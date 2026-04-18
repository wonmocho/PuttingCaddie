# 두 앱 분리: PuttingCaddy+ / PuttingCaddy (4/3 안정)



> **필수:** 두 앱은 **서로 다른 디렉터리 이름**에 둔다. 안정판은 Plus Git 저장소 **안에 넣지 않는다.**  

> - **PuttingCaddy+**: Git 저장소 루트 폴더 이름 **`PuttingCaddyPlus`** (`com.wmcho.puttingcaddyplus`)  

> - **PuttingCaddy** (4/3 안정): 형제 폴더 **`PuttingCaddy`** — `scripts/export_stable_puttingcaddy.ps1` 기본 출력  

> 루트 `REPO_ROOT_IS_PUTTINGCADDY_PLUS.txt`, `.cursor/rules/two-app-workspaces.mdc` 참고.



## 요약



| 앱 | 런처 이름 | `applicationId` | 로컬 폴더 (권장) | Cursor |

|----|-----------|-----------------|---------------|--------|

| **PuttingCaddy+** | PuttingCaddy+ | `com.wmcho.puttingcaddyplus` | **`StudioProjects/PuttingCaddyPlus`** (이 Git 클론) | 이 폴더만 연다 |

| **PuttingCaddy** | PuttingCaddy | `com.wmcho.puttingcaddy` | **`StudioProjects/PuttingCaddy`** (export 결과) | 안정 작업 시 **이 폴더만** 연다 |



두 `applicationId`가 다르므로 **동시 설치** 가능합니다. (Kotlin `namespace`는 기존 `com.wmcho.puttingcaddie` 유지 — `applicationId`와 별개.)



## 로컬 폴더 (이름 고정)



| 폴더 | 역할 |

|------|------|

| **`…/PuttingCaddyPlus`** | 연구·기능 포함 **PuttingCaddy+** 소스. **지금 이 Git 저장소**는 여기 두는 것을 전제로 한다. |

| **`…/PuttingCaddy`** | **4/3 안정** 스토어용 **PuttingCaddy** 전체 트리(스크립트가 생성·갱신). Plus와 **형제** 디렉터리. |



예: `StudioProjects/PuttingCaddyPlus` 옆에 `StudioProjects/PuttingCaddy`.



과거 이름 **`PuttingCaddyStable`** 은 쓰지 않는다. 이미 있으면 내용 백업 후 삭제하거나, 한 번 export로 **`PuttingCaddy`** 로 옮기면 된다.



## PuttingCaddy+ 빌드



**PuttingCaddyPlus** 폴더(이 저장소) 루트에서:



```bash

cd android

./gradlew :app:compileDebugKotlin

# 또는

flutter build appbundle

```



## PuttingCaddy (안정) 생성 및 빌드



1. **먼저** Plus 저장소 폴더 이름이 **`PuttingCaddyPlus`** 인지 확인한다. (`PuttingCaddy` 라는 이름이면 export 기본 경로와 충돌하므로 스크립트가 중단한다.)



2. Plus 저장소 루트에서:



```powershell

powershell -File scripts/export_stable_puttingcaddy.ps1

```



- 기본 출력: **부모 폴더** 아래 **`PuttingCaddy`** (예: `StudioProjects/PuttingCaddy`).

- 다른 경로: 환경 변수 **`PUTTINGCADDY_STABLE_DIR`** 에 전체 경로 (레거시 이름이지만 그대로 사용).



3. **Cursor / IDE:** **`PuttingCaddy`** 폴더만 연다 (Plus와 동시에 루트로 열지 않는 것을 권장).



4. **Flutter SDK:** `PuttingCaddy/android/local.properties` 가 없으면  

   **`PuttingCaddyPlus/android/local.properties`** 를 복사한다.



5. **서명:** `key.properties` 는 `PuttingCaddy/android/` 에 두거나, 형제 **`PuttingCaddyPlus/android/key.properties`** 를 사용 (`build.gradle.kts` 탐색 순서 참고).



6. 빌드:



```bash

cd PuttingCaddy

flutter pub get

flutter build appbundle

```



## 패치(오버레이) 위치



안정판에 덮어쓸 파일은 **Plus 저장소** 안의 `stable_snapshot_overrides/android/` 에만 둔다.  

스크립트가 export 시 형제 **`PuttingCaddy`** 로 복사한다.



## 동시 설치 확인



1. Plus: `com.wmcho.puttingcaddyplus` 로 빌드·설치  

2. 안정: `com.wmcho.puttingcaddy` 로 빌드·설치  

3. 런처에 **PuttingCaddy** 와 **PuttingCaddy+** 가 각각 보이면 성공.



## 주의



- 안정 트리는 **4/3 커밋 3e10423** 기준 `git archive` 이다. 재생성하면 해당 폴더가 통째로 갈아엎인다. 장기 수정은 **오버레이**에 반영할 것.

- 과거 사용하던 `puttingcaddy_stable_403/` (Plus repo 안) 방식은 폐기했다. 남아 있으면 삭제해도 된다.


