# 경사 테스트 분석: 실내 퍼팅장 (2026)

> **테스트 환경**: 실내 골프 퍼팅 전용 시설. 잔디 질감·넓은 바닥면·조명이 실제 그린과 유사한 환경.

---

## 1. 결론 요약

| 질문 | 판단 |
|------|------|
| 경사가 왜 0으로 고정되는가? | **입력 파이프라인**. Plane 기반 입력이 계속 수평 plane으로 들어옴 |
| "실외 골프장 가면 해결될 것" 가설 | **약해짐**. 유사 환경(실내 퍼팅장)에서도 동일 패턴 |
| 간헐적 "상하경사 10cm" 등 큰 값 | **실제 경사 아님**. Y 입력 이상치로 추정 |
| 같은 위치 재측정 시 다시 0 | **비재현성**. 실제 경사라면 방향·크기 재현되어야 함 |

**한 줄**: 현재 Plane 기반 slope 입력은 실제 필드/퍼팅장 환경에서도 경사를 반영하지 못한다. 환경 한계가 아니라 **입력 구조 문제** 가능성이 높다.

---

## 2. 반복 패턴 (성공 측정 공통)

```
ball.groundPlane.normalY = 1.000
ball.groundPlane.planeType = HORIZONTAL_UPWARD_FACING
result.liveSource = PLANE_INTERSECTION
result.cupPlaneType = HORIZONTAL_UPWARD_FACING
result.ballCupPlaneAngleDeg = 0.00
result.ballCupSamePlane = false
```

- 볼·컵 모두 **수평 upward-facing plane**으로 들어옴
- `ballCupSamePlane = false`이면서 `ballCupPlaneAngleDeg = 0`  
  → 같은 plane은 아니지만 서로 **평행한 수평 plane 조각**들
- 경사면 정보가 아닌, 평평한 면 정보만 들어오는 구조

---

## 3. 간헐적 큰 값 현상

### 관찰
- 같은 위치에서 대부분 0
- 가끔 "상하경사 10cm" 등 큰 값 표시
- 같은 위치 재측정 시 다시 0

### 해석
- **실제 경사라기보다 입력 Y/기준면 이상치**로 추정
- 로그에서 `cameraY`, `medianY`가 비정상적으로 큰 경우 존재
  - 예: 컵 거리 3~4m인데 cameraY/medianY가 8~9m
  - 물리적으로 자연스럽지 않음
- Y 입력이나 기준면 참조가 순간적으로 튀는 것으로 보임

### 결론
- 경사 파트는 **"안정적으로 0"도, "안정적으로 실제 경사"도 아님**
- 입력 파이프라인이 불안정: 대부분 평평하게 0, 드물게 튐

---

## 4. 환경 해석

| 테스트 장소 | 환경 특성 | 결과 |
|-------------|-----------|------|
| 실내 경사판/책 | 특징점 부족, 실험실 | 0 패턴 |
| 아스팔트 경사로 | 실외 | 0 패턴 |
| **실내 퍼팅장** | 잔디, 넓은 면, 실제 유사 | 0 패턴 + 간헐적 튐 |

"실내라서 안 됐다"는 해석은 **실내 퍼팅장**에서는 적용 어렵다.  
→ **환경 문제**보다 **입력 구조 문제** 쪽에 무게를 두는 게 타당.

---

## 5. 판단 및 권장 방향

### 확정 판단
1. Plane 기반 slope → **baseline/debug only**로 격하
2. 거리 엔진 → **유지** (변경 금지)
3. 경사 2세대 → **Point / Depth / local multi-point fit** 등 experimental 입력 설계로 이동

### 추가 점검 권장
1. **상하경사 표시값 소스 추적**
   - deltaYRaw, hv.v, cameraY, medianY, anchor pose y 중 어떤 값이 UI에 반영되는지 확인
2. **Y 입력 sanity check**
   - cup cameraY/medianY/anchor y가 거리 대비 비정상적으로 크면 경사 계산 제외 또는 reject
3. **경사 표시 정책 보수화**
   - 반복 재현되지 않는 큰 경사값은 사용자 노출 자제
   - sanity check 통과 시에만 표시

### 디버그 패널 추가 권장
- displayedSlopeSource, displayedDeltaY
- anchorYBall, anchorYCup
- cameraY, medianY
- rejectReasonIfOutlier

---

## 6. 관련 문서

| 문서 | 역할 |
|------|------|
| `SLOPE_2ND_GEN_PLAN.md` | 2세대 개발 계획 |
| `SLOPE_INPUT_PIPELINE_AUDIT.md` | 입력 파이프라인 점검 |
| `SLOPE_PHASE1_LOG_DESIGN.md` | Phase 1 로그 설계 |
