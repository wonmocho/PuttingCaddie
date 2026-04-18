# 측정 최종화 정책·세션 구조 개편 정리

거리·경사 **정책 레이어 분리**, **0값 오염 방지**, **타깃 모드 세션 고정**, **거리 하드 가드 차단**, **재발 방지 단위 테스트**를 한 번에 반영한 변경의 요약 문서입니다. (코드 기준: `android/app` 모듈)

---

## 1. 배경과 목표

### 해결하려던 구조 문제

| 문제 | 방향 |
|------|------|
| SharedP3가 유효한데도 Phase1 등으로 덮여 **0%·잘못된 표시** | SharedP3 우선, 실패 시 **null / UNAVAILABLE** (0으로 위장 금지) |
| 거리 가드가 경고 수준만 있고 **통과** | 가드 실패 시 **REJECTED**, `distanceOk` 등으로 **재측정** |
| slope 정책이 distance에 영향 (또는 그 반대) | **거리·상하경사·좌우경사** 결정 타입 분리 |
| 0이 “실제 0 / 미측정 / 거절”에 혼용 | `MetricStatus`: VALID / UNAVAILABLE / REJECTED |
| ball/cup 모드가 세션 중 바뀌며 **편향** | 세션 시작 시 모드 고정, **불일치 시 하드 실패** |
| experimental이 shadow-only인데 표시 신뢰도가 높음 | 그래픽 confidence **조건부 하향** |

### 비목표 (이번 라운드에서 의도적으로 제외)

- experimental 입력 구조·`buildBallFloorRingSamples` 등 **P2 이후**
- offset-anchor `candidateCount=0` 등 **별도 이슈 트랙**
- `SlopeDecision.forwardPct` 필드명을 `valuePct`로 바꾸는 **대규모 리네이밍** (KDoc으로 의미만 명시)

---

## 2. 핵심 SSOT: `MeasurementFinalizationPolicy`

**파일:** `android/app/src/main/kotlin/com/wmcho/puttingcaddie/MeasurementFinalizationPolicy.kt`

### 2.1 타입

- **`MetricStatus`**: `VALID`, `UNAVAILABLE`, `REJECTED`
- **`SlopeSource`**: `SHARED_P3`, `PHASE1`, `EXPERIMENTAL`
- **`TargetMode`**: `CUP_STANDARD`, `BALL_ON_FLOOR`
- **`SlopeDecision`**: `status`, `source`, `forwardPct`(※좌우 결정 시에는 **lateral %**를 동일 필드에 담음), `qualityLabel`, `reason`
- **`DistanceDecision`**: `status`, `valueMeters`, `reason`
- **`FinalMeasurementResult`**: `distance`, `forwardSlope`, **`lateralSlope`** (세 축 SSOT)

### 2.2 세션 (타깃 모드 고정)

- **`beginSession(requested)`**  
  - 최초: `sessionTargetMode` 설정 후 `sessionLocked = true`  
  - 이미 잠김: **`check(sessionTargetMode == requested)`** — 다르면 `IllegalStateException`, 메시지 `"TARGET_MODE_CHANGED_DURING_SESSION"`
- **`endSession()`**: 잠금 해제 (`sessionLocked = false`)
- **`sessionTargetMode`**: 읽기 전용 노출 (거리 하드 가드 등에서 사용)

### 2.3 경사 최종 선택

- **`sharedP3PrimaryUsable(ui)`**: 로그 품질·normalY·residual·forward 가용성·(있으면) `experimentalSharedSlope` valid 등
- **`chooseFinalForwardSlope(ui)`**  
  - SharedP3 1순위 → (비-shadow 시) experimental → experimental reject 시 **좁은 Shared 로그 폴백** → Phase1 (same-plane 휴리스틱·`ballCupSamePlane` 조건으로 Phase1 억제) → 없으면 `UNAVAILABLE` + 사유
- **`chooseFinalLateralSlope(ui)`**: forward와 **동일 우선순위·동일 가드 패턴**, 축만 lateral 필드 사용
- **`forwardPctFromSharedP3Primary` / `lateralPctFromSharedP3Primary`**
- **`samePhysicalFloorHeuristicDisablesPhase1(ui)`**: `samePlane == false` 이고 평면 각 &lt; 4° 등일 때 Phase1 폴백 억제 (트랙 병합 아님, **선택 우선순위만**)

### 2.4 experimental / Shared 보조

- **`EXPERIMENTAL_SLOPE_SHADOW_ONLY`**: `true`면 experimental을 상하 표시 후보에서 사실상 제외
- **`sharedP3LogUsableForExperimentalFallback`**, **`experimentalRejectEligibleForSharedP3`**

### 2.5 거리 하드 가드

- **`distanceHardGuardsPass(...)`** / **`distanceHardGuardsPass(ui)`**  
  - `trackingState != "TRACKING"` → 실패  
  - `BALL_ON_FLOOR` + `centerYOffsetApplied == true` → 실패  
  - `ui.distanceMeters`(또는 인자 `distanceMeters`)로 **`thresholdsForDistance`** 선택 후:  
    - **&lt;3m**: px&lt;45, XZ&gt;0.50, spread&gt;0.60  
    - **3m~6m 미만**: px&lt;40, XZ&gt;0.45, spread&gt;0.50  
    - **6m+**: px&lt;30, XZ&gt;0.35, spread&gt;0.40  
- **`distanceDecisionFromUi(ui)`**  
  - 거리 ≤ 0 또는 비유한 → `UNAVAILABLE`  
  - 가드 실패 → **`REJECTED`**, `valueMeters = null`  
  - 통과 → `VALID`

### 2.6 일괄 API

- **`finalMeasurementFromUi(ui)`** → `FinalMeasurementResult(distance, forwardSlope, lateralSlope)`

---

## 3. 연동 파일별 변경 요약

### 3.1 `DistanceMeasurementActivity.kt`

- **`onDestroy()`**: `runCatching { MeasurementFinalizationPolicy.endSession() }` 후 `super.onDestroy()`
- **시작**: prefs `KEY_MEASUREMENT_TARGET_MODE` → `TargetMode` 매핑 후 **`beginSession(tm)`**, 이어 `StartPressed`
- **리셋 터치**: **`endSession()`** 후 `ResetPressed`
- **`onResume` + `proForceResetOnResume`**: **`endSession()`** 후 `ResetPressed`
- **`buildMeasurementLogJson`**: `"finalMeasurementSsot"` + `logFinalSlopeAxes` (RESULT)
- **FINAL UI 거리**: `distanceDecisionFromUi`가 `REJECTED`면 문자열 **`greeniq_distance_hard_rejected`** (영: RETAKE, 한: 재측정)
- **그래픽 번들 confidence**: `graphicSource == "EXPERIMENTAL"`일 때  
  - `EXPERIMENTAL_SLOPE_SHADOW_ONLY` → **0.3f**  
  - 아니면 **0.85f**  
- 기존 `when`에 `SHARED_P3`, `SHARED_P3_FALLBACK`, `PHASE1_FALLBACK` 등 confidence 분기 보강(이전 라운드 포함)

### 3.2 `V31StateMachine.kt`

- **`resetAll()`** 말미: **`MeasurementFinalizationPolicy.endSession()`** (엔진 전역 리셋과 세션 해제 일원화)
- **컵 멀티레이 `centerYOffsetRatio`**: `sessionTargetMode == BALL_ON_FLOOR`이면 **0f**, 아니면 기존 `CUP_CENTER_Y_OFFSET_RATIO` (볼 바닥 모드에서 cup 중심 Y 오프셋 비활성)

### 3.3 `SlopeFieldTestLog.kt`

- 상하 경사 그래픽/스냅: **`chooseFinalForwardSlope`** 결과를 우선 사용, 실패 시 기존 Shared/게이트 레거시 분기
- 소스 문자열: `SHARED_P3` / `SHARED_P3_FALLBACK` 등 Policy `SlopeSource`와 정합
- experimental/shared 판단 일부를 Policy 헬퍼로 위임

### 3.4 `DistanceFieldTestLog.kt`

- RESULT 시 **`finalMeasurementFromUi(ui)` 단일 호출** → 스냅샷 **`finalMeasurementSsot`** + 거리 정책(`distance`) 재사용
- 스냅샷에 Policy 기반 거리 상태 필드 (예: raw vs guard, `REJECTED` 시 분류)
- **`distanceOk`**: raw OK이면서 가드 미거절 등 정책과 정합
- **`classifyResult`**: 전달된 `DistanceDecision`으로 하드 가드 실패 분류(중복 Policy 호출 없음)

### 3.5 리소스

- `android/app/src/main/res/values/strings.xml` — `greeniq_distance_hard_rejected`
- `android/app/src/main/res/values-ko/strings.xml` — 동 키, 한글 문구

### 3.6 단위 테스트

- **`android/app/src/test/.../MeasurementFinalizationPolicyTest.kt`**
  - 거리 하드 가드 (px, ball+yoffset, 정상 통과, **원거리 구간 완화**)
  - **`beginSession` 모드 불일치 시 `IllegalStateException`**
  - forward / lateral **SharedP3가 Phase1보다 우선**
  - same-plane 휴리스틱으로 Phase1 비활성 → forward `NO_USABLE_FORWARD_SOURCE`
  - **`finalMeasurementFromUi`** 세 축 검증
  - `distanceDecisionFromUi` REJECTED / VALID
  - `@After`에서 **`endSession()`**으로 테스트 간 오염 방지

### 3.7 보강 라운드 (세션 종료·SSOT JSON·축 로그·거리 적응 가드)

- **`DistanceMeasurementActivity.onDestroy()`**  
  - `runCatching { MeasurementFinalizationPolicy.endSession() }` 후 `super.onDestroy()` — 프로세스 유지 이탈 시 세션 잠금 잔존 완화.
- **거리 하드 가드 거리 구간** (`MeasurementFinalizationPolicy`)  
  - `distanceMeters` 기준: **&lt;3m** / **3m~6m 미만** / **6m+** 에서 `minProjectedCupPx`, `maxXzDeltaM`, `maxCupSpreadM` 상이 (`thresholdsForDistance`).
- **`SlopeAxis` + `logFinalSlopeAxes`**  
  - RESULT JSONL 작성 시 `SLOPE_AXIS_DECISION` 로그로 forward / lateral 각각 status·source·reason.
- **`finalMeasurementSsot` JSON + 스냅샷**  
  - `DistanceFieldTestLog.feedbackSnapshot`이 RESULT에서 **`finalMeasurementFromUi` 한 번**만 호출해 `finalMeasurementSsot` 필드에 보관.  
  - `buildMeasurementLogJson`에서 `"finalMeasurementSsot"` 키로 `appendFinalMeasurementSsotJson` 출력 (거리/상하/좌우 status·값·reason·source).  
  - `classifyResult`는 동일 `DistanceDecision`을 넘겨 **중복 `distanceDecisionFromUi` 호출 제거**.

---

## 4. 세션·`endSession()` 호출 흐름 (운영 점검용)

### 호출되는 경로

1. **`DistanceMeasurementActivity.onDestroy()`** → `endSession()` (예외 삼킴)
2. **`DistanceMeasurementActivity`** 리셋 영역 → `endSession()` → `ResetPressed`
3. **`onResume`** + `proForceResetOnResume` → 동일
4. **`V31StateMachine.onUiEvent(ResetPressed)`** → **`resetAll()`** 끝의 **`endSession()`**  
   → `ResetPressed`만 스테이트머신에 도달해도 세션은 풀림.

### 주의

- **`beginSession`의 `check`는 유지**(자동 복구 없음). 세션 종료 누락 시에만 모드 변경 + 재시작 조합에서 예외 위험이 남음 — 위 `onDestroy`로 대부분 완화.

---

## 5. 후속 작업 후보 (문서·코드 밖 로드맵)

- 런타임 1회: 위 **세션 종료 누락 시나리오** 기기 점검
- **P2**: `buildBallFloorRingSamples`, 3m bias 테스트, 실전 cup/ball 검증
- **별도 이슈**: offset-anchor `candidateCount=0`
- 장기: `SlopeDecision` 필드명을 중립명(`valuePct`)으로 리네이밍

---

## 6. 빌드·테스트

- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` 통과 기준으로 정책 단위 테스트까지 포함해 검증됨.

---

*문서 버전: 구조 개편 반영 후 정리. 세부 임계값은 `MeasurementFinalizationPolicy.kt` 소스가 단일 기준입니다.*
