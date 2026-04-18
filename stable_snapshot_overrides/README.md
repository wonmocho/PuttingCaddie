# PuttingCaddy (4/3 안정) 패키징 오버레이

`git` 커밋 **3e10423** 에서 추출한 트리 위에 덮어쓰는 파일만 보관합니다.

- `android/app/build.gradle.kts` — `applicationId` = `com.wmcho.puttingcaddy`, 서명 경로 보정  
- `android/settings.gradle.kts` — `local.properties` 폴백  
- `android/app/src/main/res/values/strings.xml` / `values-ko/strings.xml` — 런처 이름 **PuttingCaddy**, 거리 연습 모드 문자열
- `android/app/src/main/kotlin/.../PracticeModeController.kt` — 거리 반복 연습(안정판 전용)
- `android/app/src/main/kotlin/.../DistanceMeasurementActivity.kt` — 연습 UI 연동 포함 안정판 액티비티
- `android/app/src/main/res/layout/activity_distance_measurement.xml` — 연습 패널 포함 레이아웃

재생성: Plus 저장소(**폴더 이름 `PuttingCaddyPlus`**) 루트에서  
`powershell -File scripts/export_stable_puttingcaddy.ps1`  
→ 기본적으로 **형제 폴더 `PuttingCaddy`** 에 전체 앱이 풀린다 (Plus repo 안에 넣지 않음).  
경로 변경: 환경 변수 `PUTTINGCADDY_STABLE_DIR`.
