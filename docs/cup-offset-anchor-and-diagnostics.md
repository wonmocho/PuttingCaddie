# 컵 락 진단 · Offset-anchor 재투영 · Throttle 캐시 — 변경 정리

이 문서는 **4/3 기준 협의**에 맞춘 컵 측정 관련 변경(계측, 재투영, throttle, JSON)을 한곳에 모은 요약입니다.  
**제품 UX(컵 중심 조준, 줌 없음, “옆을 찍으라” 문구 없음)** 는 바꾸지 않았습니다.

---

## 1. 목표와 원칙

### 1.1 컵 락 실패 분리 관찰 (계측만)

- **변경하지 않음**: sigma threshold, `LOCK_CONSEC_TICKS`, `LOCK_TIME_GATE_NS`, soft lock 기준, samePlane/slope 해석, tier 구조.
- **추가**: Logcat 태그, 정규화 reason, `UiModel` / 측정 JSON 필드로 사후 집계 가능하게 구성.

### 1.2 Offset-anchor 재투영 (엔진 내부만)

- 사용자는 **항상 컵 중심**만 조준.
- **Offset ground 후보**는 우하단 sector 스크린 후보 → world hit → **legacy 중심 대비 8~20m 월드 거리** 필터.
- **선택된 offset 지점의 월드 좌표는 최종 cup wp로 쓰지 않음.**
- **Center ray × local plane 교점**만 `lastLiveCupWorldForDistance`에 반영 → 거리 LIVE, END 앵커, phase1 앵커 기반 slope와 정렬.
- 실패 시 **legacy 중심 경로**로 fallback.

### 1.3 Throttle / 캐시 (성능·지터 완화)

- LIVE마다 전체 재투영을 돌리지 않도록 **120ms** 및 **화면·tracking·카메라 자세** 변화 시에만 재계산.
- **성공 시** 마지막 재투영 월드를 캐시; **실패 시** 해당 시점의 **legacy fallback 월드**를 캐시(실패 “진짜” 캐시).
- **`FloatArray`는 저장·반환 모두 `copyOf()`** 로 참조 공유 차단.
- Throttle 상태는 **프로세스 전역 `object`가 아니라 `V31StateMachine` 인스턴스의 `CacheState`**.

---

## 2. 주요 파일

| 파일 | 역할 |
|------|------|
| `CupLockDiagnostics.kt` | 컵 락 reason 정규화, gate/timeline/outcome/summary 한 줄 포맷 |
| `V31StateMachine.kt` | STABILIZING_END 진단 emit, LIVE `lastLiveCupWorldForDistance` 갱신, offset throttle 연동, `UiModel` 필드, `CacheState` 인스턴스 |
| `CupOffsetAnchorEstimator.kt` | 후보 생성, ground hit, `SharedPlaneFit` 로컬 평면, ray–plane 교차, throttle 래퍼, `CacheState` 클래스 |
| `DistanceMeasurementActivity.kt` | 측정 JSON `diag` 블록에 컵 락·offset·throttle·slope 영향 플래그 필드 append |

---

## 3. 컵 락 분리 진단 (CupLockDiagnostics + V31StateMachine)

### 3.1 Logcat (요약)

- **`CUP_LOCK_GATE_SNAPSHOT`**: stabilizing 동안 핵심 게이트 한 번에 (throttle 적용).
- **`CUP_LOCK_BLOCK_REASON`**: primary/secondary 정규화 + 기존 `failDetailCode` 유지.
- **`CUP_SIGMA_TIMELINE`**: sigma 전환 또는 ~100ms 간격.
- **`CUP_LOCK_OUTCOME`**: 단계 종료 한 줄.
- **`CUP_LOCK_SUMMARY`**: 세션 관점 한 줄 요약.
- **`CUP_LOCK_DIAG`**: 위 계열 태그로 묶어서 출력되는 경우 있음.

### 3.2 정규화 reason

- `CupLockDiagnostics.classifyCupLockBlockCascade`, `normalizeFromFailDetail` 등으로  
  `sigma_not_ok`, `no_consecutive_ok`, `time_gate_not_ok`, `cup_quality_guard`, `live_snapshot_guard`, `far_mode_hold`, `no_eligible_live_cup_world` 등 집계 가능 코드로 매핑.

### 3.3 UiModel / JSON (컵 락)

`UiModel` 및 `buildMeasurementLogJson`의 `cup.diag` 등에 예시 필드:

- `cupLockPrimaryReason`, `cupLockSecondaryReason`, `cupLockOutcome`
- `cupSigmaMarginCm`, `cupSigmaUsedCm` / `cupSigmaMaxCm` (cm 요약)
- `cupMaxConsecutiveOkReached`, `cupConsecutiveRequired`, `cupElapsedStabilizingMs`
- `cupProjectedPxEnd`, `cupValidSampleCountEnd`
- `cupSoftHoldTriggered`, `cupSoftLockTriggered`
- `cupTrackingStateEnd`, `cupFarModeHoldActive`, `cupQualityGuardPassed`, `cupLiveSnapshotAvailable`, `cupEligibleLiveCupWorldAvailable`

`CupLockOutcomeSummary`에 far/quality/live/eligible/tracking 종료 스냅샷 포함.

---

## 4. Offset-anchor 재투영 (CupOffsetAnchorEstimator)

### 4.1 파이프라인

1. **Legacy** `centerWorldLegacy`: 기존 LIVE plane 교차 또는 center `hitTest` 결과.
2. **스크린 후보**: 컵 중심 기준 우하단 sector, 반경은 `projectedCupPx` 기반(픽셀).
3. **후보별**: 소반경 multi-hit → 분산 → **8~20cm** legacy 대비 필터 → 주변 3×3 plane 샘플 → `SharedPlaneFit.fitFromWorldPoints` (입력은 **해당 후보 주변 점만**, ball–cup corridor 혼입 없음).
4. **Center ray** (기존 UV/내적과 동일 계열) × **local plane** → 교점 = 재투영 월드.
5. 성공 시 그 점을 `lastLiveCupWorldForDistance`에 반영; 실패 시 legacy 유지.

### 4.2 플래그

- `USE_CENTER_AIM_OFFSET_ANCHOR` (기본 true)
- `OFFSET_ANCHOR_DEBUG_LOG` (기본 true)

### 4.3 Slope 정렬 상태 (로그/JSON만 명시)

- **정렬됨**: 거리 LIVE, `endAnchor`, **phase1 앵커 기반** slope (재투영 wp).
- **아직 중심 ROI 기준**: `experimentalCupSlopeSamples` / LocalSurfaceFit 실험 경로.  
  JSON/UI에 `cupAnchorReprojectedAffectsExperimentalSurface = false` 등으로 **문서화만** (이번에 로직 변경 없음).

---

## 5. Throttle · CacheState

### 5.1 세션 로컬

- 타입: **`CupOffsetAnchorEstimator.CacheState`**
- 소유: **`V31StateMachine`의 `private val cupOffsetAnchorCacheState`**
- 리셋: `resetAll`, LIVE 이탈(`!inLiveStates`), `StartPressed`(측정 재시작), **`enterStabilizingEnd`** 진입 시 `cupOffsetAnchorCacheState.reset()`.

### 5.2 재계산 조건 (`shouldRecomputeOffsetAnchor`)

- 마지막 평가 이후 **≥ 120ms**
- **TrackingState** 변경
- **중심 픽셀** 이동 ≥ 8px
- **projectedCupPx** 변화 ≥ 6px
- **카메라 translation** 변화 ≥ 0.03m 또는 **forward** 각 차이 ≥ 2.5°

### 5.3 캐시 동작

| 직전 결과 | throttle 구간 출력 월드 |
|-----------|---------------------------|
| 재투영 성공 | `lastSuccessWorld`의 **복사본** |
| 재투영 실패 | `lastFailureFallbackWorld`의 **복사본** (없으면 현재 legacy 복사) |

- `resolveFinalCupWorldPoint` 성공 시 반환값도 **`reproj.copyOf()`** 로 분리.

### 5.4 진단 필드 (Diagnostics → UiModel → JSON)

- `throttleMode`: `recomputed_success` | `recomputed_fail` | `throttled_cache_success` | `throttled_cache_fail` | `feature_disabled`
- `throttleAgeMs`, `cacheHit`, `cacheWasSuccess`
- `cameraTranslationDeltaM`, `cameraAngleDeltaDeg` (throttle 히트·재계산 시점 스냅샷)
- `reprojectedAffectsDistance` / `reprojectedAffectsEndAnchor` / `reprojectedAffectsExperimentalSurface`

JSON 예시 키: `cupAnchorThrottleMode`, `cupAnchorThrottleAgeMs`, `cupAnchorCacheHit`, `cupAnchorLastFailureReason`, `cupAnchorCacheWasSuccess`, `cupAnchorCameraMovedM`, `cupAnchorCameraAngleMovedDeg`, `cupAnchorReprojectedAffects*` 등.

### 5.5 로그 스팸 완화

- throttle cache 사용 로그는 **약 250ms** 간격으로 `Log.d("CupOffsetAnchor", …)` 제한.

### 5.6 `qualityProbeStatus` / `qualityInvalidateReason` 집계 기준 (운영·대시보드)

- **이전**: `null`이 “미기입”과 “정상처럼 보이는 값”에 섞여 해석되기 쉬움.
- **현재**: `Diagnostics` 기본값으로 **`ok` / `none`** 이 항상 채워지고, throttle·recompute·feature OFF 경로는 **`finalizeCupOffsetDiagnostics`** 를 통과한 뒤만 외부로 나감.
- **집계 권장**: 정상 경로는 **`qualityProbeStatus == "ok"` AND `qualityInvalidateReason == "none"`** 로 잡는다. (나머지 조합은 프로브 실패·품질 무효화·재계산 트리거 등으로 구분.)

---

## 6. 통합 지점 (V31StateMachine)

- **LIVE** (`AIM_END` / `STABILIZING_END`): plane 또는 hitTest로 `legacyW` 산출 후  
  `applyOffsetAnchorIfEnabled` → `resolveFinalCupWorldPointWithThrottle(state, …)` → `usedW`로 `raw` 거리 및 `lastLiveCupWorldForDistance` 갱신.
- **END_LOCK**: `cupWorldForCommit`은 여전히 eligibility 검사 후 `lastLiveCupWorldForDistance` freeze → 재투영이 반영된 동일 계열.

---

## 7. 이번 범위에서 하지 않은 것

- Offset 후보·8–20cm·평면 잔차·재투영 수식 **본체 변경 없음**.
- Experimental / LocalSurfaceFit **경로를 재투영 wp에 맞추는 구현** (다음 단계).
- UX 문구, 줌, YOLO, sigma/lock 임계값 튜닝.

---

## 8. 빌드

- 변경 후 **`./gradlew :app:compileDebugKotlin`** 로 Kotlin 컴파일 확인 권장.

---

## 9. 한 줄 요약

**컵 락은 집계 가능한 진단만 추가했고, 컵 LIVE 월드는 “중심 조준 유지 + 우하단 내부 anchor로 local plane + 중심 레이 재투영”으로 안정화하되, throttle·세션 로컬 캐시·카메라 invalidation·`copyOf()`로 성능과 참조 안전을 맞춘 상태입니다.**
