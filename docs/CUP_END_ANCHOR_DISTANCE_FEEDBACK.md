# 컵 END 앵커 · 거리 피드백 JSON/이메일 — 수정 요약

이 문서는 **컵 엔드 앵커 커밋 게이트**, **LIVE XZ 정렬 멀티레이**, **거리/앵커 진단 필드**가 **앱 내 이메일 피드백 JSON(`PC_feedback_*.json`)** 및 본문에 반영된 내용을 정리합니다.

---

## 1. 목적

- **멀티레이 대표 히트**가 라이브 컵 월드(`lastLiveCupWorldForDistance`)와 XZ로 어긋나 커밋되는 문제를 줄이기 위해, 샘플링 단계에서 **XZ 최근접 히트 선택**을 지원한다.
- **END_LOCK 직전**에 커밋 후보 vs 라이브 컵 XZ 거리가 크면 **한 프레임씩 커밋을 연기**(veto). 다만 무한 대기를 막기 위해 **재시도 상한** 후에는 한 번 **게이트 우회**로 커밋할 수 있다.
- 동일 진단을 **Logcat**, **`DistanceFeedbackSnapshot` JSON**, **이메일 본문**에서 일관되게 볼 수 있게 한다.

---

## 2. 관련 파일

| 파일 | 역할 |
|------|------|
| `android/.../CupEndAnchorCommitPolicy.kt` | XZ 거리, `strictFar` 판정, 임계값(0.15m / 0.30m), veto 여부 |
| `android/.../V31HitSampler.kt` | `sampleCupPlaneMultiRay(..., liveWorldAlignForHitPick)` — 지정 시 median 대신 **XZ로 라이브에 가장 가까운** 그리드 히트 선택 |
| `android/.../V31StateMachine.kt` | `lastLiveCupWorldForDistance` 갱신, `shouldBlockCupEndAnchorCommit`, 커밋 시 스냅샷 캡처, `UiModel` 필드 노출 |
| `android/.../DistanceFieldTestLog.kt` | `DistanceFeedbackSnapshot`, `appendDistanceFeedbackJson`, Logcat 태그 `CUP_END_ANCHOR_COMMIT_JSON` 등 |
| `android/.../DistanceMeasurementActivity.kt` | 피드백 이메일 본문·`PC_feedback_*.json` 첨부에 `lastDistanceFinalSummary` 등 연동 |

---

## 3. 정책: `CupEndAnchorCommitPolicy`

- **`xzDistanceMeters(a, b)`**: 월드 `FloatArray` `[x,y,z]` 기준 **XZ 평면 거리**(m).
- **`strictFarMode`**: 투영 컵 크기(px), spread, 유효 샘플 수 등으로 **저품질·원거리에 가까운** 상황이면 true.
- **임계값**
  - 일반: **`CUP_DELTA_NEAR_THRESHOLD_M` = 0.15** m  
  - `strictFar == true`: **`CUP_DELTA_FAR_THRESHOLD_M` = 0.30** m  
- **`vetoShouldBlock(gateDeltaM, strictFar)`**: `gateDeltaM > thresholdM(strictFar)` 이면 커밋 차단.

---

## 4. 엔진: `V31StateMachine`

### 4.1 LIVE 정렬용 멀티레이

- **`tick()` 순서:** `AIM_END` / `STABILIZING_END` 에서 **GREENIQ LIVE 블록이 `sampleCupPlaneMultiRay` 보다 먼저** 실행되어 `lastLiveCupWorldForDistance`·`lastLiveCupWorldFrameTimestampNs` 가 갱신된 뒤 멀티레이가 돈다. 멀티레이 정렬·게이트·커밋 스냅샷이 **동일 틱의 동일 LIVE 스냅샷**을 쓴다.
- `cupLiveAlignForMultiRaySample()` → `lastLiveCupWorldForDistance?.copyOf()` 를 `sampleCupPlaneMultiRay`의 **`liveWorldAlignForHitPick`** 으로 전달 (기본 / FAR 확장 / retry 경로 동일).
- 컵 AIM·END 안정화·FAIL 샘플링 구간에서 사용.

### 4.2 커밋 직전 게이트

- **`shouldBlockCupEndAnchorCommit(stabilizingHit, sample)`**
  - `lastLiveCupWorldForDistance` 가 없으면 게이트 **미적용**(차단 안 함), 내부 재시도 카운터 리셋.
  - 후보 `world3FromPose(stabilizingHit.hitPose)` 와 라이브 XZ 거리 = `gateDeltaM`.
  - veto 시 **`cupEndAnchorGateRetryCount`** 증가, **`CUP_END_ANCHOR_GATE_MAX_RETRIES` (기본 12)** 미만이면 그 틱은 `confirmLock` 하지 않음.
  - 상한 초과 시 **`cupEndAnchorGateBypassedMaxRetriesPending = true`** 로 한 번 우회 허용 후 커밋 가능(로그 `gate=bypass_max_retries`).

### 4.3 호출 위치

- **정상 락**: 시그마·품질·라이브 스냅샷 등 기존 가드 통과 직후, `confirmLock` 전에 `shouldBlockCupEndAnchorCommit` 이 true면 `return buildUi` 만 수행.
- **소프트 락**(타임아웃 분기, `CUP_SOFT_LOCK_ENABLED` 등): `confirmLock` 전 동일 게이트.

### 4.4 커밋 시 캡처 → `UiModel` (END_LOCKED / RESULT)

- **`cupCandidateVsLiveHitXZDeltaMAtCommit`**: 커밋 직전 후보 vs 라이브 컵 XZ(m) — 게이트와 동일 정의.
- **`cupEndAnchorCommitStrictFar`**, **`cupEndAnchorCommitGateThresholdM`**, **`cupEndAnchorGateBypassedMaxRetries`** / **`cupEndAnchorCommitBypassSession`**: 커밋 순간의 정책 스냅샷·통계용 bypass 플래그.
- **`cupLiveWorldFrameTimestampNs`**: 커밋 시점에 동기화된 LIVE 갱신 프레임 타임스탬프(ns).

### 4.5 Logcat

- **`CUP_END_ANCHOR_ROOT`**: `gate=veto` / `gate=bypass_max_retries`, `gateDeltaM_m`, `thr_m`, `strictFar`, **`liveFrameNs`**, retry 카운트 등.

---

## 5. 로그·JSON: `DistanceFieldTestLog`

### 5.1 `DistanceFeedbackSnapshot` (추가·연관 필드 요약)

- 앵커·라이브 끝점 비교: `ballLiveHitWorldAtFinish`, `cupLiveHitWorldAtFinish`, `cupAnchorHitWorldBeforeSnap`, `cupAnchorPoseWorldAfterSnap`, `ballAnchorVsLiveHitXZDeltaM`, `cupAnchorVsLiveHitXZDeltaM`, `liveHitPairXZM`, `cupSnapXZDeltaM`, `endAnchorLowQualityFar` 등.
- 컵 엔드 앵커 커밋:
  - **`cupCandidateVsLiveHitXZDeltaMAtCommit`**
  - **`cupEndAnchorCommitStrictFar`**
  - **`cupEndAnchorCommitGateThresholdM`**
  - **`cupEndAnchorGateBypassedMaxRetries`** / **`cupEndAnchorCommitBypassSession`**(통계용 동일 의미)
  - **`cupLiveWorldFrameTimestampNs`**, **`cupLiveAlignAndGateSameFrame`**

### 5.2 `appendDistanceFeedbackJson`

- 위 필드들이 **이메일 첨부 JSON**의 `lastDistanceFinalSummary` 객체(또는 동일 스냅샷이 직렬화되는 위치)에 **그대로 포함**된다.

### 5.3 Logcat (요약 한 줄)

- **`CUP_END_ANCHOR_COMMIT_JSON`**: `gateDeltaAtCommit_m`, `thr_m`, `strictFar`, `bypassMaxRetries`, `bypassSession`, `liveFrameNs`, `alignGateSameFrame`
- **`CUP_END_ANCHOR_ROOT`**: `gate=veto` / `gate=bypass_max_retries` 시 **`liveFrameNs`** 포함
- 기존 **`ANCHOR_LIVE_ENDPOINT_COMPARE`**, **`DISTANCE_GUARD_SUMMARY`** 등과 함께 분석 가능.

---

## 6. 이메일: `DistanceMeasurementActivity`

- 피드백 메일 **본문**에 최근 세션의 `distanceFinalSummary`가 있을 때:
  - **`CupEndAnchor(commit):`** 줄에 `gateDeltaXZ_m`, `thr_m`, `strictFar`, `bypassMaxRetries`, `liveFrameNs`, `alignGateSameTick`, 그리고 bypass 시 **`[bypass after max retries]`** 문구.
- 첨부 **`PC_feedback_<timestamp>.json`** 에는 스키마 안내 문구대로 **`cupCandidateVsLiveHitXZDeltaMAtCommit`**, **`cupEndAnchor*`**, **`cupLiveWorldFrameTimestampNs`** 등이 포함된다.

---

## 7. 빌드·검증 (참고)

- 로컬에서 `assembleDebug` 등으로 컴파일 확인.
- 실측 후: 이메일 첨부 JSON에서 `cupCandidateVsLiveHitXZDeltaMAtCommit` / `cupEndAnchorCommitGateThresholdM` / `cupEndAnchorGateBypassedMaxRetries` 가 **END 완료 세션**에 채워지는지 확인.

---

## 8. 버전 메모

- 본 문서는 저장소 **현재 상태** 기준으로 작성되었으며, 상수(`CUP_END_ANCHOR_GATE_MAX_RETRIES`, 임계값 m)는 `V31StateMachine.kt` / `CupEndAnchorCommitPolicy.kt` 를 기준으로 한다.

---

## 9. 코드 검증 결과 (저장소 grep/리드 기준, 최종 점검)

아래는 **합의 스펙 대비 실제 Kotlin 연결**을 저장소에서 확인한 결과이다. (실기 로그 파일 없이 **소스만** 근거로 판단.)

### 9.1 `cupCandidateVsLiveHitXZDeltaMAtCommit` 정의 (긴급 ①)

| 항목 | 결과 |
|------|------|
| **사후 앵커 vs LIVE로 바뀌지 않는가** | **충족.** `confirmLock`의 `STABILIZING_END` 분기에서 `capturedCupEndAnchorCommitGateDeltaM`은 **`world3FromPose(hit.hitPose)`** 와 **`lastLiveCupWorldForDistance`** 로 `CupEndAnchorCommitPolicy.xzDistanceMeters` 계산. **`createAnchor()` 이전**에 캡처한다. |
| **게이트와 동일 식인가** | **충족.** `shouldBlockCupEndAnchorCommit`도 `cand = world3FromPose(stabilizingHit.hitPose)`, `live = lastLiveCupWorldForDistance`, 동일 `xzDistanceMeters`. |
| **`UiModel` 매핑** | `cupCandidateVsLiveHitXZDeltaMAtCommit` ← `capturedCupEndAnchorCommitGateDeltaM` (`END_LOCKED` / `RESULT` 에만 비-null). |

**LIVE 시점 정렬(리팩터 반영):** `AIM_END` / `STABILIZING_END` 에서 **LIVE 블록이 멀티레이 샘플보다 먼저** 실행되므로, `liveWorldAlignForHitPick`·게이트·커밋 스냅샷이 **동일 틱에서 갱신된 `lastLiveCupWorldForDistance`**(및 `lastLiveCupWorldFrameTimestampNs`)를 기준으로 한다. JSON·Logcat에는 `cupLiveWorldFrameTimestampNs`, `cupLiveAlignAndGateSameFrame`, `cupEndAnchorCommitBypassSession` 등으로 진단·통계가 가능하다.

### 9.2 `sampleCupPlaneMultiRay` 대표 히트 (긴급 ②)

| 항목 | 결과 |
|------|------|
| **`liveWorldAlignForHitPick != null`일 때 median이 아닌가** | **충족.** `V31HitSampler`에서 그리드 후보에 대해 **XZ 거리 제곱 최소**로 `SelectedHit` 선택. `null`이면 기존처럼 median에 3D 근접. |

### 9.3 `cupEndAnchorGateBypassedMaxRetriesPending` 1회성 (긴급 ③)

| 항목 | 결과 |
|------|------|
| **max retry 초과 시 true** | `shouldBlockCupEndAnchorCommit`에서 retry 상한 초과 시 `cupEndAnchorGateBypassedMaxRetriesPending = true` 후 `return false`(커밋 허용). |
| **커밋 직후 소비** | `confirmLock` `STABILIZING_END`에서 `capturedCupEndAnchorGateBypassedMaxRetries = cupEndAnchorGateBypassedMaxRetriesPending` 직후 **`cupEndAnchorGateBypassedMaxRetriesPending = false`**. |
| **게이트 통과 시 리셋** | veto가 아닌 경우(`!vetoShouldBlock`) `cupEndAnchorGateBypassedMaxRetriesPending = false`. |
| **세션 리셋** | IDLE 등 전환 시 캡처 필드·카운터 초기화 블록 존재. |

→ **한 번 우회 플래그가 다음 커밋에 누수해 게이트가 무력화되는 패턴은 코드상 방지됨.**

### 9.4 JSON / 이메일 / Logcat 동일 원천 (긴급 ④)

| 항목 | 결과 |
|------|------|
| **`DistanceFeedbackSnapshot`** | `feedbackSnapshot()`이 `ui.cupCandidateVsLiveHitXZDeltaMAtCommit` 및 `cupEndAnchorCommit*` 를 **`V31StateMachine.UiModel`에서 직접 복사**. |
| **`appendDistanceFeedbackJson`** | 위 스냅샷 필드를 그대로 직렬화. |
| **이메일 본문** | `recentSessions.lastOrNull()?.distanceFinalSummary`의 **동일 스냅샷**에서 `CupEndAnchor(commit):` 줄 출력. |

### 9.5 후순위 로그 교차검증

- `CUP_END_ANCHOR_COMMIT_JSON`(요약) / `CUP_END_ANCHOR_ROOT`(게이트 이벤트) / `ANCHOR_LIVE_ENDPOINT_COMPARE`(앵커·라이브 XZ)는 **역할이 다르므로** 수치가 항목마다 다를 수 있다. 다만 **9.1**의 스냅샷 필드는 **커밋 순간 후보 vs LIVE**에 고정되어 있다.

---

**최종 판단:** 문서·필드 정의·호출 위치는 저장소 코드와 **일치**한다. **9.1**의 과거 tick 순서 리스크는 **LIVE 선행 갱신**으로 완화되었으며, **bypass 세션**은 `cupEndAnchorCommitBypassSession`·이메일 본문 `[bypass after max retries]`·배너 `|CUP_END_BYPASS` 로 구분 가능하다.
