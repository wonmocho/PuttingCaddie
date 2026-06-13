# 경사 인식 파이프라인 점검 보고서

## 1. 원인 후보

### 1) ARCore SDK 한계 (가장 유력)
- **ARCore는 수평(HORIZONTAL)과 수직(VERTICAL) 평면만 인식**합니다.
- [google-ar/arcore-android-sdk#477](https://github.com/google-ar/arcore-android-sdk/issues/477)  
  인용: "Currently ARCore only recognizes horizontal and vertical surfaces."
- 경사판(5~15° 기울기)처럼 기울어진 면은  
  **별도 Plane으로 생성되지 않습니다**.
- hitTest는 기존에 감지된 Plane 중 ray와 교차하는 것을 반환 →  
  경사판 위를 조준해도 **바닥 수평면**이 선택됨.

### 2) BALL/CUP 모두 같은 평면에 스냅
- 경사판 표면에 특징점이 부족하면(흰 매트 등)  
  ARCore가 해당 영역을 별도 Plane으로 추적하지 못함.
- BALL과 CUP hit 모두 바닥의 큰 수평 Plane으로 hit →  
  `ballPlaneAtFix === cupPlane` → samePlane = true.
- 두 앵커 모두 같은 수평면 위에 생성 →  
  `ballPos.y ≈ cupPos.y` → `v = dy = 0`.

### 3) 코드 상 평탄화(버그) 가능성
- 점검 결과: **y, normal을 0 또는 수평으로 강제하는 코드는 없음**.
- `horizontalVerticalMeters` = `Pair(sqrt(dx²+dz²), dy)` 그대로 사용.
- `ballNormal`, `cupNormal`은 plane.centerPose.getTransformedAxis(1)에서 추출,  
  fallback 없음.
- SlopeComputer에서 worldUp 강제 대체 로직 없음.

---

## 2. 확인된 사실

| 항목 | 확인 내용 |
|------|-----------|
| ballPos, cupPos | startAnchor.pose, endAnchor.pose에서 직접 추출.<br>중간 평탄화 없음. |
| v (dy) | `b.ty() - a.ty()` 그대로 사용.<br>XZ로 바꾸는 경로 없음. |
| ballNormal | groundPlaneModel.normal (BALL fix 시 plane.centerPose Y축). |
| cupNormal | cupPlane.centerPose Y축 (CUP fix 시). |
| hv 계산 | RESULT 시 `a=startAnchor, b=endAnchor`,<br>`h=sqrt(dx²+dz²)`, `v=dy`. |
| XYZ 강제 | debug/test 모드에서 axisMode=XYZ,<br>hv 계산 경로 정상 진입. |
| hitTest 정책 | BALL: useFarthest, CUP: selectBestPlaneHit(preferUpwardFacing).<br>polygon 내부 우선. |
| Plane 타입 | HORIZONTAL_UPWARD_FACING, HORIZONTAL_DOWNWARD_FACING, VERTICAL만 존재.<br>경사면 타입 없음. |

---

## 3. 문제 지점 (코드 vs 환경)

| 구간 | 코드 문제 여부 | 비고 |
|------|----------------|------|
| BALL/CUP 월드포인트 | ❌ 없음 | anchor pose 그대로 사용 |
| horizontalVerticalMeters | ❌ 없음 | dx, dy, dz 정확히 계산 |
| Normal 추출 | ❌ 없음 | plane.centerPose.getTransformedAxis(1) |
| Slope 계산식 | ❌ 없음 | forward/lateral 분해, refNormal 등 정상 |
| Plane 인식 | ⚠️ **ARCore 한계** | 경사면을 별도 Plane으로 생성하지 않음 |
| hitTest 선택 | ❌ 없음 | 수평면 우선이 아니라,<br>**경사면 Plane 자체가 없음** |

→ **실제 원인: 테스트 환경 + ARCore SDK 한계**. 코드 버그 아님.

---

## 4. 실제 수정이 필요한지

- **입력 평면 인식**: ARCore 자체 한계이므로 앱 코드로 해결 불가.
- **대안**: Point Cloud + RANSAC 등 자체 평면 피팅  
  (대규모 작업, 거리 엔진 영향 가능성 있음).
- **실용적 방안**: 실제 골프장 그린에서 테스트.  
  그린 경사는 대체로 수평면으로 근사되나, 넓은 그린의 완만한 경사는 ARCore가  
  하나의 "기울어진 큰 수평면"으로 인식할 수 있는지, 또는 서로 다른 평면 구역으로  
  나뉘는지 확인 필요.

---

## 5. 수정안 (최소)

### 이미 적용
- `ballCupSamePlane`: BALL과 CUP가 같은 Trackable(Plane)에 붙었는지 표시.
- `cupPlaneType`: CUP hit의 Plane 타입 (HORIZONTAL_UPWARD_FACING 등).
- `deltaYRaw`: cupPos.y - ballPos.y (v와 동일, 진단용).
- 디버그 패널에 samePlane, ballPlaneType, cupPlaneType, deltaYRaw 추가.

### 테스트 가이드 보강 (LATERAL_SLOPE_DESIGN.md)
- 흰색 민무늬 경사판은 특징점 부족으로  
  ARCore가 별도 평면으로 인식하기 어려울 수 있음.
- 경사판 표면에 테이프/패턴/마커를 붙여 특징점을 늘린 뒤 재테스트 권장.

---

## 6. 거리 기능 영향

- **없음**. V31StateMachine의 거리 계산, LIVE 파이프라인, anchor 생성 로직은  
  변경하지 않음.
- 추가된 항목: ballPlaneAtFix 저장, ballCupSamePlane, cupPlaneType 계산 및  
  UiModel/디버그 표시만.

---

## 7. 요약

| 질문 | 답변 |
|------|------|
| BALL/CUP가 정말 경사판에 붙었는가? | **아니오**. 같은 수평면(바닥)에 붙은 것으로 보임.<br>samePlane=true 시 확인 가능. |
| y와 normal이 중간에서 수평으로 덮어써지는가? | **아니오**. 코드 상 그런 로직 없음. |
| 0값이 계산 결과인가, 입력 상실인가? | **입력이 처음부터 수평**으로 들어옴.<br>ARCore가 경사판을 별도 Plane으로 인식하지 못함. |

**한 줄**: 실내 경사판 테스트에서 경사가 0으로 나오는 것은  
**ARCore가 기울어진 면을 별도 Plane으로 인식하지 못하기 때문**이며,  
앱의 경사 계산 로직은 정상 동작 중이다.
