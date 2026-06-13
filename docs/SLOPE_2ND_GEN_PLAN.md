# PuttingCaddy 경사 2세대 개발 계획서

> **목적**: Cursor 설계 반영용. 거리 측정은 완성 축으로 유지하고, 경사는 입력부 재설계 중심의 2세대 개발로 분리한다.

---

## 1. 이해 요약

### 1.1 핵심 판단

| 항목 | 판단 |
|------|------|
| **거리** | 이미 제품 수준. BALL/CUP 흐름, LIVE, 결과 표시 완성. 계속 유지·개선만 진행. |
| **경사** | 실제 경사값이 의미 있게 검증된 적 없음. 거의 항상 0 또는 — |
| **원인** | 계산식이 아니라 **입력 파이프라인**. normal·deltaY가 계속 수평화되어 들어옴 |

### 1.2 지금까지 확인된 현상

- 실내 바닥, 경사판, 아스팔트 경사로, **실내 퍼팅장**에서 **반복적으로**:
  - 상하경사 = 0 또는 —
  - 측면경사 = 0 또는 —
  - 평면차이 ≈ 0
  - normal ≈ [0, 1, 0]
  - deltaY ≈ 0
- **간헐적 튐**: 같은 위치에서 가끔 큰 값(예: 10cm) 표시 후 재측정 시 다시 0
  - 실제 경사라기보다 cameraY/medianY 등 Y 입력 이상치로 추정
- **결론**: 경사 입력이 평탄화되는 구조. ARCore가 수평/수직 평면만 인식하고, 경사면을 별도 Plane으로 추적하지 않음. 유사 환경(실내 퍼팅장)에서도 동일 → 환경 한계가 아니라 **입력 구조 문제** 가능성 높음.

→ 상세: `SLOPE_INDOOR_PUTTING_FACILITY_ANALYSIS.md`

### 1.3 무엇을 바꿀 것인가

- **버리지 않음**: BALL/CUP 흐름, 월드포인트, 결과 화면, 디버그 패널, 계산 공식 틀, test mode
- **다시 설계**: slope input source, normal 추정, deltaY 산출, local surface slope 추출, Plane 전용 의존

### 1.4 최우선 원칙

1. **거리 엔진은 절대 수정하지 않는다**
2. 현재 Plane 기반 slope는 **baseline 비교용**으로만 유지
3. 경사는 **입력부 재설계**가 핵심
4. debug/test mode에서는 경사 관련 모든 데이터를 **전부 표시**
5. 현재 단계는 Pro 잠금보다 **slope debug UI**가 우선

---

## 2. 설계 반영 계획 (Phase별)

### Phase 0. 동결·선언 (즉시)

| 작업 | 내용 | 산출물 |
|------|------|--------|
| 문서화 | `docs/` 에 본 계획서 및 원칙 정리 | `SLOPE_2ND_GEN_PLAN.md` (본 문서) |
| 코드 주석 | 거리 엔진 변경 금지 주석 강화 | `V31StateMachine`, distance 관련 |
| Cursor 규칙 | `.cursor/rules/` 또는 프로젝트 규칙에 "거리 수정 금지, 경사는 slope path만" 반영 | RULE.md |

---

### Phase 1. 디버그 진단 강화 (우선 구현)

**목표**: internal/debug/test mode에서 slope 진단에 필요한 모든 값을 표시

#### 현재 표시 항목 (확인됨)

- mode, tracking, distance, anchorDist
- h, v, forwardPct, lateralPct, planeDriftDeg
- blockedReason, quality
- samePlane, ballPlaneType, cupPlaneType
- deltaYRaw, ballNormal, cupNormal, refNormal, ballPos, cupPos, forward, left, worldUp

#### 추가 필요 항목 (계획서 요구)

| 항목 | 설명 | 현재 상태 |
|------|------|-----------|
| `slopeInputSource` | PLANE_BASED / POINT / DEPTH / FALLBACK 등 | **미구현** |
| `ballTrackableType` | BALL hit의 Trackable 타입 (Plane/Point 등) | **미구현** (ballGroundPlaneType은 있음) |
| `cupTrackableType` | CUP hit의 Trackable 타입 | **미구현** (cupPlaneType은 있음) |

#### 구현 계획

1. **V31StateMachine.UiModel**에 추가:
   - `slopeInputSource: String?` (예: `"PLANE_BASED"`)
   - `ballTrackableType: String?`
   - `cupTrackableType: String?`

2. **SlopeComputer 호출부**에서 slope source 명시:
   - 현재는 Plane normal 기반 → `slopeInputSource = "PLANE_BASED"` 고정

3. **formatSlopeDebugText**에 출력 추가:
   - slopeInputSource
   - ballTrackableType, cupTrackableType

4. **거리 영향**: 없음 (UiModel 확장 + 디버그 텍스트만)

---

### Phase 2. Baseline 경로 정리 (Phase 1 직후)

| 작업 | 내용 |
|------|------|
| 명칭 고정 | 현재 Plane 기반 slope → `source = PLANE_BASED` |
| 문서화 | `SLOPE_INPUT_PIPELINE_AUDIT.md`에 "언제 0이 나오는지" 정리 |
| 디버그 표시 | 결과/디버그 패널에 `slopeInputSource = PLANE_BASED` 명시 |

---

### Phase 3. Experimental Input 설계 (설계 단계)

| 후보 | 내용 | ARCore 활용 |
|------|------|-------------|
| **A. Point 기반** | hitTest PointCloud 결과, estimated surface normal | PointCloud.getPoints() |
| **B. Depth 기반** | DepthPoint, 깊이맵 기반 샘플 | Session.acquireDepthImage() |
| **C. Local multi-point fit** | 볼/컵/진행 경로 주변 다점 → 국소 평면 피팅 | RANSAC 등 |

**목표**: “실제 경사를 반영하는 입력”이 어떤 방식인지 실험

---

### Phase 4. 병렬 비교 출력 (Experimental 구현 후)

| 작업 | 내용 |
|------|------|
| UI 확장 | `상하경사(Plane)`, `상하경사(Experimental)` 병렬 표시 |
| 데이터 구조 | `SlopeDebugInfo` 또는 별도 필드로 `forwardPct_plane`, `forwardPct_experimental` |

---

### Phase 5. 골프장 현장 검증

- 같은 지점 반복 측정
- 오르막/내리막, 좌고우저/우고좌저 구간
- 아침/오후 조도 차이

---

### Phase 6. 승자 채택

- 반복 안정성·방향성이 가장 좋은 입력 경로를 최종 slope source로 채택

---

## 3. 파일별 영향 예상

| 파일 | Phase 1 | Phase 2 | Phase 3+ |
|------|---------|---------|----------|
| `V31StateMachine.kt` | UiModel 필드 추가, slopeInputSource 설정 | — | Experimental path 호출 |
| `SlopeComputer.kt` | — | — | Experimental 입력용 함수 |
| `SlopeDebugInfo.kt` | — | — | source 필드, experimental 결과 |
| `DistanceMeasurementActivity.kt` | formatSlopeDebugText 확장 | source 표시 | 병렬 비교 표시 |
| `ResultDataActivity.kt` | — | — | (formatSlopeDebugText 결과 사용) |
| 거리 관련 | **변경 없음** | **변경 없음** | **변경 없음** |

---

## 4. Cursor 실행 지시문 (Phase 1용)

```
작업: Slope 2세대 Phase 1 - 디버그 진단 강화

원칙:
- 거리 엔진은 절대 수정하지 않는다.

구현:
1. V31StateMachine.UiModel에 추가:
   - slopeInputSource: String?  (기본 "PLANE_BASED")
   - ballTrackableType: String?
   - cupTrackableType: String?

2. BALL fix 시 ballTrackableType = trackable의 클래스명 또는 "PLANE"
   CUP fix 시 cupTrackableType = trackable의 클래스명 또는 "PLANE"

3. SlopeComputer 호출 직전/직후 slopeInputSource = "PLANE_BASED" 설정

4. formatSlopeDebugText에 출력 추가:
   - slopeInputSource = ...
   - ballTrackableType = ...
   - cupTrackableType = ...

5. 거리 관련 코드는 변경하지 말 것.
```

---

## 5. 금지사항 체크리스트

- [ ] 거리 엔진 복제/이동/리팩터링
- [ ] slope 실험으로 거리 기능 영향
- [ ] slope 0일 때 계산식만 계속 수정
- [ ] test mode에서 값 숨기기
- [ ] Plane 기반 결과를 최종 정답으로 가정

---

## 6. 실내 퍼팅장 테스트 결과 (2026)

- **환경**: 실내 퍼팅 전용 시설 (잔디·면적·조명이 실제 그린과 유사)
- **패턴**: 대부분 0, 간헐적으로 큰 값(예: 10cm) 표시 후 같은 위치 재측정 시 다시 0
- **해석**: Plane 기반 입력 구조 한계. "실외 가면 해결" 가설 약화. 환경보다 **입력 구조 문제** 가능성 높음.
- **간헐적 튐**: cameraY/medianY 등 Y 입력 이상치 → sanity check·표시 정책 보수화 권장.

→ 상세: `SLOPE_INDOOR_PUTTING_FACILITY_ANALYSIS.md`

### 6.1 추가 권장 작업 (선택)

| 작업 | 내용 |
|------|------|
| Y 입력 sanity check | cup cameraY/medianY/anchor y가 거리 대비 비정상적으로 크면 경사 reject |
| 경사 표시 정책 보수화 | 반복 재현되지 않는 큰 값은 사용자 표시 제한, sanity 통과 시에만 표시 |
| 디버그 강화 | displayedSlopeSource, displayedDeltaY, anchorYBall, anchorYCup, cameraY, medianY, rejectReasonIfOutlier |

---

## 7. 관련 문서

| 문서 | 역할 |
|------|------|
| `SLOPE_INDOOR_PUTTING_FACILITY_ANALYSIS.md` | 실내 퍼팅장 테스트 분석 |
| `SLOPE_INPUT_PIPELINE_AUDIT.md` | 입력 파이프라인 점검 결과 |
| `SLOPE_PHASE1_LOG_DESIGN.md` | Phase 1 로그 설계 |
| `LATERAL_SLOPE_DESIGN.md` | 경사 수학 정의, 측면경사 설계 |
| `PRO_MODE_INTEGRATION_PLAN.md` | Pro 통합, 과금, 화면 흐름 |

---

## 8. 한 줄 정리

> 거리 측정은 완성 축으로 유지하고, 경사는 **입력부 재설계** 중심의 2세대 개발로 분리한다. 실내 퍼팅장 테스트에서도 동일 패턴 → Plane 기반 slope는 baseline/debug only로 격하하고, experimental 입력 설계로 이동한다.
