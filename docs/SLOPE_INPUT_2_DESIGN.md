# Slope Input 2.0 설계 문서

> **목적**: 경사 입력부 재설계. 거리 엔진 동결, 기존 경사 계산식 유지, Baseline + Experimental 병렬 구조.

---

## 1. 확정 사항

| 항목 | 내용 |
|------|------|
| 거리 엔진 | **수정 금지** |
| BALL/CUP fix 흐름 | **수정 금지** |
| final distance 계산 | **수정 금지** |
| 기존 경사 계산식 | forwardPct, lateralPct 공식 **유지** |
| Plane 기반 경사 | **baseline**으로 유지, 비교용 |
| LocalSurfaceFit | **experimental** 신규 입력 경로 |
| 병렬 비교 | Baseline vs Experimental 동시 표시 |
| 채택 결정 | 현장 반복 테스트 후 |

---

## 2. 추가 설계 원칙

### 2.1 LocalSurfaceFit 1차 목표: 재현성

> 절대값 정밀도보다 **부호/방향/반복 안정성** 확보.

- 평평한 곳 → 0 근처
- 오르막/내리막 → 부호 일관
- 좌우 방향 → 일관
- 같은 자리 반복 → 덜 흔들림

### 2.2 샘플 소스 우선순위

> "가능한 최상 입력"이 아니라 **"안정적으로 얻을 수 있는 입력"**.

- Depth가 이론상 좋아도 기기/환경에서 불안정하면 1차에서는 Point·Plane fallback 혼합이 실용적일 수 있음.

### 2.3 병렬 비교 UI: source·quality 필수

결과만 보여주지 말고 반드시 함께 표시:

- **source** (DEPTH/POINT/PLANE)
- **sampleCountBall**, **sampleCountCup**
- **fitResidualBall**, **fitResidualCup**
- **rejectReason**

---

## 3. 아키텍처

```
Distance Core (동결)
   └─ 기존 BALL/CUP 거리 측정 유지

Slope Engine
   ├─ SlopeInputProvider (인터페이스)
   ├─ PlaneBaselineInputProvider (기존 → provider 형태)
   ├─ LocalSurfaceFitInputProvider (신규)
   ├─ SlopeComparator
   └─ SlopeDebugPanel (병렬 표시)
```

---

## 4. SlopeInputProvider 인터페이스

```kotlin
interface SlopeInputProvider {
    val sourceId: String  // "PLANE_BASED" | "LOCAL_SURFACE_FIT"
    fun collect(
        ballPos: FloatArray,
        cupPos: FloatArray,
        frame: Frame,
        ballScreenCenter: PointF?,   // null = use projection
        cupScreenCenter: PointF?,
        roiScreen: RectF
    ): SlopeInputResult
}
```

### SlopeInputResult

```kotlin
data class SlopeInputResult(
    val ballNormal: FloatArray?,
    val cupNormal: FloatArray?,
    val refNormal: FloatArray?,
    val forwardPct: Float?,
    val lateralPct: Float?,
    val quality: String,           // "valid" | "rejected"
    val rejectReason: String?,
    val sourceId: String,
    // LocalSurfaceFit 전용
    val sampleCountBall: Int = 0,
    val sampleCountCup: Int = 0,
    val validSampleRatio: Float = 0f,
    val fitResidualBall: Float? = null,
    val fitResidualCup: Float? = null
)
```

---

## 5. PlaneBaselineInputProvider

- 기존 Plane/normal/deltaY 경로를 그대로 유지
- groundPlaneModel, cupPlaneNormal 사용
- SlopeComputer.compute() 호출
- sourceId = "PLANE_BASED"

---

## 6. LocalSurfaceFitInputProvider (1차 버전)

### 6.1 역할

- 볼 주변·컵 주변 다점 샘플 수집
- local plane fitting
- ballLocalNormal, cupLocalNormal 산출
- refNormal = (ballNormal + cupNormal) 정규화
- SlopeComputer와 동일 공식으로 forwardPct, lateralPct 계산

### 6.2 샘플 수집

- ball/cup 월드포인트 → 화면에 투영
- 투영 주변 3x3 또는 5-point cross로 hitTest
- hitTest 결과: Point, DepthPoint, Plane 모두 수용
- 각 hit의 hitPose.tx/ty/tz를 월드포인트로 수집

### 6.3 Local plane fitting

- 최소 3점 이상일 때만 fitting
- 방법: centroid + PCA 또는 3점 cross product
- normal 방향: world up과 내적 > 0이 되도록 보정

### 6.4 품질 판정

- sampleCountBall < 3 또는 sampleCountCup < 3 → reject
- fitResidual 임계치 초과 → reject
- ball/cup normal 차이(각도) 과대 → reject

---

## 7. SlopeComparator

- BaselineResult + ExperimentalResult 수집
- SlopeDebugPanel에 전달

---

## 8. SlopeDebugPanel (formatSlopeDebugText 확장)

```
[Baseline]
상하경사(Plane) 0.0%
측면경사(Plane) 0.0%
source = PLANE_BASED

[Experimental]
상하경사(LocalFit) -1.7%
측면경사(LocalFit) 좌고우저 0.8%
source = POINT
sampleCountBall = 9
sampleCountCup = 8
fitResidualBall = 0.002
fitResidualCup = 0.003
rejectReason = none
```

---

## 9. 구현 순서

| Phase | 내용 |
|-------|------|
| 2-1 | SlopeInputProvider 인터페이스, SlopeInputResult 정의 |
| 2-2 | PlaneBaselineInputProvider 구현 (기존 로직 래핑) |
| 2-3 | LocalSurfaceFitInputProvider 1차 (hitTest 기반 샘플, 단순 plane fit) |
| 2-4 | SlopeComparator, 병렬 UI 추가 |
| 2-5 | 현장 반복 테스트 |
| 2-6 | 승자 채택 |

---

## 10. 금지 사항

- 거리 엔진 수정
- BALL/CUP fix 흐름 수정
- final distance 계산 수정
- 기존 Plane 경사 삭제
- Pro 잠금/과금 UI 작업 (현재 단계)

---

## 11. 관련 문서

| 문서 | 역할 |
|------|------|
| `SLOPE_2ND_GEN_PLAN.md` | 2세대 계획 |
| `SLOPE_INDOOR_PUTTING_FACILITY_ANALYSIS.md` | 실내 퍼팅장 분석 |
| `LATERAL_SLOPE_DESIGN.md` | 경사 수학 정의 |
