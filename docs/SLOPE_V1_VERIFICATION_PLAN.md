# Experimental v1 검증 계획

> **목적**: Experimental v1이 Baseline(Plane)보다 실제 경사를 더 잘 반영하는지, 반복 안정성이 있는지 검증한다.

---

## 1. 테스트 시나리오

| # | 시나리오 | 환경 | 목적 |
|---|----------|------|------|
| 1 | **평평한 바닥** | 실내 평평한 면 | 0 근처 출력 여부, 과도한 튐 검증 |
| 2 | **실내 퍼팅장** | 잔디·면적·조명이 그린과 유사한 시설 | 실내 조건에서 Baseline vs Experimental 비교 |
| 3 | **실제 골프장** | 그린 | 실제 사용 환경 검증 |
| 4 | **같은 위치 반복 3회** | 위 중 1곳 이상 | 반복 안정성 검증 |

---

## 2. 필수 기록 항목

매 측정마다 아래 항목을 반드시 기록한다.

### 2.1 경사 값

| 항목 | 설명 |
|------|------|
| 상하경사(Plane) | Baseline forwardPct |
| 상하경사(Experimental) | LocalFit forwardPct |
| 측면경사(Plane) | Baseline lateralPct |
| 측면경사(Experimental) | LocalFit lateralPct |

### 2.2 입력 소스 및 샘플 품질

| 항목 | 설명 |
|------|------|
| ballInputSource | DepthPoint / Point / Plane / Plane(fallback) |
| cupInputSource | 동일 |
| sampleCountBall | 0~9 (3x3 그리드) |
| sampleCountCup | 0~9 |
| fitResidualBall | local fit 잔차 (m) |
| fitResidualCup | 동일 |
| rejectReason | none 또는 사유 |

### 2.3 추적 정보

| 항목 | 설명 |
|------|------|
| ballSampleTimestampMs | BALL_FIX 시점 (ms) |
| cupSampleTimestampMs | CUP_FIX 시점 (ms) |
| ballSampleFrameId | BALL_FIX 시점 식별자 (또는 collectedAtNs) |
| cupSampleFrameId | CUP_FIX 시점 식별자 |

---

## 3. ROI center 대표성 점검

**목적**: BALL_FIX/CUP_FIX 시점의 roi center가 실제 공/컵 중심을 잘 대표하는지 확인.

| 확인 항목 | 방법 | 통과 기준 |
|-----------|------|-----------|
| BALL_FIX 시점 | STABILIZING_START에서 사용자가 화면 중앙에 공을 두고 있는가 | roi center ≈ 공 중심 |
| CUP_FIX 시점 | STABILIZING_END에서 사용자가 화면 중앙에 컵을 두고 있는가 | roi center ≈ 컵 중심 |
| 3x3 그리드 범위 | GRID_STEP_PX(18px)로 공/컵 주변 표면을 충분히 덮는가 | sampleCount ≥ 3 |

**비고**: roi는 보통 화면 중앙 RectF. 사용자가 조준 방식(공→컵 이동)에 따라 roi center가 실제 타깃과 어긋나면 sampleCount가 줄어들 수 있음.

---

## 4. 테스트 결과 기록 템플릿

### 4.1 개별 측정 레코드 (시나리오별 복사 사용)

```
[시나리오: ___________] [날짜: ___] [측정#: ___]

상하경사(Plane)      = ___
상하경사(Experimental)= ___
측면경사(Plane)      = ___
측면경사(Experimental)= ___

ballInputSource      = ___
cupInputSource       = ___
sampleCountBall      = ___
sampleCountCup       = ___
fitResidualBall      = ___
fitResidualCup       = ___
rejectReason         = ___

ballSampleTimestampMs= ___
cupSampleTimestampMs = ___
ballSampleFrameId    = ___
cupSampleFrameId     = ___

ROI center 대표성: □ 양호  □ 보통  □ 부족  (비고: ___)
```

### 4.2 결과 집계표 (시나리오별)

| 측정# | 상하(Plane) | 상하(Exp) | 측면(Plane) | 측면(Exp) | ballSrc | cupSrc | ballN | cupN | reject |
|-------|-------------|-----------|-------------|-----------|---------|--------|-------|------|--------|
| 1     |             |           |             |           |         |        |       |      |        |
| 2     |             |           |             |           |         |        |       |      |        |
| 3     |             |           |             |           |         |        |       |      |        |

---

## 5. 결론 도출 기준

### 5.1 Experimental이 실제 경사를 더 반영하는가

| 평가 | 조건 |
|------|------|
| ○ 우수 | Experimental이 알려진 경사 방향/부호와 일치, Plane은 0 또는 부호 반대 |
| △ 보통 | Experimental이 Plane보다 부호/방향 일치도 높음 |
| × 불충분 | Experimental도 Plane과 유사하게 0 또는 튐 |

### 5.2 반복 안정성

| 평가 | 조건 |
|------|------|
| ○ 우수 | 같은 위치 3회 측정 시 값 변동 < 1% 또는 부호 일관 |
| △ 보통 | 변동 1~3% 내 |
| × 불충분 | 과도한 튐, 부호 불일치 |

---

## 6. 결론 작성란 (테스트 완료 후)

```
[시나리오 1: 평평한 바닥]
- Experimental이 실제 경사를 더 반영하는가: ___
- 반복 안정성: ___
- 비고: ___

[시나리오 2: 실내 퍼팅장]
- Experimental이 실제 경사를 더 반영하는가: ___
- 반복 안정성: ___
- 비고: ___

[시나리오 3: 실제 골프장]
- Experimental이 실제 경사를 더 반영하는가: ___
- 반복 안정성: ___
- 비고: ___

[시나리오 4: 같은 위치 반복 3회]
- Experimental이 실제 경사를 더 반영하는가: ___
- 반복 안정성: ___
- 비고: ___

[종합 결론]
- Plane 대비 Experimental 채택 권고 여부: ___
- 추가 개선 필요 사항: ___
```

---

## 7. 디버그 데이터 수집 방법

앱 내 Slope Debug 패널(측정data → 그래픽 결과 → 분석 상세)에서 위 필수 항목이 표시됨.

- **Baseline**: [Baseline] 섹션
- **Experimental**: [Experimental] 섹션 (ballInputSource, cupInputSource, sampleCount, fitResidual, timestamp, frameId 포함)

테스트 시 해당 화면을 스크린샷 또는 텍스트 복사로 보존하면 된다.
