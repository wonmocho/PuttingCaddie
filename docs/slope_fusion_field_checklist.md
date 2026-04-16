# Slope Fusion Field Checklist

본 문서는 거리/경사 분리 정책과 축별 slope fusion 정책을 현장에서 검증하기 위한 체크리스트다.

## 목적

- `FULL / DEGRADED / BLOCK` 분류가 정책 의도와 일치하는지 확인
- `distance`와 `slope`가 독립적으로 동작하는지 확인
- `verticalBestSource / lateralBestSource` 선택 건강도 확인
- reject reason 분포 기반으로 후속 튜닝 우선순위 도출

## 핵심 정책 확인 포인트

- 거리/경사 분리:
  - `distanceState=DISTANCE_FIXED` 인데 `mode=BLOCK` 이어도 정상일 수 있다.
  - 실패 판단은 distance와 slope를 분리해서 해석한다.
- 축별 fusion:
  - 상하(Vertical): `SHARED / LOCAL / HV` 중 best 선택
  - 좌우(Lateral): `SHARED / LOCAL` 중 best 선택
  - HV는 vertical 전용 fallback

## 상태별 기대 조건

### FULL

- `SLOPE_DISPLAY_DECISION mode=FULL`
- `verticalQuality=GOOD`
- `lateralQuality=GOOD`
- `upDownAvailable=true`
- `leftRightAvailable=true`
- `finalForwardSource != NONE`

### DEGRADED

- `SLOPE_DISPLAY_DECISION mode=DEGRADED`
- `upDownAvailable=true`
- `leftRightAvailable=false` 이거나 lateral 품질 약화
- `lateralQuality=SOFT/WEAK/BLOCK` 또는 `lateralRejectReason` 존재
- `lateralSuppressedReason=policy_ui_hidden` 가능

### BLOCK

- `SLOPE_DISPLAY_DECISION mode=BLOCK`
- `upDownAvailable=false`
- `verticalQuality=BLOCK`
- `verticalRejectReason` 존재
- distance는 별도 해석 (`distanceState=DISTANCE_FIXED` 가능)

## 필수 로그 키

- `distanceState`
- `mode`
- `verticalBestSource`
- `lateralBestSource`
- `verticalQuality`
- `lateralQuality`
- `verticalRejectReason`
- `lateralRejectReason`
- `finalForwardSource`
- `upDownAvailable`
- `leftRightAvailable`

## 시나리오

### 시나리오 A: 3m 근거리/안정

- 기대: `FULL` 우세
- 추가 확인:
  - `verticalBestSource=HV`
  - `lateralBestSource=BLOCK`
  - `mode=DEGRADED`
- 위 패턴이 나오면, "기존 NONE 구간에서 상하라도 살리는 fallback"이 동작한 것으로 해석

### 시나리오 B: 6m 중거리/경계

- 기대: `DEGRADED` 비율 증가
- 확인 패턴 예시:
  - `verticalBestSource=SHARED/LOCAL`
  - `lateralBestSource=BLOCK`
  - `distanceState=DISTANCE_FIXED`

### 시나리오 C: 9m+ 원거리/저품질

- 기대: `BLOCK` 또는 상하만 유지되는 `DEGRADED`
- 과도한 성공 포장 금지
- `vertical/lateral reject reason`이 품질 저하 원인과 일치해야 함

## Reject Reason 분포 확인 (권장)

단건 로그만 보지 말고 회차 종료 후 reason 빈도를 집계한다.

예시 관찰 키:

- `verticalRejectReason=plane_drift_too_large`
- `lateralRejectReason=shared_no_stable_candidate`
- `lateralRejectReason=policy_ui_hidden`

해석 가이드:

- 특정 reason 쏠림이 크면, 해당 축/소스 임계치가 과도하거나 입력 안정화가 필요
- 3m에서도 BLOCK reason이 과다하면 fallback 또는 noise floor 조건 재검토

## 실행 기록 템플릿

아래 항목을 시나리오별로 기록한다.

- 환경:
  - 날짜:
  - 디바이스:
  - 빌드:
  - 그린/조도:
  - 운영자:
- 시나리오:
  - 거리(3m/6m/9m+):
  - 샷 수:
- 결과 분포:
  - FULL:
  - DEGRADED:
  - BLOCK:
- 핵심 소스 분포:
  - verticalBestSource (SHARED/LOCAL/HV):
  - lateralBestSource (SHARED/LOCAL/BLOCK):
- 핵심 reason Top 3:
  - verticalRejectReason:
  - lateralRejectReason:
- 거리/경사 분리 검증:
  - `distanceState=DISTANCE_FIXED` + `mode=BLOCK` 사례 수:
- 코멘트:
  - 재현 이슈:
  - 다음 튜닝 후보:

## 최종 판정 기준

- 3m에서 FULL 우세, 또는 HV 기반 DEGRADED로 상하 복구
- 6m에서 DEGRADED 증가가 자연스럽고 distance는 안정 유지
- 9m+에서 BLOCK 증가가 자연스럽고 reason이 품질 저하와 일치
- 전체적으로 distance 실패와 slope 실패가 혼동되지 않음
