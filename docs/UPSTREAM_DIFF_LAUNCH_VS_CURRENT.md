# 런칭버전 vs 현재버전 upstream 차이 비교 보고서

> **목적**: finalDistance SSOT 문장은 동일해도, 그 최종값을 만들기 **전 단계**에서 무엇이 달라졌는지 정확히 비교  
> **수정 없음**: 코드 변경 없이 비교·보고만 수행

---

## 1. BALL 차이

### 런칭버전
- **샘플링**: 3x3 grid, `BALL_GRID_STEP_PX = 6f`, `roiScreen.centerX/Y` 기준 패치
- **point 선택**: `gridCount==9` → `pickBestByFarthestDistance` (PLANE/DEPTH/POINT 모두)
- **fix source 우선순위**: PLANE > DEPTH > POINT (동일)
- **freeze**: `BALL_FREEZE_TIMEOUT_NS = 2s`, `ballLastGoodHit` 유지
- **anchor 생성**: `currentBallFixNeedHits()` (zoom>=2.9 → 4, else 3), `BALL_FIX_MIN_HOLD_NS`, `START_ANCHOR_CLOSE_TO_AVG_M`
- **fixedMinSamples (BALL)**: `currentBallFixNeedHits()`

### 현재버전
- **샘플링**: 동일 (3x3, step 6px)
- **point 선택**: 동일 (`pickBestByFarthestDistance` for gridCount==9)
- **fix source**: 동일
- **freeze**: 동일
- **anchor 생성**: 동일
- **추가**: `ballSampleRejectionReason`, `planeRejectedByPolygonCount` (진단 로그만)

### 차이점
- **실질 차이 없음**. BALL 경로는 런칭버전과 현재버전이 동일.

### 거리 bias 가능성
- **낮음**. BALL 쪽 upstream 변경이 systematic short bias의 직접 원인일 가능성은 낮음.

---

## 2. CUP 차이

### 런칭버전
- **multi-ray plan**: NEAR_7x7 / MID_5x5 / FAR_3x3 / FAR_5x5 (forceFar5x5)
- **centerYOffsetApplied**: `cy = baseRoiScreen.centerY() + (glH * 0.05f)` — **항상 적용**
- **centerFallbackUsed**: valid<=0 시 center hit 또는 depth/point fallback
- **projectedCupPx 분기**: `CUP_PROJECTED_PX_FORCE_FAR5=22`, `CONDITIONAL=24` — 동일
- **valid hit 선택**: median 기반 (median pose에 가장 가까운 hit 선택)
- **CUP expand**: needsFarExpand → forceFar5x5 또는 retry with offset 0.05

### 현재버전
- **multi-ray plan**: 동일
- **centerYOffsetApplied**: 동일 (0.05f)
- **centerFallbackUsed**: 동일
- **projectedCupPx 분기**: 동일
- **valid hit 선택**: 동일
- **CUP expand**: 동일
- **추가**: `CUP_DETECT_STATE` 로그, `CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES` 사용 (centerFallback 시 valid<5면 lock 차단)

### 차이점
- **CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES**: centerFallback 사용 시 validHits < 5면 lock 차단. 런칭버전에도 `CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES = 5` 존재. 로직 차이 여부는 CUP quality guard 블록 비교 필요.
- **실질적으로 CUP 샘플링/선택 로직은 동일**.

### 거리 bias 가능성
- **낮음**. centerYOffsetApplied, centerFallback, plan 분기는 양쪽 동일.  
- 다만 `centerYOffsetApplied`가 **항상 true**로 적용되며, cy를 아래로 5% 밀어 ROI가 실제 컵보다 아래를 가리키면 ray가 **컵보다 가까운 지면**을 맞출 수 있어, 이론적으로는 짧은 bias 가능성은 있음. 다만 런칭버전도 동일 적용.

---

## 3. FINAL 직전 차이

### 런칭버전
- **endLiveSnapshotMeters 설정**: CUP lock 직후, live stability gate 통과 시 `farModeLiveMedianAtEndLock` 또는 `liveSmoothedMeters`; 실패 시 `lastDisplayDistanceMeters`
- **lastDisplayDistanceMeters fallback**: snapshot 실패 시 사용
- **farModeLiveMedianAtEndLock**: farMode일 때 `liveMedian5OrNaN()` 사용
- **finalDistanceMeters**: `endLiveSnapshotMeters ?: lastDisplayDistanceMeters ?: 0f`
- **anchorDistance**: `distanceBetweenAnchorsMeters()` — **로그/진단에만 사용, final에 미사용**
- **finalize 타이밍**: END_LOCKED 진입 직후 1 tick 후 RESULT (또는 FinishPressed 시)

### 현재버전
- **endLiveSnapshotMeters 설정**: 동일 구조
- **lastDisplayDistanceMeters fallback**: 동일
- **farModeLiveMedianAtEndLock**: 동일
- **finalDistanceMeters**: 동일
- **anchorDistance**: 동일 (진단용)
- **추가**: `LIVE_SNAPSHOT_GUARD` — liveRaw와 liveEma 차이가 threshold 초과 시 lock 지연/retry. **런칭버전에도 동일 상수 존재** (`LIVE_SNAPSHOT_GUARD_BASE_DIFF_M`, `HOLD_NS`, `MAX_RETRIES`).

### 차이점
- **LIVE_SNAPSHOT_GUARD**: 양쪽 모두 존재. snapshot 타이밍/품질 제어는 동일.
- **실질적으로 FINAL 직전 경로는 동일**.

### 거리 bias 가능성
- **낮음**. FINAL 직전 단계에서 런칭 vs 현재 차이는 거의 없음.

---

## 4. sigma / lock 정책 차이

### 런칭버전
- **sigmaMaxEnd(dMeters)**: `raw = a + b*dMeters`, `cap = endSigmaCapMeters(dMeters)`, `floor = cap*0.7` → `max(raw, floor).coerceIn(sigmaMin, cap)`  
  **6m+ 구간 추가 완화 없음**
- **CUP_SIGMA_NEAR_RATIO**: 1.10 (존재)
- **CUP_SIGMA_SOFTPASS_RATIO**: 1.12 (존재)
- **CUP_SOFT_LOCK**: **없음** — timeout 시 바로 FAIL
- **LOCK_CONSEC_TICKS**: 6
- **END_STABILIZING_TIMEOUT_NS**: 4s
- **fixedMinSamples (CUP)**: `chooseMinSamplesForDEst(fixedDEstMeters).coerceAtMost(18)`

### 현재버전
- **sigmaMaxEnd(dMeters)**: 동일 base + **`CUP_SIGMA_FAR_RELAX_CM = 0.015f`** — dMeters >= 6m일 때 threshold에 +1.5cm 추가
- **CUP_SIGMA_NEAR_RATIO**: 동일
- **CUP_SIGMA_SOFTPASS_RATIO**: 동일
- **CUP_SOFT_LOCK**: **있음** — `TIMEOUT_SIGMA_NOT_OK` 직후, sigma가 `sigmaMax + 0.015m` 이내이고 validHits>=9, projectedPx>=24면 **구제 lock** 허용
- **LOCK_CONSEC_TICKS**: 6 (동일)
- **END_STABILIZING_TIMEOUT_NS**: 4s (동일)
- **fixedMinSamples (CUP)**: 동일

### 차이점
| 항목 | 런칭버전 | 현재버전 |
|------|----------|----------|
| **sigma 6m+ 완화** | 없음 | +1.5cm |
| **CUP_SOFT_LOCK** | 없음 | 있음 (threshold+1.5cm 이내 구제) |

### 거리 bias 가능성
- **높음**.  
  1. **sigma 6m+ 완화**: 원래 통과하지 못할 CUP lock이 통과 → **덜 안정적인 pose에서 lock** 가능 → anchor/median이 실제 컵보다 가까운 쪽으로 치우칠 수 있음.  
  2. **CUP_SOFT_LOCK**: sigma가 threshold를 아주 조금 초과한 경우에도 lock 허용 → **품질 기준 완화** → 비슷하게 덜 안정적인 hit에서 lock될 수 있음.  
  → **성공률 향상을 위한 완화가 거리 정확도에 부정적 영향**을 줄 가능성이 큼.

---

## 5. 좌표/보정 계열 차이

### 런칭버전
- **preview ↔ hitTest**: `adjustedHitTestLocalPoint`, `UvAdjust.applyUv` — 동일
- **centerYOffsetRatio**: 0.05f (CUP multi-ray에 적용)
- **LIVE_MAX_FRAME_DELTA_M**: 0.55f — 동일

### 현재버전
- 동일

### 차이점
- **없음**.

### 거리 bias 가능성
- **낮음**.

---

## 6. 가장 의심되는 변경 Top 5 (검증 후 수정)

### 검증 결과 (2025.02)
- CUP 완화 제거 시: **7m 이상에서 CUP lock 거의 불가** (장거리 usability 저하)
- 거리 오차: **0.3~0.4m 고정 오프셋형** (7m→6.7m, 5m→4.6m) — 비례형 아님
- **해석**: CUP 완화는 short bias 주원인이 아님. 장거리 lock 성공률용 장치. bias는 BALL/CUP endpoint 또는 live 경로의 fixed offset 가능성.

### 수정된 우선순위
| 순위 | 변경 | 검증 후 판단 |
|------|------|--------------|
| **1위** | **BALL/CUP endpoint fixed offset** | anchorDistance_m vs finalDistance_m로 구분 후, BALL/CUP hit 위치 systematic bias 우선 점검 |
| **2위** | **live snapshot 경로 fixed offset** | ray-plane 교차, ground plane 모델, ROI 정렬 등 |
| **3위** | **centerYOffsetApplied (0.05)** | 양쪽 동일. 단독 원인 가능성은 낮음 |
| **4위** | **CUP_SIGMA_FAR_RELAX_CM** | short bias 원인 아님. 장거리 usability용 유지 |
| **5위** | **CUP_SOFT_LOCK** | short bias 원인 아님. 장거리 usability용 유지 |

---

## 7. 바로 확인할 로그/검증 포인트 (수정 없이)

| # | 검증 포인트 | 확인 방법 |
|---|-------------|-----------|
| 1 | **anchorDistance_m vs finalDistance_m** | 5m, 10m, 12m 실측 시 CUP_FINAL_RESULT 로그에서 두 값 비교. anchor≈실측, final만 짧으면 live 경로 문제; anchor도 짧으면 BALL/CUP anchor 문제 |
| 2 | **centerFallbackUsed 빈도** | centerFallbackUsed=true인 lock에서 final이 짧은지. true일 때 편향이 크면 center fallback이 원인 후보 |
| 3 | **centerYOffsetApplied** | 항상 true. 로그에서 centerYOffsetApplied와 finalDistance 상관관계 확인 |
| 4 | **CUP_SOFT_LOCK / sigma relax 사용 빈도** | `CUP_LOCK_GATE softLock=true` 로그가 있는 측정에서 final이 짧은지. soft-lock으로 lock된 경우 편향이 크면 원인 확정에 가까움 |
| 5 | **multiRayPlan (MID_5x5 vs FAR_3x3 vs FAR_5x5)** | plan별로 finalDistance 편향이 다른지. FAR_3x3(valid 적음)에서 편향이 크면 원격 구간 sampling 이슈 가능 |

---

## 요약

- **BALL**: 런칭버전과 현재버전 **동일**.
- **CUP**: 샘플링/선택 로직 **동일**. centerYOffset, centerFallback, plan 분기 동일.
- **FINAL 직전**: **동일**.
- **sigma/lock**: 현재버전에 **CUP_SIGMA_FAR_RELAX_CM(6m+)** 와 **CUP_SOFT_LOCK** 추가 → **성공률 향상 대신 거리 정확도 저하** 가능성 높음.
- **우선 검증**: `anchorDistance_m` vs `finalDistance_m` 로그로 live vs anchor 원인 구분 후, soft-lock/sigma relax 사용 빈도와 편향 상관관계 확인.
