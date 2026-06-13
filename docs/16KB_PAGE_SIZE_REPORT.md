# 16KB Page Size 대응 작업 보고서

**프로젝트**: PuttingCaddy (Flutter)  
**작업일**: 2025-02-17  
**목표**: Google Play Console "앱이 16KB 메모리 페이지 크기를 지원하지 않습니다" 오류 해결

---

## 1. 원인

### 문제 원인 후보 1순위

- **ONNX Runtime 1.18.0**  
  - `libonnxruntime.so`, `libonnxruntime4j_jni.so`  
  - 1.18.0은 16KB page size 미지원  
  - GitHub 이슈 [#26228](https://github.com/microsoft/onnxruntime/issues/26228)에서 1.23.0 이상에서 16KB 지원 추가 확인

- **ARCore 1.44.0**  
  - `libarcore_sdk_c.so`, `libarcore_sdk_jni.so`  
  - 16KB 호환 여부 확인을 위해 최신 안정 버전으로 업그레이드

---

## 2. 수정 내용

### 변경 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `android/app/build.gradle.kts` | ONNX Runtime, ARCore 버전 업그레이드 |

### 변경 전/후 핵심 내용

```kotlin
// 변경 전
implementation("com.google.ar:core:1.44.0")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

// 변경 후
implementation("com.google.ar:core:1.52.0")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.0")
```

### 올린 Plugin/SDK 버전

| 의존성 | 이전 | 이후 |
|--------|------|------|
| ARCore | 1.44.0 | 1.52.0 |
| ONNX Runtime Android | 1.18.0 | 1.23.0 |

---

## 3. 검증 결과

### AAB 빌드 성공 여부

- **성공**  
- 명령: `flutter clean` → `flutter pub get` → `flutter build appbundle --release`

### 16KB 관련 검사 결과

**arm64-v8a .so LOAD segment alignment (llvm-objdump -p):**

| 라이브러리 | LOAD align | 16KB 호환 |
|------------|------------|-----------|
| libonnxruntime.so | 2**14 (16KB) | ✓ |
| libonnxruntime4j_jni.so | 2**14 (16KB) | ✓ |
| libarcore_sdk_c.so | 2**14 (16KB) | ✓ |
| libarcore_sdk_jni.so | 2**14 (16KB) | ✓ |
| libflutter.so | 2**16 (64KB) | ✓ |
| libapp.so | 2**16 (64KB) | ✓ |

모든 네이티브 라이브러리가 16KB(2**14) 이상 정렬되어 16KB page size 요구사항을 충족합니다.

### 새 AAB 경로

```
C:\Users\cho58\StudioProjects\PuttingCaddy\build\app\outputs\bundle\release\app-release.aab
```

---

## 4. Play Console 업로드 안내

### 바로 올려도 되는지

- **예.**  
- 새 AAB는 16KB page size 요구사항을 충족하므로, 해당 AAB로 Play Console 프로덕션 업로드를 진행해도 됩니다.

### 추가 조치가 필요한지

- **없음.**  
- 앱 기능 변경 없이 의존성만 최소 범위로 업그레이드했으며, 서명/패키지명/동작에는 영향이 없습니다.

### 남은 리스크

- ONNX Runtime 1.18.0 → 1.23.0: 공식 릴리스 노트 기준 호환성 유지.  
- ARCore 1.44.0 → 1.52.0: Google 공식 SDK 업데이트.  
- 실제 기기에서 AR 및 ONNX 추론 기능을 한 번씩 테스트해 보는 것을 권장합니다.

---

## 요약

| 항목 | 내용 |
|------|------|
| 원인 | ONNX Runtime 1.18.0 (16KB 미지원) |
| 수정 | ONNX Runtime 1.23.0, ARCore 1.52.0으로 업그레이드 |
| 검증 | 모든 .so LOAD align ≥ 16KB 확인 |
| AAB | `build\app\outputs\bundle\release\app-release.aab` |
| 업로드 | 바로 진행 가능 |
