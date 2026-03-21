# 거리 systematic short bias 분석

> 고정 오프셋으로 단정하지 않고, 비율/일관된 짧은 편향(systematic short bias) 가능성으로 표현

---

## 1. 거리 확정 기준

| 항목 | 내용 |
|------|------|
| **최종 거리 SSOT** | **live snapshot** (anchor-to-anchor 아님) |
| **finalDistance_m** | `endLiveSnapshotMeters` 또는 `lastDisplayDistanceMeters` fallback |
| **endLiveSnapshotMeters** | CUP lock 시점의 `liveSmoothedMeters` 또는 `farModeLiveMedianAtEndLock` |
| **liveSmoothedMeters** | `distanceFromStartToPoseMeters(startAnchor, intersectionOrHitPose)` |

**결론**: 표시 거리는 **anchor 기반이 아니라 live ray/hitTest 기반**이다.

---

## 2. 추가된 로그

### BALL_FIX_DIAGNOSTICS (BALL lock 시)
- `fixHitPose_x/y/z`
- `distanceFromCamera_m`
- `fixSource` (PLANE/DEPTH/POINT/FREEZE)
- `validHits`
- `useFarthest=true` (BALL은 gridCount=9에서 항상 farthest)

### CUP_FIX_DIAGNOSTICS (CUP lock 시)
- `fixHitPose_x/y/z`
- `distanceFromCamera_m`
- `fixSource` (multiRayPlan)
- `centerFallbackUsed`
- `validHits`
- `useFarthest=false` (CUP은 gridCount=25, median 사용)
- `anchorDistance_m` (anchor-to-anchor 거리)

### CUP_FINAL_RESULT
- `anchorDistance_m` (anchor-to-anchor)
- `finalDistance_m` (실제 표시값 = live snapshot)

---

## 3. 고정 오프셋 가능 원인 (코드 기준)

### A. BALL endpoint 오프셋
| 원인 | 설명 |
|------|------|
| **useFarthest** | BALL은 farthest 사용 → 카메라에 가까운 hit은 제외. 반대로 **가까운 쪽이 선택되면** 짧게 나옴 |
| **plane hit 위치** | hitTest가 plane 표면을 칠 때, **볼 앞쪽(카메라 쪽) 경계**를 맞추면 0.2~0.5m 짧을 수 있음 |
| **FREEZE 사용** | ballFreezeUsed 시 이전 hit 유지. freeze 시점 hit가 이미 안쪽이면 오프셋 유지 |

### B. CUP endpoint 오프셋
| 원인 | 설명 |
|------|------|
| **Ray-plane 교차** | ground plane(BALL fix)과 카메라→ROI ray 교차. **경사/평면 오차**로 교차점이 컵보다 안쪽에 올 수 있음 |
| **HitTest fallback** | `preferFarthestForDistance=true`지만, **단일 ray**라 컵 가장자리보다 안쪽을 맞출 수 있음 |
| **Multi-ray median** | CUP은 median 사용. valid hit이 **컵보다 가까운 점에 치우치면** median이 안쪽으로 이동 |
| **centerFallbackUsed** | valid hit 부족 시 center 1점만 사용 → **컵 중심이 아닌 ROI 중심**에 가까운 점 선택 가능 |

### C. Live 경로 자체 오프셋
| 원인 | 설명 |
|------|------|
| **Ground plane 모델** | BALL fix plane의 `pointOnPlane`이 BALL hit 위치. plane normal이 `plane.centerPose` 기준 → **볼 위치와 plane 중심 불일치** 가능 |
| **ROI 정렬 오차** | 사용자가 컵을 맞추었다고 해도 ROI center가 **실제 컵 중심보다 안쪽**일 수 있음 (시선/손떨림) |
| **LIVE_MAX_FRAME_DELTA_M** | 프레임당 변화 제한으로 **갑작스런 보정**이 억제될 수 있음 (보조 요인) |

### D. CUP이 BALL만큼 안쪽으로 잡힐 수 있는지
**가능함.** 이유:
1. **Ray-plane**: plane이 BALL 기준이라, ray가 컵 방향으로 나가도 **경사/곡률**에 따라 교차점이 실제 컵보다 가까울 수 있음
2. **Multi-ray median**: 5x5 그리드에서 **가까운 hit에 편향**되면 median이 안쪽으로 이동
3. **centerFallback**: valid hit 붕괴 시 center 1점 → **실제 컵 위치와 다를 수 있음**

---

## 4. anchorDistance_m vs finalDistance_m 비교

로그에서 다음을 확인:
- `anchorDistance_m` ≈ 실측에 가깝고 `finalDistance_m`이 짧다 → **live 경로(ray-plane/hitTest) 쪽 오프셋**
- `anchorDistance_m`도 짧다 → **BALL 또는 CUP anchor 자체 오프셋**
- `anchorDistance_m`은 괜찮은데 `finalDistance_m`만 짧다 → **live snapshot 계산 경로 문제**

---

## 5. 한 줄 요약

> systematic short bias는 scaling보다 **BALL/CUP endpoint 또는 live ray 경로의 systematic bias** 가능성이 크다.  
> `anchorDistance_m`과 `finalDistance_m`을 함께 보면 anchor vs live 중 어디서 짧아지는지 구분할 수 있다.

---

## 5-1. CUP 완화 검증 결과 및 원칙 (로그 검토)

### 추가 관찰
- CUP 완화(CUP_SIGMA_FAR_RELAX_CM, CUP_SOFT_LOCK) 제거 시 **7m 이상에서 거의 CUP 확정 불가**
- 거리 오차는 **7m→6.7m, 5m→4.6m**처럼 비례형이 아니라 **대략 0.3~0.4m 고정 오프셋형**에 가까움

### 해석
1. **CUP 완화는 short bias의 주원인이 아님** — 장거리 lock 성공률을 위한 장치
2. **short bias는 별도로 존재** — BALL/CUP endpoint 또는 final live snapshot 경로의 **fixed offset** 가능성이 큼

### 원칙 (정리)
| 원칙 | 내용 |
|------|------|
| **CUP 완화** | 장거리 usability를 위해 유지 (CUP_SIGMA_FAR_RELAX_CM, CUP_SOFT_LOCK 재활성화) |
| **거리 bias 분석** | lock 정책과 분리해서 분석 |
| **anchor vs final** | `anchorDistance_m` vs `finalDistance_m`를 **같은 측정에서 반드시 비교** |
| **우선 점검** | **BALL/CUP endpoint의 fixed offset 가능성**을 우선 점검 |

---

## 6. 런칭버전 vs 현재버전 distance SSOT 코드 비교 (anchor-to-anchor 복원 보고)

### 런칭버전 distance SSOT

| 항목 | 내용 |
|------|------|
| **최종값 소스** | `endLiveSnapshotMeters` (live snapshot) |
| **fallback** | `lastDisplayDistanceMeters` |
| **설정 위치** | `confirmLock()` END_LOCKED (706–709행), `tick()` END_LOCKED→RESULT (739–741행) |
| **endLiveSnapshotMeters 산출** | live stability gate 통과 시 `farModeLiveMedianAtEndLock` 또는 `liveSmoothedMeters`; 실패 시 `lastDisplayDistanceMeters` |
| **liveSmoothedMeters** | `distanceFromStartToPoseMeters(startAnchor, ray-plane 교차점 또는 hitTest hitPose)` |
| **distanceBetweenAnchorsMeters()** | 존재(2077행)하나 **finalDistance 계산에 미사용** |

**코드 결론**: 런칭버전도 **live snapshot**을 SSOT로 사용. anchor-to-anchor는 최종 거리에 사용되지 않음.

---

### 현재버전 distance SSOT

| 항목 | 내용 |
|------|------|
| **최종값 소스** | `endLiveSnapshotMeters` (live snapshot) |
| **fallback** | `lastDisplayDistanceMeters` |
| **설정 위치** | `confirmLock()` END_LOCKED (733–736행), `tick()` END_LOCKED→RESULT (784–787행) |
| **endLiveSnapshotMeters 산출** | live stability gate 통과 시 `farModeLiveMedianAtEndLock` 또는 `liveSmoothedMeters`; 실패 시 `lastDisplayDistanceMeters` |
| **liveSmoothedMeters** | `distanceFromStartToPoseMeters(startAnchor, ray-plane 교차점 또는 hitTest hitPose)` |
| **distanceBetweenAnchorsMeters()** | 존재(2367행)하나 **finalDistance 계산에 미사용** (로그용 `anchorDistance_m`만) |

**코드 결론**: 현재버전도 **live snapshot**을 SSOT로 사용. 런칭버전과 동일한 구조.

---

### 차이점

| 구분 | 런칭버전 | 현재버전 |
|------|----------|----------|
| **finalDistance SSOT** | live snapshot | live snapshot (동일) |
| **fallback chain** | endLiveSnapshot → lastDisplay | endLiveSnapshot → lastDisplay (동일) |
| **anchor 사용** | finalDistance에 미사용 | finalDistance에 미사용 (동일) |
| **추가 로직** | - | CUP_FINAL_RESULT, CUP_ZERO_DISTANCE_GUARD 등 진단 로그 |

**요약**: 제공된 런칭버전 코드와 현재버전 모두 **live snapshot**을 SSOT로 사용.  
`launch_version_extracted` 기준으로는 anchor-to-anchor를 SSOT로 쓰던 시점이 코드 상에는 없음.

---

### anchor-to-anchor 복원 위치 (사용자 요청 기준)

런칭버전이 anchor-to-anchor였고 정확도가 더 좋았다는 전제라면, **코드 상으로는 그 시점이 없더라도** anchor-to-anchor를 SSOT로 복원하는 것이 목표.

| 수정 위치 | 파일 | 함수/구간 |
|-----------|------|-----------|
| **1** | `V31StateMachine.kt` | `confirmLock()` 내 `State.END_LOCKED` → `UiEvent.FinishPressed` 처리 (약 731–756행) |
| **2** | `V31StateMachine.kt` | `tick()` 내 `State.END_LOCKED` → `State.RESULT` 전환 (약 782–809행) |

**변경 내용**:
- `finalDistanceMeters` = `distanceBetweenAnchorsMeters()` (anchor-to-anchor)  
- `distanceBetweenAnchorsMeters()`가 유효하지 않을 때 (0 또는 invalid) → `endLiveSnapshotMeters` fallback

---

### 수정 파일/함수

| 파일 | 수정 대상 |
|------|-----------|
| `V31StateMachine.kt` | `confirmLock()` END_LOCKED 분기 (finalDistanceMeters 할당) |
| `V31StateMachine.kt` | `tick()` END_LOCKED→RESULT 분기 (finalDistanceMeters 할당) |

---

### 수정 후 실측 검증 결과

| 실측 거리 | 예상 검증 항목 |
|-----------|----------------|
| **5m** | finalDistance_m ≈ 5m, anchorDistance_m ≈ 5m |
| **10m** | finalDistance_m ≈ 10m, anchorDistance_m ≈ 10m |
| **12m** | finalDistance_m ≈ 12m, anchorDistance_m ≈ 12m |

복원 후 `finalDistance_m` = `anchorDistance_m` 기준으로 동작해야 함.
