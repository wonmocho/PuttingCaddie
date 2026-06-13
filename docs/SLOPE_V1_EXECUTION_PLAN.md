# PuttingCaddy 경사 2세대 Experimental v1 — 설계 이해 및 실행 방안

> **목적**: GPT 설계안에 대한 이해도를 정리하고, 팀 확인 후 실행할 작업 목록을 명확히 한다.  
> **실행 시점**: 팀 확인 이후.

---

## 0. 설계 핵심 (수정 반영)

| 항목 | 결정 |
|------|------|
| **Ball slope 샘플** | BALL_FIX 시점에 병행 수집. RESULT 시점 재투영은 주 전략으로 채택 금지. |
| **Cup slope 샘플** | CUP_FIX 시점에 병행 수집. |
| **RESULT** | 저장된 Ball/Cup 샘플 조합 → local fit + slope 계산. |
| **projectWorldToViewPoint** | fallback/debug용으로만 유지. |
| **추적 필드** | Ball/Cup slope 샘플 저장 시 timestampMs, frameId 함께 저장 (확정). |

---

## 1. 설계안 이해 요약

### 1.1 핵심 메시지

| 항목 | 이해 내용 |
|------|-----------|
| **변경 범위** | 경사 **입력부**만 재설계. 계산식·거리 엔진은 수정 금지. |
| **핵심 질문** | "경사를 어떻게 계산할까?" → **"실제 경사를 반영하는 3D 입력을 어떻게 얻을까?"** |
| **현재 experimental** | v0 수준. Cup만 3×3, Ball은 Plane fallback, hit 우선순위 없음. |
| **v1 목표** | Ball·Cup 양쪽 모두 local surface fit 입력 확보. 진짜 experimental 비교 가능 상태로. |

### 1.2 절대 건드리지 않을 것

- 거리 엔진
- BALL/CUP fix 흐름
- final distance 계산
- LIVE 스냅샷 거리 파이프라인
- 기존 Plane 기반 slope (baseline 유지, 삭제 금지)

### 1.3 Ball/Cup 측정 시점 설계 원칙

> **잘못된 설계**: Ball 측정 후 사용자가 다시 Ball로 돌아가서 경사 샘플 수집.  
> **올바른 설계**: Ball 측정 1회에 거리 fix + 경사용 Ball 주변 샘플 수집, Cup 측정 1회에 동일하게 수행.  
> 즉, **추가 동선 없이** 기존 측정 흐름에 slope 샘플을 겹쳐 수집.

### 1.4 Ball slope 샘플 수집 시점 — 설계 핵심 (수정됨)

> **⚠️ 채택하지 말 것**: Ball slope 샘플을 END_LOCKED/RESULT 시점의 **현재 프레임**에서 `projectWorldToViewPoint`로 재투영해 수집하는 방식.

**이유**: 사용자는 Ball 찍고 Cup 쪽으로 이동. RESULT 시점 프레임은 대개 Cup을 향함. 이때 Ball 월드 좌표를 현재 프레임에 투영하면:
- 화면 밖일 수 있음
- 가려져 있을 수 있음
- 보이더라도 Ball 주변 실제 표면을 hitTest로 안정적으로 얻기 어려움

**✅ 채택할 설계**:

| 시점 | 동작 | 비고 |
|------|------|------|
| **BALL_FIX** (STABILIZING_START → START_LOCKED) | 기존 거리 fix 동시에 Ball 주변 slope 샘플 수집 → **저장** | 카메라가 Ball 향함, hitTest 가능 |
| **CUP_FIX** (STABILIZING_END → END_LOCKED) | 기존 거리 fix 동시에 Cup 주변 slope 샘플 수집 → **저장** | 카메라가 Cup 향함, hitTest 가능 |
| **RESULT** | 저장된 Ball/Cup slope 샘플로 local fit + slope 계산 | 재투영·재샘플링 없음 |

`projectWorldToViewPoint`는 **fallback/debug용**으로만 유지. Ball local fit의 **주 수집 방식**으로 사용하지 않음.

### 1.5 현재 코드와의 관계

| 설계 항목 | 현재 코드 상태 |
|-----------|----------------|
| Baseline (Plane) | `PlaneBaselineInputProvider` — 유지 |
| Experimental | `LocalSurfaceFitInputProvider` — v0 (Cup만 샘플, Ball fallback) |
| 수집 시점 | END_LOCKED/RESULT에서 `collect()` 호출 — **v1에서 BALL_FIX/CUP_FIX 시점으로 변경** |
| Ball/Cup screen center | BALL_FIX/CUP_FIX 시점의 roi 또는 hit 기준 사용 |

---

## 2. Experimental v1 요구사항 정리

| # | 요구사항 | 현재 상태 |
|---|----------|-----------|
| 1 | Ball도 3×3(또는 5-point) 다점 샘플링 | Ball 샘플 없음 (sampleCountBall=0) |
| 2 | Cup 3×3 샘플링 유지 | 구현됨 |
| 3 | hit 선택 우선순위: DepthPoint > Point > Plane | 없음 (minBy distance만 사용) |
| 4 | ballInputSource, cupInputSource 분리 표시 | 없음 |
| 5 | BALL_FIX/CUP_FIX 시점에 각각 Ball/Cup slope 샘플 수집·저장 | RESULT 시점 단일 수집 → 시점별 수집으로 변경 |
| 6 | Baseline vs Experimental 병렬 디버그 표시 | 일부 구현됨 |

---

## 3. 실행 방안 (작업 단위)

### Phase A: V31HitSampler 확장

| 작업 | 내용 | 파일 |
|------|------|------|
| A-1 | `hitTestWithTypePriority(frame, screenX, screenY): HitResult?` 추가 | V31HitSampler.kt |
| | DepthPoint > Point > Plane, 동일 타입 내에서는 distance 가까운 쪽 | |
| A-2 | `projectWorldToViewPoint(frame, worldX, worldY, worldZ): PointF?` 추가 | V31HitSampler.kt |
| | fallback/debug용. Ball local fit 주 수집 방식으로 사용하지 않음 | |

### Phase B: LocalSurfaceFitInputProvider v1 업그레이드

**원칙**: "RESULT 프레임에서 재샘플링"이 아니라 **"시점별 저장 구조"를 받을 수 있게** 확장.

**추적 필드 (저장 구조에 포함)**:
- `ballSampleTimestampMs`, `cupSampleTimestampMs`
- `ballSampleFrameId`, `cupSampleFrameId` (또는 sequence number)
- 가능하면 `trackingStateAtSample` (Ball/Cup 각각)

| 작업 | 내용 | 파일 |
|------|------|------|
| B-1 | `hitTestClosestAtScreenPoint` → `hitTestWithTypePriority`로 교체 | LocalSurfaceFitInputProvider.kt |
| B-2 | **Ball raw sample 수신 API**: `collectBallSamples(frame, roiScreen, ballScreenCenter)` 또는 저장 구조 | LocalSurfaceFitInputProvider.kt |
| B-3 | **Cup raw sample 수신 API**: `collectCupSamples(frame, roiScreen, cupScreenCenter)` 또는 저장 구조 | LocalSurfaceFitInputProvider.kt |
| B-4 | **computeFromStoredSamples(ballPoints, cupPoints, …)**: 저장된 Ball/Cup 샘플로 local fit + slope 산출 | LocalSurfaceFitInputProvider.kt |
| B-5 | ballInputSource, cupInputSource 산출 (타입 비율 또는 대표 타입) | LocalSurfaceFitInputProvider.kt |
| B-6 | Ball/Cup 샘플 부족 시 Plane fallback 허용 | LocalSurfaceFitInputProvider.kt |

### Phase C: V31StateMachine — BALL/CUP 시점 저장 훅 추가

**원칙**: `projectWorldToViewPoint` 기반 RESULT 시점 재투영을 **주 방식으로 두지 않음**.  
대신 BALL_FIX/CUP_FIX 완료 시점에 slope 샘플 수집 훅 추가.

| 작업 | 내용 | 파일 |
|------|------|------|
| C-1 | **BALL_FIX 시점 훅**: `confirmLock()` STABILIZING_START 분기에서 Ball slope 샘플 수집·저장 | V31StateMachine.kt |
| | 시점: 카메라가 Ball 향함. `tickFrame`, `tickRoiScreen`, hit 기준 ball screen center 사용 | |
| C-2 | **CUP_FIX 시점 훅**: `confirmLock()` STABILIZING_END 분기에서 Cup slope 샘플 수집·저장 | V31StateMachine.kt |
| | 시점: 카메라가 Cup 향함. roi 또는 hit 기준 cup screen center 사용 | |
| C-3 | **RESULT 시점**: 저장된 Ball/Cup 샘플로 `computeFromStoredSamples` 호출, slope 산출 | V31StateMachine.kt |
| C-4 | `projectWorldToViewPoint`는 fallback/debug용으로만 활용 | V31StateMachine.kt |
| | 기존 거리 로직·state machine 동작은 변경 없음 | |

### Phase D: SlopeInputResult 확장

| 작업 | 내용 | 파일 |
|------|------|------|
| D-1 | `ballInputSource: String?`, `cupInputSource: String?` 추가 | SlopeInputResult.kt |
| D-2 | 추적 필드: `ballSampleTimestampMs`, `cupSampleTimestampMs`, `ballSampleFrameId`, `cupSampleFrameId` (선택) | SlopeInputResult.kt |
| | (sampleCountBall, sampleCountCup, fitResidualBall, fitResidualCup, rejectReason은 이미 존재) | |

### Phase E: 디버그 패널 확장

| 작업 | 내용 | 파일 |
|------|------|------|
| E-1 | `formatSlopeDebugText`에 ballInputSource, cupInputSource 표시 | DistanceMeasurementActivity.kt |
| E-2 | [Experimental] 섹션에 Baseline vs Experimental 병렬 구조 유지 | (이미 구현됨, 정리만) |

---

## 4. 실행 순서 제안

```
A (V31HitSampler) → D (SlopeInputResult) → B (LocalSurfaceFit 시점별 저장 구조) → C (BALL/CUP 훅) → E (디버그)
```

- **A**: 기반 API 추가 (hitTestWithTypePriority, projectWorldToViewPoint fallback용)
- **D**: 결과 구조 확장 (ballInputSource, cupInputSource 등)
- **B**: LocalSurfaceFitInputProvider가 시점별 Ball/Cup raw sample을 받아 compute할 수 있게 확장
- **C**: V31StateMachine에 BALL_FIX/CUP_FIX 시점 훅 추가 (단순 재투영 전달이 아님)
- **E**: 확인용 디버그 출력

---

## 5. 팀 확인 포인트

| # | 확인 항목 | 비고 |
|---|-----------|------|
| 1 | hit 우선순위 적용 범위 | "debug/test mode에서만"인지, 아니면 항상 적용할지 |
| 2 | v0 호환 | v1 적용 후 기존 v0 결과와의 비교 로그가 필요한지 |

### 5.1 추적 필드 (확정)

Ball/Cup slope 샘플 저장 시 **timestamp 및 가능하면 frame id**를 함께 저장한다.

| 목적 | |
|------|------|
| Ball/Cup 샘플 수집 시점 추적 | |
| RESULT 계산에 사용된 샘플의 시점 검증 | |
| 디버그 로그/JSON 기록과 대조 가능 | |
| Ball/Cup 샘플이 서로 다른 프레임 조건에서 모였을 때 해석 | |

**구현 시 권장 필드**: `ballSampleTimestampMs`, `cupSampleTimestampMs`, `ballSampleFrameId`, `cupSampleFrameId`, `trackingStateAtSample` (선택)

---

## 6. Cursor 실행 문구 (팀 확인 후)

```
문서 방향은 대체로 맞다. 다만 Ball slope 샘플을 END_LOCKED/RESULT 시점의
현재 프레임에서 projectWorldToViewPoint로 재투영해 수집하는 것을 주 전략으로 채택하면 안 된다.

수정 원칙:
1. Ball slope 샘플은 BALL_FIX 완료 시점에 병행 수집
2. Cup slope 샘플은 CUP_FIX 완료 시점에 병행 수집
3. RESULT 시점에는 저장된 Ball/Cup slope 샘플을 조합해 local fit 및 slope 계산
4. projectWorldToViewPoint는 fallback/debug용으로만 유지

사용자는 Ball 1회, Cup 1회만 측정하고, 각 시점에서 slope용 샘플도 함께 저장하는 구조로 바꿔라.

- 거리 엔진·BALL/CUP fix 흐름·final distance: 변경 금지
- hit 선택 우선순위: DepthPoint > Point > Plane
- SlopeInputResult: ballInputSource, cupInputSource 추가
- 디버그 패널: Baseline vs Experimental 병렬 표시
- -Pro, CTA, 잠금 카드: 지금 단계 금지

docs/SLOPE_V1_EXECUTION_PLAN.md의 Phase A→D→B→C→E 순서로 Experimental v1 구현하라.
```

---

## 7. 한 줄 요약

**Ball slope 샘플은 RESULT 재투영이 아니라 BALL_FIX 시점 병행 수집. Cup은 CUP_FIX 시점. RESULT에서 저장된 샘플 조합. Phase A→D→B→C→E 순으로 구현.**

---

## 8. P3 — Shared plane 기반 Hybrid (팀 합의·SSOT)

> **목적**: Local-only experimental의 한계(작은 패치·micro-plane·drift)와 구분하여, **흔들리지 않는 기준 경사**를 먼저 두고 Local은 **보정**으로만 쓰는 구조를 문서로 고정한다.  
> **구현 시점**: P0/P1/P2(관측·weighted) 검증 후, 팀이 아래 **2가지**를 확정한 뒤 착수.

### 8.1 반드시 확정해야 할 2가지

| # | 항목 | 내용 |
|---|------|------|
| **1** | **Shared 정의** | 아래 8.2 한 줄을 팀에서 **그대로 채택**할지 확정. 흐리면 로그 해석이 다시 꼬인다. |
| **2** | **phase1 vs Shared** | **옵션 A(권장)**: 제품 경사 = Shared 기반, phase1 = fallback / sanity·비교 기준. **옵션 B(비권장)**: 제품=phase1, shared=experimental만 → 경로·UX 혼선 가능. |

### 8.2 Shared plane / Shared slope 정의 (고정안)

- **Shared plane**: `worldUp` 기준으로 정의된 **단일** 기준면(합쳐 fit한 한 장의 법선).
- **Shared slope**: 그 법선의 기울기를 **퍼팅 라인 방향**, 즉 **ball → cup 월드 벡터**에 투영해 forward/lateral 성분으로 변환한 값.  
- **금지**: “normal만 있으면 slope”처럼 **투영 축 없이** 퍼센트로만 변환하는 애매한 정의(방향 기준 불명 → lateral 튐·sanity 반복).

**계산 통일**: normal, ball→cup, slope **모두 world space**에서 수행한다 (camera/local 혼합 금지).

**코드 레벨 강제 (기준 축)**  
Shared slope 계산 시 forward/lateral 분해는 반드시 **ball→cup 월드 벡터**를 기준 축으로 사용한다. **카메라 좌표**, **ROI·스크린 기준 축**, **화면 기준 축**은 Shared 분해에 **절대 사용하지 않는다**. (실무에서 camera/screen 축을 쓰는 실수가 lateral 튐·sanity 반복의 상당 부분을 만든다.)

### 8.3 Hybrid 구조 (요약)

| 역할 | 설명 |
|------|------|
| **기본값** | Shared 기반 `sharedForwardPct` / `sharedLateralPct` |
| **Local** | experimental local 경로는 **제거하지 않음**. 조건 충족 시에만 shared에 **제한된 보정** (예: forward/lateral 각 **±2% clamp**). |
| **조건 예시** (문서상 참고) | `driftCanonicalDeg`·std·`normalStabilityStatus`·sanity 등 — 구현 시 수치는 별도 합의. |
| **출력 모드** | `SHARED_ONLY` / `HYBRID_CORRECTED` / `BLOCKED` 등으로 **로그·디버그에 명시**. |

**1차 입력 범위**: **ball 패치 + cup 패치** 점군만으로 shared fit (중간(mid) 패치는 2차 확장). spread·residual 게이트는 기존 철학 유지.

### 8.4 구현 시 로그 필수 필드 (권장)

**Shared / 품질**

- `sharedPlaneNormal`, `sharedPlaneFitResidual_m`, `sharedPlaneSampleCount`
- `sharedForwardPct`, `sharedLateralPct`

**Hybrid·비교 (필수 권장)**

- `sharedVsLocalForwardDelta`, `sharedVsLocalLateralDelta` — local 보정이 **의미 있는지 vs 노이즈인지** 판단용.
- `localCorrectionApplied`, `finalForwardPct`, `finalLateralPct`, `slopeOutputMode`

### 8.5 Shared 구현에서 자주 실패하는 3가지 (예방)

| 실패 | 증상 | 대응 |
|------|------|------|
| **1. 방향 정의 없음** | forward/lateral 튐, sanity 반복 | **기준 축 = ball→cup**, forward=라인 방향 성분, lateral=수직 성분 (8.2 고정). |
| **2. 좌표계 혼합** | drift는 줄었는데 lateral 이상, `delta_lateral_vs_phase1` 반복 | **world만** 사용. |
| **3. 입력이 과도하게 noisy** | shared std·residual 악화 | 1차는 **ball+cup만**; outlier·큰 spread 제외; mid 패치는 나중. |

### 8.6 첫 로그 30초 판정 (실전)

1. **`slopeOutputMode`**: `BLOCKED` 다수 → shared 입력/정의 점검. `SHARED_ONLY`·`HYBRID_CORRECTED` 비율 확인.  
2. **`sharedForwardPct` / `sharedLateralPct`**: 같은 조건 반복 시 **값이 덜 튀는지**.  
3. **`sharedVsLocal*Delta`**: 작으면(예: ±2% 근처) local이 보정 역할, 크면 local이 별도 스토리 → 정의/좌표 재점검.  
4. **`sanityRejectReason`**: 대비 **감소** 여부.

**성공의 본질**: “계산이 되느냐”가 아니라 **값이 안 흔들리느냐**로 본다.

### 8.7 P3 1차에서 하지 말 것

- distance 엔진·BALL/CUP fix·YOLO·제품 UI 문구 대개편·임계값 미세튜닝(구조 검증 우선).

### 8.8 구현 전 마지막 체크 3개

구현 착수 직전에 아래를 **전부** 만족하는지 확인한다.

| # | 항목 | 내용 |
|---|------|------|
| **1** | **좌표계** | normal / ball→cup / slope 계산 = **전부 world**. |
| **2** | **Shared 입력 (1차)** | **ball + cup 패치만**. mid 패치는 **넣지 않는다** (2차 확장). |
| **3** | **Local correction** | 조건을 통과하지 못하면 보정을 **무조건 버린다** (`SHARED_ONLY` 또는 `BLOCKED`). 타협·부분 적용 금지. |

### 8.9 한 줄 (P3)

P3는 경사를 더 정밀히 만드는 단계가 아니라, Shared로 *안정적인 기준*을 먼저 만들고 Local은 *조건부 보정*으로만 쓰는 단계이다.

### 8.10 P3 권장 구현 순서 (실전)

1. **Shared plane fit만** 먼저 구현한다 (local correction은 이후).
2. **`slopeOutputMode = SHARED_ONLY`** 위주로 로그를 확보한다.
3. **안정성**(std·drift·sanity)이 문서 기준에 맞는지 확인한 뒤 **local correction**을 붙인다.

**이 단계에서 하지 말 것**: local correction 동시 도입, mid 샘플, threshold 임의 조정, “조금 더 정확하게”를 위한 범위 확장. 목표는 **안정성 검증**이다.

### 8.11 함수 단위 최소 구조 (코드 SSOT)

리포지토리 기준 배치(이름은 구현과 일치시킨다).

| 단계 | 역할 | 코드 위치(현재) |
|------|------|-----------------|
| **입력** | Ball/Cup에서 수집한 **월드 점** 리스트만 합친다 (mid 제외). | `V31StateMachine.kt` — `END_LOCKED` / `RESULT`에서 `experimentalBallSlopeSamples?.points + experimentalCupSlopeSamples?.points` |
| **Shared fit** | `SharedPlaneFit.fitFromWorldPoints(worldPoints)` → `normalWorld`, `residualMeanM`, `pointCount` | `SharedPlaneFit.kt` |
| **Shared slope** | 위 법선 + `ballPos`/`cupPos`(월드) → forward/lateral % | `SlopeComputer.computeSharedOnly(...)` |
| **출력** | `UiModel.experimentalSharedSlope`, `sharedPlaneFitResidualM`, `sharedPlaneSampleCount` (phase1·experimental **덮어쓰기 없음**) | `V31StateMachine` → 로그 JSON `slopeSharedP3`, 디버그 텍스트 `[P3 Shared]` |
| **금지(Step 1)** | hybrid·local 보정·튜닝 | 파이프라인에 넣지 않음 |

**데이터 흐름 (한 줄)**  
`world points (ball∪cup) → SharedPlaneFit → shared normal → SlopeComputer.computeSharedOnly → SlopeDebugInfo`.

### 8.12 첫 로그 판정 템플릿 (복붙·약 10초)

아래를 로그 한 건(또는 스프레드시트 한 행)에 붙여 넣고 채운다.

```
[측정 ID] ___________  [날짜] ___________

1) slopeOutputMode (또는 동등 필드): ___________
   → SHARED_ONLY 위주면 진행 OK / BLOCKED 다수면 입력·정의 점검

2) sharedForwardPct / sharedLateralPct (또는 computeSharedOnly 결과 %)
   동일 조건 재측정 시 튐 정도: [ ] 작음(OK)  [ ] 큼(축·좌표 의심)

3) sanityRejectReason / blockedReason
   이전 대비: [ ] 감소  [ ] 동일  [ ] 악화

4) (선행) plane residual / pointCount
   residual: ___ m   count: ___

판정 (한 줄)
[ ] 성공: 값 안정 + sanity 개선 또는 유지
[ ] 좌표/축 의심: lateral만 튐
[ ] 실패: BLOCKED·invalid 다수 → shared 입력 또는 §8.2 재확인
```

---
