# Phase 0: 프로젝트 구조 및 거리 로직 기준선

## 1. 현재 구조

### 앱 진입점
- **MainActivity** (FlutterActivity) → 즉시 **DistanceMeasurementActivity**로 전환 후 finish
- Flutter `lib/main.dart`는 "Launching..."만 표시, 실질적 UI는 100% Android 네이티브

### 거리 측정 핵심 파일 (변경 금지)
| 파일 | 역할 |
|------|------|
| `DistanceMeasurementActivity.kt` | 메인 UX, BALL/CUP 버튼, 결과 표시, 설정 |
| `V31StateMachine.kt` | 측정 상태 머신 (IDLE→AIM_START→...→RESULT) |
| `V31Engine.kt` | 거리 계산 엔진 |
| `V31HitSampler.kt` | BALL/CUP hitTest 샘플링 |
| `ScreenToViewMapper.kt` | 화면↔hitTest 좌표 매핑 |

### UI/AR
| 파일 | 역할 |
|------|------|
| `activity_distance_measurement.xml` | 결과 화면 레이아웃 (txtDistance, btn_start, btn_finish 등) |
| `ViewFinderView.kt`, `ViewFinderMaskView.kt` | 뷰파인더 UI |
| `BackgroundRenderer.kt` | AR 카메라 렌더링 |

### 기타
| 파일 | 역할 |
|------|------|
| `CupYoloDetector.kt` | 컵 YOLO 감지 |
| `LegacyPoseEngine.kt`, `MeasurementEngine.kt` | 레거시/대체 엔진 (사용 여부 확인 필요) |

## 2. 거리 결과 표시 흐름

1. `V31Engine.onFrame()` → `V31StateMachine` tick → `UiModel` 반환
2. `DistanceMeasurementActivity`의 `applyUiToViews()`:
   - `ui.engineState == RESULT` → `onResultReached(ui)` 호출
   - `txtDistance.text = formatDistanceWithDecimals(ui.distanceMeters, decimals)`
3. `onResultReached()`: 세션 기록, 설문/리뷰 체크

## 3. 기준선 체크리스트 (사용자 확인)

- [ ] BALL 탭 → 공 인식 → CUP 탭 → 컵 인식 → 거리 결과 표시
- [ ] LIVE 거리(1자리) → FINAL 거리(2자리) 정상 전환
- [ ] RESET 후 재측정 정상
- [ ] 단위(m/yd) 전환 정상
- [ ] 기기별 거리 값 일관성 (기록 권장)

## 4. Pro 통합 시 접근 포인트

- **결과 화면**: `activity_distance_measurement.xml`의 `txtDistance` 아래 또는 `txt_instruction` 위에 **경사 카드 영역 추가** (기존 요소 이동 금지)
- **설정 메뉴**: `showSettingsBottomSheet()` 내에 Pro 항목 추가 (feature flag로 숨김)
- **Entitlement 주입 지점**: `DistanceMeasurementActivity` 또는 공유 ViewModel/Manager

---
*Phase 0 완료 후 Phase 1~ 진행*
