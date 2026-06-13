# 측면경사(Lateral Slope) 설계 문서

> **목적**: 팀 공유용. 측면경사는 "데이터 유무"가 아니라 "올바른 정의로 계산 가능할 때" 구현한다.
>
> **현재 단계**: 과금/잠금 UI보다 경사 성능 검증이 우선. internal/debug/test mode에서는 경사 관련 모든 데이터와 진단값을 전부 표시한다.

---

## 1. 핵심 원칙

| 원칙 | 내용 |
|------|------|
| **측면경사 ≠ 평면차이** | `ballCupPlaneAngleDeg`는 두 평면의 3D 각도 차이. 좌우경사(브레이크)가 아님. 품질지표/평면차이로만 사용. |
| **정의 기반 계산** | 진짜 측면경사는 **기준 평면 normal**을 퍼팅 진행방향과 좌우방향으로 **분해**해서 계산해야 함. |
| **구현 시점** | 데이터가 생겼을 때가 아니라, 정의가 고정되고 그 정의대로 **안정적으로 계산될 때** 구현. |

---

## 2. 구현 조건 (4가지)

측면경사를 구현하려면 아래 4가지를 갖춰야 함.

### 2-1. 기준 평면 normal 확보
- BALL·CUP 주변 평면 normal 둘 다 유효해야 함
- 둘 차이가 크면(plane drift 큼) → 그린이 하나의 면으로 설명되지 않음 → 측면경사 출력 대신 "평면 차이 큼" 처리
- `ballCupPlaneAngleDeg`는 이 품질 판정값으로 사용

### 2-2. 퍼팅 진행 방향 벡터 확정
- ball → cup 방향이 월드 좌표에서 안정적으로 잡혀야 함
- BALL/CUP fix가 흔들리지 않아야 함
- ARCore hit pose로 교차 위치를 얻을 수 있음

### 2-3. 좌우축 명확 정의
- 진행방향 d, 월드 up u, 기준 평면 normal n일 때
- 좌우축: `l = normalize(cross(u, d))` 또는 부호 반대
- forward grade(상하경사)와 lateral grade(측면경사)를 같은 수학으로 계산 가능해야 함

### 2-4. 출력 신뢰도 기준
- **내부 테스트**: plane drift < 8°
- **프로덕션 공개**: plane drift < 5°, 반복 측정 시 측면경사 편차 충분히 작을 때

---

## 3. 지금 바로 구현 가능 vs 미루기

### ✅ 지금 바로 구현 가능한 시점
- XYZ 모드에서만 우선 구현
- BALL/CUP 고정 안정
- 기준 normal 확보
- plane drift가 낮음
- **테스트 화면에서만 노출** (debug/internal test mode)

### ❌ 사용자 공개하면 안 되는 시점
- XZ 모드 (vertical/normal 정보 빈약)
- `ballCupPlaneAngleDeg`만 가지고 좌우경사라고 부르는 상태
- 같은 퍼팅 라인에서 값이 들쭉날쭉함
- plane drift가 큰데도 수치를 계속 보여주는 상태

---

## 4. 수학적 정의

### 입력
- `u`: 월드 up 단위벡터
- `n`: 기준 평면 normal 단위벡터
- `pBall`, `pCup`: BALL·CUP 월드 좌표

### 중간 벡터
```
d = pCup - pBall
forward = normalize(reject(d, u))   // 퍼팅 진행방향의 수평 성분
left = normalize(cross(u, forward)) // 진행방향 기준 왼쪽 방향
```

### 경사 계산
```
forwardPct = -(dot(n, forward) / dot(n, u)) * 100   // 상하경사 %
lateralPct  = -(dot(n, left) / dot(n, u)) * 100     // 측면경사 %
```

### 해석
- `forwardPct > 0` → 오르막
- `forwardPct < 0` → 내리막
- `lateralPct < 0` → 좌고우저 가능성
- `lateralPct > 0` → 우고좌저 가능성

> **주의**: 좌우 부호는 좌표계에 따라 반대가 될 수 있음. 현장 테스트 1회로 부호만 최종 확정.

### 기준 normal 선택
- `ballNormal`, `cupNormal` 각각 normalize
- `planeDriftDeg = angle(ballNormal, cupNormal)`
- drift가 작으면: `refNormal = normalize(ballNormal + cupNormal)` (평균 사용)
- drift가 threshold 초과 시: **계산하지 않음**, 측면경사 "—"

---

## 5. 구현 조건 체크리스트

측면경사는 아래 **모두** 만족할 때만 계산:

| 조건 | 설명 |
|------|------|
| XYZ 모드 | `isXyzMode == true` |
| BALL·CUP plane normal 둘 다 유효 | `ballNormalRaw != null && cupNormalRaw != null` |
| tracking good | `trackingGood == true` |
| plane drift ≤ threshold | 내부테스트: 8°, 공개: 5° |
| ball→cup 수평거리 충분 | `minHorizontalDistanceM` (예: 0.3m) 이상 |

조건 미달 시:
- 측면경사 → "—"
- 디버그에는 평면차이 X.X°만 표시

---

## 6. 포맷 예시

### 상하경사
```
상하경사 +2.3%
상하경사 -1.5%
```

### 측면경사 (구현 시)
```
측면경사 좌고우저 1.1%
측면경사 우고좌저 0.8%
측면경사 0.0%
```

### 디버그 표시 (테스트 단계)
```
상하경사 +2.3%
측면경사 좌고우저 1.1%
평면차이 2.8°
```

→ 경사값, 좌우 방향, 품질지표를 동시에 보여서 현장 검증 용이

---

## 7. 구현 순서

1. `ballCupPlaneAngleDeg`는 그대로 유지 → 평면차이/품질지표
2. 위 공식을 **debug/internal test mode에서만** 먼저 적용
3. 현장 테스트로 검증:
   - 같은 위치 반복 측정 시 측면경사 값이 과도하게 흔들리지 않는가
   - 실제 좌고우저/우고좌저 방향과 부호가 맞는가
   - planeDrift가 클 때 "—" 처리되는가
4. 부호가 반대면 `left = normalize(cross(forward, u))`로 교체
5. 신뢰도 확보 후에만 일반 사용자 공개

---

## 8. Cursor/AI 실행 지시문

```
측면경사는 ballCupPlaneAngleDeg를 직접 쓰지 말고,
기준 평면 normal(refNormal)을 퍼팅 진행방향(forward)과 좌우방향(left)으로 분해해서 계산하라.

구현식:
  forwardPct = -(dot(refNormal, forward) / dot(refNormal, worldUp)) * 100
  lateralPct = -(dot(refNormal, left) / dot(refNormal, worldUp)) * 100

여기서
  forward = normalize(reject(cupPos - ballPos, worldUp))
  left = normalize(cross(worldUp, forward))

ballCupPlaneAngleDeg는 계속 '평면차이' 품질지표로만 유지한다.
plane drift가 threshold보다 크면 측면경사는 출력하지 말고 '—' 처리한다.
우선 XYZ 모드 + debug/internal test mode에서만 활성화하고,
현장 테스트로 좌우 부호 방향을 1회 검증해 고정하라.
거리 측정 로직은 절대 수정하지 말라.
```

---

## 9. XZ/XYZ 모드와 경사 계산

### 원인
- `blockedReason = xyz_mode_required` → 현재 **XZ 모드**라서 경사 계산이 실행되지 않음.
- XZ 모드: 수평 거리만 (dy 무시). `horizontalVerticalMeters` = null.
- XYZ 모드: 3D 거리 + hv(h,v) 계산 → 상하경사/측면경사 가능.

### 해결 (구현됨)
- **debug 빌드** 또는 **Pro 테스트 모드**에서 `axisMode`를 **XYZ로 강제**.
- `isAxisXzSelected()`: test mode일 때 false 반환 → XYZ 사용.
- 이제 같은 실내 바닥 측정에서도 mode=XYZ, 경사 계산 경로 실행됨.

### 확인 방법
- 결과data 페이지에서 `mode = XYZ` 확인.
- `blockedReason = none`, `quality = valid` 또는 hv 값 확인.

---

## 10. 실내 경사판 테스트 실패 원인 (ARCore 한계)

### 관찰
- 경사판 위를 측정해도 상하경사/측면경사 0%, ballNormal/cupNormal 모두 [0,1,0].

### 원인 (코드 점검 완료)
- **ARCore는 수평/수직 평면만 인식**. 기울어진 면(경사판)은 별도 Plane으로 생성되지 않음.
- hitTest는 기존 Plane 중 교차하는 것을 반환 → 경사판을 조준해도 **바닥 수평면**이 선택됨.
- BALL·CUP 모두 같은 수평 Plane에 hit → `samePlane=true`, `v=0`, normal=[0,1,0].

### 테스트 가이드
- **흰 민무늬 경사판**: 특징점 부족으로 ARCore가 별도 평면으로 인식하기 어려움.
- **권장**: 경사판 표면에 테이프/패턴/마커를 붙여 특징점을 늘린 뒤 재테스트.
- **1차 목표**: 경사값이 아니라 `ballNormal`, `cupNormal`이 [0,1,0]에서 벗어나는지, `v`가 0이 아닌지 확인.
- **실제 검증**: 골프장 그린에서 테스트가 최종 검증.

### 디버그 표시 (추가됨)
- `samePlane`: BALL과 CUP이 동일 Trackable(Plane)에 붙었는지.
- `ballPlaneType`, `cupPlaneType`: 각 hit의 Plane 타입.
- `deltaYRaw`: cupPos.y - ballPos.y (v와 동일).

---

## 11. 거리 표시값 vs h (horizontal) 차이

### 관찰
- **distance(화면)**: 2.97m
- **h**: 2.64m
- **v**: 0.00m

v≈0인 평평한 바닥에서는 이론상 `distance ≈ sqrt(h² + v²) = h` 이어야 하나, 실제로는 다를 수 있음.

### 원인: 서로 다른 파이프라인

| 값 | Source | 정의 |
|----|--------|------|
| **distance** | `finalDistanceMeters` = `endLiveSnapshotMeters` | **LIVE 파이프라인**: 카메라 화면 중심 → ray-plane 교차(또는 hitTest) → startAnchor~교차점 거리. 스무딩·프레임클램프·점프가드 적용. |
| **h** | `horizontalVerticalMeters` 또는 SlopeComputer | **anchor 파이프라인**: startAnchor.pose ↔ endAnchor.pose. `h = sqrt(dx² + dz²)`. 평면 normal과 무관. |

### 의미
- **LIVE** 끝점: 화면 중심 ray가 바닥과 만나는 점 (또는 hitTest 결과). CUP fix 직전/직후의 “조준점”.
- **Anchor** 끝점: CUP fix 시 multi-ray grid에서 선택된 best hit. 앵커가 실제로 붙은 3D 위치.

두 점이 다르면 distance와 h가 달라짐. 의도적으로 다른 정의이며, 거리 엔진은 변경 금지.

### 디버그 패널
- `[거리 소스]`에 distance, anchorDist, 설명 추가.
- `anchorDist` ≈ h (XYZ 모드, v≈0일 때). `distance`와 `anchorDist` 차이가 크면 LIVE vs anchor 끝점 불일치.

---

## 12. 한 줄 요약

> 측면경사는 "평면차이 값이 있을 때"가 아니라, **기준 평면 normal을 퍼팅 진행축과 좌우축으로 분해할 수 있고**, 그 출력이 **반복 측정에서 충분히 안정적일 때** 구현한다.

> **XZ vs XYZ**: debug/test 모드에서는 XYZ 강제 → 경사 계산 경로 확실히 실행.

> **distance vs h**: 거리(화면) = LIVE 파이프라인, h = anchor 기반. 서로 다른 끝점이므로 차이 가능. 거리 엔진 변경 금지.

> **실내 경사판**: ARCore가 기울어진 면을 별도 Plane으로 인식하지 못함. samePlane, planeType으로 확인. 골프장 검증 필요.

---

*문서 작성: 2026-02*  
*관련: [PRO_MODE_INTEGRATION_PLAN.md](./PRO_MODE_INTEGRATION_PLAN.md)*
