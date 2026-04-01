# Slope Phase 1 로그 설계

> **목적**: Phase 1 테스트 시 경사 입력 파이프라인 진단을 위한 로그 태그 및 형식 정의.

---

## 1. 로그 태그

| 태그 | 용도 |
|------|------|
| `SLOPE_PHASE1` | Phase 1 slope 입력 진단 전용 (adb logcat 필터: `SLOPE_PHASE1`) |

---

## 2. 로그 시점 및 형식

### 2.1 BALL_FIX

**시점**: BALL 고정 확정 시 (confirmLock, STABILIZING_START → START_LOCKED)

**형식**:
```
SLOPE_PHASE1: BALL_FIX slopeInput=PLANE trackableType=<Plane|Unknown|null> planeType=<HORIZONTAL_UPWARD_FACING|...> normalY=<float>
```
또는 (plane이 null일 때):
```
SLOPE_PHASE1: BALL_FIX slopeInput=NONE trackableType=<...> planeType=NONE (plane cast failed)
```

**필드**:
- `slopeInput`: PLANE (plane 사용) / NONE (plane 없음)
- `trackableType`: hit.trackable의 클래스명 (Plane, Point 등)
- `planeType`: Plane.type.name (HORIZONTAL_UPWARD_FACING 등)
- `normalY`: plane normal Y 성분 (수평이면 ≈ 1.0)

---

### 2.2 CUP_FIX

**시점**: CUP 고정 확정 시 (confirmLock, STABILIZING_END → END_LOCKED)

**형식**:
```
SLOPE_PHASE1: CUP_FIX slopeInput=PLANE trackableType=<Plane|...> planeType=<...|null> samePlane=<true|false> deltaYRaw_m=<float|null> planeDriftDeg=<float|null>
```

**필드**:
- `trackableType`: cup hit의 trackable 클래스명
- `planeType`: cup Plane 타입 (cupPlane이 null이면 "null")
- `samePlane`: BALL과 CUP이 동일 Plane인지
- `deltaYRaw_m`: cupPos.y - ballPos.y (m)
- `planeDriftDeg`: ballNormal과 cupNormal 각도 차이 (°)

---

### 2.3 SLOPE_RESULT

**시점**: slope 계산 완료 시 (END_LOCKED/RESULT, SlopeComputer.compute 직후, 1회만)

**형식**:
```
SLOPE_PHASE1: SLOPE_RESULT slopeInput=PLANE forwardPct=<%|null> lateralPct=<%|null> blocked=<reason|none> deltaYRaw_m=<float> ballNy=<float|null> cupNy=<float|null>
```

**필드**:
- `forwardPct`: 상하경사 (%)
- `lateralPct`: 측면경사 (%)
- `blocked`: SlopeComputer blockedReason
- `deltaYRaw_m`: cupPos.y - ballPos.y
- `ballNy`, `cupNy`: normal Y 성분 (수평이면 ≈ 1.0)

---

## 3. adb logcat 필터 예시

```bash
adb logcat -s SLOPE_PHASE1
```

한 번의 측정 흐름에서 예상 로그 순서:
1. `BALL_FIX` (BALL 고정 시)
2. `CUP_FIX` (CUP 고정 시)
3. `SLOPE_RESULT` (slope 계산 완료 시, 1회)

---

## 4. JSON 측정 로그 (PC_measurements_*.jsonl)

`buildMeasurementLogJson`에 `slopePhase1` 객체 추가:

| 키 | 타입 | 설명 |
|------|------|------|
| slopeInputSource | string | PLANE_BASED 등 |
| ballTrackableType | string | Plane, Point 등 |
| cupTrackableType | string | Plane, Point 등 |
| ballPlaneType | string | HORIZONTAL_UPWARD_FACING 등 |
| cupPlaneType | string | HORIZONTAL_UPWARD_FACING 등 |
| samePlane | boolean | BALL·CUP 동일 Plane 여부 |
| deltaYRaw_m | float | cupPos.y - ballPos.y |
| ballNormalSource | string | PLANE_CENTER_POSE 등 |
| cupNormalSource | string | PLANE_CENTER_POSE 등 |
| forwardPct | float | 상하경사 % |
| lateralPct | float | 측면경사 % |
| blockedReason | string | SlopeComputer blockedReason |
| quality | string | valid, rejected 등 |

---

## 5. 디버그 패널 (측정data → 분석 상세)

`formatSlopeDebugText`의 `[입력 소스]` 섹션에서 동일 항목 표시.

---

## 6. 검증 체크리스트

Phase 1 테스트 시 확인:
- [ ] BALL_FIX 로그: trackableType=Plane, planeType=HORIZONTAL_UPWARD_FACING, normalY≈1.0
- [ ] CUP_FIX 로그: samePlane, deltaYRaw, planeDriftDeg
- [ ] SLOPE_RESULT 로그: forwardPct, lateralPct, blocked
- [ ] 경사면에서 측정 시에도 normalY≈1.0, deltaY≈0 이면 → 입력 평탄화 의심
- [ ] JSON slopePhase1 객체에 모든 필드 기록되는지 확인
