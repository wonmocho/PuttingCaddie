# PuttingCaddy Pro 모드 통합 계획서

> **결정사항**: Basic 모델과 Pro 모드를 단일 앱으로 통합합니다.  
> **팀 공유용 문서** — Google Play Billing 기준에 맞춘 제품 구조, 화면 흐름, 개발 지시문 포함

---

## ⚠️ 최우선 원칙 (절대 변경 금지)

### 거리 측정 기능 보존
- **현재 거리 측정은 완성 상태입니다.**
- **거리 측정 관련 기능은 반드시 그대로 유지합니다.**
- **다시는 맴돌이가 되어서는 안 됩니다.**
- BALL/CUP 측정, 최종 거리 계산, 결과 표시 흐름은 **코드 변경 없이** 유지
- Pro 통합 시 거리 엔진·측정 플로우는 **건드리지 않음**
- **Pro 모드 통합 작업은 기존 거리 측정 기능 위에 entitlement와 UI를 얹는 작업이며, 거리 측정 네이티브 로직 자체는 변경 대상이 아니다.**

### 구현 전략: 선구현·후오픈
- **현재 목표는 과금 오픈이 아니라 경사 기능의 신뢰성 확보와 테스트 편의성 확보**
- Billing/Entitlement 구조는 지금 구현하되, **일반 사용자에게는 숨김**
- 내부 테스트/디버그 환경에서 경사 기능을 충분히 검증할 수 있는 구조로 우선 구현
- 경사 정확도와 안정성이 충분해졌을 때만 과금 UI를 공개
- **"과금 기능은 선구현, 사용자 노출은 후오픈"**

---

## 1. 최종 제품 구조

| 구분 | 제공 기능 |
|------|-----------|
| **무료** | 거리 측정 |
| **Pro** | 상하경사, 측면경사, 향후 에이밍 가이드 |

### 원칙
- **앱은 하나만 유지** (Basic/Pro 별도 앱 없음)
- 거리 측정은 **항상 무료**
- 경사 기능은 **entitlement**로 접근 제어

---

## 2. Google Play 구독 상품 설계

Google Play 구독은 하나의 subscription 아래 여러 base plan을 둘 수 있으며, base plan은 auto-renewing 또는 prepaid로 구성할 수 있음. 사용자는 prepaid plan을 구매해 기간을 얻고, 이후 같은 subscription의 다른 prepaid base plan으로도 top-up(연장) 할 수 있음.

### 상품 구조
- **Subscription ID**: `puttingcaddy_pro`
- **Base plan 1**: `monthly-prepaid` (1개월 선불)
- **Base plan 2**: `season-3m-prepaid` (3개월 선불)

### Play Console 설정
| 항목 | 값 |
|------|-----|
| Product ID | `puttingcaddy_pro` |
| 표시명 | PuttingCaddy Pro |
| Base plan ID 1 | `monthly-prepaid` |
| Base plan ID 2 | `season-3m-prepaid` |

### 상품 설명 예시
> 거리 측정은 무료로 제공됩니다. Pro 구독 시 상하경사, 측면경사, 고급 퍼팅 가이드를 사용할 수 있습니다.

### 운영 팁 (초기)
- 한국만 활성화
- 1개월/3개월 상품만 먼저 오픈
- **prepaid base plan은 Allow extension 활성화 우선 검토**
- 할인·체험판은 보류
- **offers는 auto-renewing base plan에만 생성 가능** (prepaid는 offers 직접 적용 불가)
- 프로모션 필요 시 별도 prepaid base plan 추가 검토

---

## 3. 앱 화면 흐름

### 측정 흐름
1. 사용자가 BALL/CUP 측정
2. **거리 결과는 항상 무료**로 표시
3. 경사 카드 영역은 **잠금** 상태로 표시
4. CTA 버튼: "Pro로 경사 보기"
5. 구매 완료 시 **같은 결과 화면**에서 즉시 경사 결과 표시

### 결과 화면 예시 (무료)
```
거리: 3.8m
[잠금 카드] 상하경사 — Pro
[잠금 카드] 측면경사 — Pro
[1개월 Pro 시작] [3개월 Pro 시작]
```

### 설정 화면 예시
- 현재 플랜: 무료 / Pro
- **구매 복원 / 현재 구독 상태 동기화**
- Pro 관리
- 구독 상태 확인

### 운영 메모
- **구매 복원 / 현재 구독 상태 동기화**는 필수 운영 항목
- 앱이 전면에 올 때 또는 Billing 연결 직후 현재 구매 상태를 다시 조회해 entitlement를 맞춤
- Google Play Billing 문서: 앱 연결·전면 복귀 시 `queryPurchasesAsync()`로 현재 구매 확인 처리 권장

---

## 4. 권한 모델 (Entitlement)

### 개념
- 구매 여부는 **기능 엔진**이 아니라 **기능 접근 권한**으로 관리
- 거리 엔진은 **항상 동작** (변경 금지)
- 경사 엔진은 entitlement 있을 때만 결과 노출

### EntitlementState 예시
```dart
class EntitlementState {
  final bool distanceEnabled;   // 항상 true
  final bool slopeEnabled;     // Pro 또는 test override일 때 true
  final bool aimingEnabled;    // Pro일 때 true (향후)
  final DateTime? proExpiry;
  final bool isTestOverride;   // 내부 테스트용 강제 Pro 활성화
}
```

| 상황 | distanceEnabled | slopeEnabled | proExpiry | isTestOverride |
|------|-----------------|--------------|-----------|----------------|
| 무료 | true | false | null | false |
| Pro (billing) | true | true | expiryDate | false |
| 만료 후 | true | false | 과거 | false |
| **테스트 모드** | true | true | null | true |

- **billing entitlement**와 **test entitlement** 구분 가능하게 설계
- test override 시 결제 없이 slope 노출 → 내부 테스트 편의

### 원칙
1. 거리 엔진은 항상 동작
2. 경사 엔진은 entitlement 있을 때만 결과 노출
3. 구매 만료 시 경사 자동 잠금
4. **구매 복원 / 현재 상태 동기화** 시 자동 해제

---

## 5. 프로젝트 구조 (재편 계획)

### 아키텍처 전제
- 현재 PuttingCaddy는 **Flutter + Android Kotlin 혼합 구조**
- **거리 측정 로직은 android/ 네이티브에 완성 상태로 존재** (DistanceMeasurementActivity, V31StateMachine, V31Engine 등)
- Pro 통합 시 **거리 관련 Kotlin 코드는 수정·복제·이동 금지**
- 경사·billing·entitlement는 **추가 레이어**로만 붙임

```
PuttingCaddy/
├─ lib/
│  ├─ core/
│  ├─ billing/         # 상품 조회, 구매, 복원, 상태 동기화
│  ├─ entitlement/     # 무료/Pro 권한 상태 (단일 관리)
│  ├─ ui/
│  └─ features/
│     ├─ measure/      # (기존 Kotlin 흐름과 연동)
│     ├─ result_free/  # 거리 결과 화면
│     └─ result_pro/   # 경사 결과 카드/잠금 해제 UI
└─ android/
    └─ ...            # distance 엔진, slope 계산 등 (기존 유지·확장만)
```

### 모듈 역할
| 위치 | 역할 |
|------|------|
| android/ | 거리 엔진 (변경 금지), slope 계산 (추가 시), AR 연동 |
| lib/billing/ | 상품 조회, 구매, 복원 |
| lib/entitlement/ | 무료/Pro 권한 상태 **단일 관리** |
| lib/features/result_free/ | 거리 결과 화면 |
| lib/features/result_pro/ | 경사 결과/잠금 UI |

---

## 5-1. Feature Flag (과금 노출 제어)

과금 오픈 시점을 제어하기 위한 플래그.

| 플래그 | 설명 | 초기 권장값 |
|--------|------|-------------|
| `showProPaywall` | Pro 결제 유도 UI 노출 | false |
| `enableBillingPurchaseFlow` | 구매 플로우 활성화 | false |
| `enableSlopeForInternalTest` | 내부 테스트 시 slope 노출 | true |
| `enableSlopeForReleaseUsers` | 일반 사용자 slope 노출 (billing 기반) | false |

- **초기**: 코드 구현 완료, 사용자 비노출, 내부 테스트만 가능
- **과금 오픈 시**: 위 플래그를 true로 전환

---

## 6. Billing 구현 원칙

### Google Play 흐름
1. 구매 가능 상품 표시
2. 구매 플로우 실행
3. 구매 검증
4. 권한 부여
5. **acknowledge** 처리

- 초기 subscription purchase는 acknowledge 필요
- renewal은 acknowledge 대상 아님
- 1개월·3개월 prepaid는 3일 내 acknowledge 기준
- 향후 1일·3일·1주 등 짧은 prepaid plan 추가 시 acknowledge 처리 규칙 재검토

### 검증 단계
| 단계 | 방식 |
|------|------|
| 1단계 | 앱 로컬 + Play 구매상태 기반 복원 |
| 2단계 | 서버 검증 + RTDN 연동 권장 |

### 권장 아키텍처
- 초기: 앱 단 entitlement 복원으로 시작 가능
- 실운영: 백엔드 검증 + RTDN을 붙이는 것을 권장
- Google Play: RTDN·Play Developer API 기반 백엔드 purchase status management 권장

---

## 7. 개발 구현 범위

1. **프로젝트 구조 정리** — `lib/core`, `lib/billing`, `lib/entitlement`, `lib/features/result_free`, `lib/features/result_pro` 등
2. **entitlement 계층 추가** — 무료/Pro 권한, 만료 시 slope 자동 잠금, **앱 시작·포그라운드 복귀 시 현재 구매 상태 동기화**
3. **결과 화면 개편** — 무료: 거리만 표시, 경사 카드 잠금, CTA 버튼 (**거리 표시 로직 변경 금지**)
4. **billing 계층 설계** — 상품 조회, 구매, base plan 선택, 구매 복원/현재 구독 상태 동기화
5. **slope 기능 분리** — distance 결과 입력 → slope 계산, entitlement 없으면 노출 차단

---

## 8. 테스트 포인트

### 최우선 (경사 신뢰성 확보 단계)
- [ ] 무료 사용자 **거리 측정 정상** (기존과 동일) — **1순위**
- [ ] **거리 기능 회귀 없음**
- [ ] 테스트용 Pro 모드에서 **slope 반복 측정 원활**
- [ ] 앱 재실행·포그라운드 복귀 후 entitlement/test override 정상 반영
- [ ] 일반 release 사용자에게 slope·과금 UI가 **숨겨져 있는가**

### 과금 오픈 후
- [ ] 무료 사용자에게 경사 UI 잠금 표시
- [ ] 구매 성공 후 slope 노출
- [ ] 만료/비활성 상태에서 slope 잠금 복귀
- [ ] prepaid 연장(top-up) 후 entitlement 연장 반영

---

## 9. Pro 모드 통합 작업 순서 (수정본)

### Phase 0: 준비
- 현재 프로젝트 구조 파악
- android/ 네이티브 거리 로직 위치 확인
- 거리 측정 기준선 확보 (BALL/CUP/FINAL 결과 스크린샷·체크리스트)
- **원칙**: 거리 측정은 절대 건드리지 않음

### Phase 1: Entitlement 계층 추가
- EntitlementState 모델 정의 (distanceEnabled, slopeEnabled, aimingEnabled, proExpiry, **isTestOverride**)
- entitlement 상태 관리자 구현 (단일 관리)
- 무료 사용자 기본값: distanceEnabled=true, slopeEnabled=false
- **거리 측정 코드는 수정하지 않음**

### Phase 2: Billing 연결 (사용자 비노출)
- Google Play Billing 상품 구조 연결 (puttingcaddy_pro, base plans)
- 상품 조회, 구매 플로우, acknowledge, 복원, 현재 구독 상태 동기화 **구현**
- 앱 시작·포그라운드 복귀 시 entitlement 재동기화 구조 구현
- **단, 구매 버튼·Pro 관리 메뉴는 사용자에게 비노출** (배관만 설치)

### Phase 3: 테스트용 Pro 모드 추가
- 내부 테스트 전용 Pro 강제 활성화 (debug 빌드, hidden menu, long press, config flag 등)
- billing entitlement 없어도 테스트 가능하도록 **test entitlement 허용**
- 결제 없이 내부 테스트에서 경사를 반복 검증 가능하게 구성

### Phase 4: UI에 잠금 레이어 추가
- 결과 화면에 경사 카드 영역 추가
- 무료 사용자용 잠금 카드 표시
- CTA 버튼 UI는 구현하되 **feature flag로 비노출**
- **기존 거리 결과 UI는 변경·이동 금지**

### Phase 5: Slope 기능 통합 및 집중 테스트
- slope 계산 모듈 추가 (distance 결과 → slope 계산만)
- entitlement 또는 test flag가 있을 때만 노출
- **우선순위: slope test 편의성** — 내부 테스트에서 즉시 경사 반복 검증 가능해야 함

### Phase 6: 검증
1. 무료 사용자 거리 측정이 기존과 **동일한가** (최우선)
2. 거리 회귀가 **전혀 없는가**
3. 테스트용 Pro 모드에서 경사가 원활히 반복 테스트 가능한가
4. 앱 재실행·포그라운드 복귀 후 entitlement/test override 정상 반영
5. 일반 release 사용자에게 slope·과금 UI가 **숨겨져 있는가**

### Phase 7: 과금 오픈 (경사 신뢰성 확보 후)
- CTA 버튼 공개
- 구매 화면 공개
- 설정의 Pro 관리 공개
- Play Console 상품 활성화
- Staged rollout
- **과금은 마지막 스위치**

### 테스트 모드 진입 방식 (검토)
- 설정 아이콘 5회 연속 탭
- 버전명 롱프레스
- debug 빌드에서 자동 활성화
- (기타 hidden developer menu)

---

## 9-1. 과금 오픈 시 켜야 할 스위치 목록

| 스위치 | 설명 |
|--------|------|
| `showProPaywall` | true로 전환 |
| `enableBillingPurchaseFlow` | true로 전환 |
| `enableSlopeForReleaseUsers` | true로 전환 (billing entitlement 기반) |
| Play Console 구독 상품 | 활성화 |

---

## 9-2. 출시 순서 (전체)

1. Phase 0~6 수행 (과금 비노출 상태)
2. 경사 신뢰성 충분히 확보
3. Billing 라이선스 테스터로 구매/복원 테스트
4. 내부/비공개 테스트에서 결제 검증
5. Phase 7 과금 오픈
6. Staged rollout
7. RTDN/백엔드 검증 고도화

---

## 9-2b. 경사 검증 단계 원칙

**현재 단계는 과금/잠금 UI보다 경사 성능 검증이 우선이다.**

1. **1단계: 내부 테스트 완성** (현재)
   - 모든 경사/품질 데이터 전부 표시
   - -Pro 문구 금지, 잠금 카드 금지, CTA 비노출
   - debug 빌드에서 Slope Debug 패널로 전체 원시값·진단 표시

2. **2단계: 사용자용 단순화** (추후)
   - 어떤 값만 사용자에게 보여줄지 결정
   - 필요 없는 디버그 값 숨김

3. **3단계: 과금 오픈** (경사 신뢰성 확보 후)
   - Pro entitlement 뒤로 경사 이동
   - -Pro, CTA, 구독 유도

---

## 9-3. 경사 데이터 의미 정리 (Slope/Plane semantics)

경사 관련 데이터는 3종류로 구분한다. UI에 혼동 없이 표시하려면 각각의 의미를 정확히 구분해야 한다.

| 구분 | 데이터 | 의미 | 표시 |
|------|--------|------|------|
| **상하경사** | `horizontalVerticalMeters` (h, v) | 퍼팅 진행 방향 종방향 경사. slope % = (v/h)*100. +는 오르막, -는 내리막 | 정식 표시 |
| **측면경사** | (미구현) | 퍼팅 라인에 직각인 횡경사 = 좌우경사(브레이크). 진행방향 대비 횡방향 성분 필요 | — |
| **평면차이** | `ballCupPlaneAngleDeg` | BALL·CUP 국소 평면 법선의 3D 각도. 지면 일관성/신뢰성 진단값 (측면경사 아님) | 별도 항목 "평면차이 X.X°" |

### 판단 기준 (Cursor/AI 적용)
- `ballCupPlaneAngleDeg`는 측면경사가 아니라 BALL·CUP 평면의 3D 각도 차이로 해석한다.
- 현재 단계에서는 측면경사 값으로 직접 표시하지 말고, '평면차이' 또는 '그린면 차이'라는 별도 진단 항목으로만 표시한다.
- 상하경사는 `horizontalVerticalMeters` (h, v) 기반 slope %를 유지한다.
- 진짜 측면경사는 [LATERAL_SLOPE_DESIGN.md](./LATERAL_SLOPE_DESIGN.md)에 정의된 수식(기준 normal을 진행축·좌우축으로 분해)으로 별도 구현한다.

---

## 10. 핵심 원칙 (개발 시 준수)

### 최우선
- **기존 거리 측정 기능은 절대 깨지지 않게 유지**
- **거리 엔진·측정 플로우는 건드리지 않음**
- **다시는 맴돌이가 되어서는 안 됨**

### 일반
- 경사 기능은 **숨김/잠금** 구조로 먼저 통합
- **앱은 하나만** 유지
- 기능 노출 제어는 **entitlement에서 단일 관리**
- **Billing 상태는 UI가 아니라 entitlement 계층에서 단일 관리**
- 하드코딩 대신 **확장 가능한 구조**로 설계
- 장기적으로는 **백엔드 중심 검증 구조**로 전환

---

## 11. Cursor/AI 실행 지시문

### 작업 목표 (수정)
- **현재 목표는 과금 오픈이 아니라 경사 기능의 신뢰성 확보와 테스트 편의성 확보**
- Billing/Entitlement는 지금 구현하되 **일반 사용자에게는 숨김**
- 내부 테스트에서 경사 기능을 충분히 검증할 수 있는 구조로 우선 구현
- **경사 신뢰성이 충분히 확보된 뒤에만** 과금 UI 오픈

### 최우선 금지사항
- **거리 측정 관련 기능·코드는 절대 변경하지 말 것**
- **거리 엔진을 새로 복제·이동·리팩터링하지 말 것**
- **slope 때문에 거리 기능을 건드려 불안정하게 만들지 말 것**
- **BALL/CUP 측정, 최종 거리 계산, 결과 표시 흐름은 그대로 유지할 것**
- **다시는 맴돌이가 되어서는 안 됨**

### 구현 요구사항

**1. Entitlement 계층**
- distanceEnabled, slopeEnabled, aimingEnabled, proExpiry, **isTestOverride** 관리
- billing entitlement와 **test entitlement** 구분 가능하게 설계

**2. Billing 구현**
- 상품 조회, base plan, 구매 플로우, acknowledge, 복원, 현재 구독 상태 동기화 구현
- 앱 시작·포그라운드 복귀 시 현재 구매 상태 동기화
- **단, 현재는 일반 사용자에게 구매 버튼·과금 UI 비노출**

**3. 테스트용 Pro 모드**
- debug/internal/test 빌드에서 slope 강제 활성화 가능한 **hidden test mode** 추가
- 예: secret menu, long press, local config, debug flag
- **결제 없이도** 내부 테스트에서 slope 결과를 반복 검증 가능

**4. UI 정책**
- 결과 화면에 경사 카드 영역 추가 가능
- 현재 공개 release에서는 잠금 또는 비노출
- CTA/과금 유도 UI는 **feature flag로 숨김**
- **기존 거리 결과 UI는 변경·이동 금지**

**5. Slope 통합**
- distance 결과 입력 → slope 계산만 추가
- entitlement 또는 **test override**가 있을 때만 노출
- **현재 우선순위: slope test가 원활해야 함**

**6. Feature Flag 초기값**
- showProPaywall = false
- enableBillingPurchaseFlow = false
- enableSlopeForInternalTest = true
- enableSlopeForReleaseUsers = false

### 테스트 최우선 항목
1. 무료 사용자 거리 측정이 기존과 **동일한가**
2. 거리 회귀가 **전혀 없는가**
3. 테스트용 Pro 모드에서 slope가 원활히 **반복 테스트 가능한가**
4. 앱 재실행·포그라운드 복귀 후 entitlement/test override 정상 반영
5. 일반 release 사용자에게 slope·과금 UI가 **숨겨져 있는가**

### 산출물
1. 변경 파일 목록
2. **hidden Pro test mode 진입 방식**
3. **feature flag 구조**
4. billing 구현 상태
5. **slope 테스트 방법**
6. **거리 회귀 여부**
7. **과금 오픈 시 켜야 할 스위치 목록**

### 최종 목표
지금은 **과금 공개가 아니라**, 거리 기능을 건드리지 않은 상태에서 경사 기능을 내부 테스트 가능하게 통합하고, 나중에 **스위치만 켜면** 과금 오픈이 가능하도록 준비하는 것

---

*문서 작성일: 2026-03*  
*관련 문서: [PROJECT_VERSION_NOTE.md](./PROJECT_VERSION_NOTE.md)*
