# 기업 식대 QR 결제 플랫폼 설계 리서치 보고서

> 초점 모델: **후불형(postpaid) 제휴 식당 네트워크**
> TASPA = 네트워크 운영자 / 식당 = 온보딩된 제휴처 / 개인 = 여러 조직 소속 직원(QR 결제) / 조직 = 월말 실사용액 후불 청구 + 한도·식사시간 정책 통제 / 식당 = B2B 정산 대상
> 벤치마크 원형: 한국 식권대장(현대벤디스)·식신 e식권 계열
>
> 작성 목적: 4각도 리서치(유사 제품 / QR 결제·보안 / 한도·원장·정산 / taspa 접목)를 **taspa 위에 무엇을 어떻게 지을지에 대한 실행 가능한 설계 권고**로 종합.
> 작성일: 2026-07-18

---

## 0. 한 장 요약 (TL;DR)

- **모델 확정**: taspa 후불형은 식권대장과 사실상 동일 구조다. 개인 선불충전이 없어 **선불전자지급수단 규제를 회피**하고, 세무상 **식대 비과세(월 20만원)** 요건인 "현금환급 불가 식권 + 식당과 식사제공 계약"을 폐쇄루프로 충족할 수 있다.[¹](#s1-src)
- **경계 원칙**: taspa는 ADR 0001대로 **순수 IdP**로 남긴다. 식대 플랫폼은 taspa **안에 넣지 않고**, taspa 토큰을 소비하는 **별도 Resource Server 군**(식당·한도·거래·원장·청구·정산)으로 올린다. taspa에는 `organizations`/`org_memberships` 테넌시와 QR 결제토큰 발급만 최소 추가한다.[⁴](#s4-src)
- **QR 방향**: **주(主) CPM(소비자제시형, POS 스캔) + 보조 MPM(무인 매장, 서명·인앱검증)**. QR = taspa 서명키로 발급한 **초단명(30~60초) 1회성 서명 토큰**. 금액·한도·PII는 QR에 넣지 않는다(가맹점 POS가 금액 입력, 서버가 한도 조회).[²](#s2-src)
- **규제 최대 리스크**: TASPA가 개인 결제대금을 받아 식당에 재정산하면 **전자지급결제대행업(PG) 등록 트리거**에 정면으로 걸린다. **초기에는 외부 PG/에스크로에 실제 자금정산을 위탁**하고 TASPA는 한도·거래·청구 정보만 운영한다(등록 예외 활용). 규모 확대 시 PG 등록 로드맵.[³](#s3-src)
- **원장은 별도**: taspa `audit_events`(자유 JSON)는 보안 감사용이지 금전 원장이 아니다. **append-only 이중부기 원장**을 별도 불변 저장소로 신설한다.[³](#s3-src)[⁴](#s4-src)

---

## 1. 요약 · 유사 시스템 벤치마크

### 1.1 벤치마크 표

| 서비스 | 지역 | 자금 모델 | QR 메커닉스 | 조직 정산 | 정책 통제 | 식당 온보딩 | TASPA 정합성 |
|---|---|---|---|---|---|---|---|
| **식권대장** (현대벤디스) | KR | **후불형** 조직 크레딧, 초과분만 개인충전 하이브리드 | **CPM**: 앱이 QR 생성 → 점원이 스캔 → **점원이 결제확정 버튼**(위조방지) | 다(多)제휴점 → **단일 세금계산서**, 익월 일괄 정산 | 사용시간대(예 10~15/17~22), 요일(평일·주말·공휴일), 일 한도, 지급기준 다양화 | **기존 주변 식당 우선 편입**(수요 선점형), 식당이 정산 수수료 부담 | ★★★★★ 원형·직접 벤치마크 |
| **식신 e식권** | KR | **후불형**, 24만 임직원/5만 식당 | 모바일 전자식권, 편의점은 바코드 스캔, **식신페이=무단말 터치결제** | 실사용 후불, GS25 정산 인프라 활용 | 사용처·시간·한도 기업별 설정 | 기존+신규 자영업 폭넓게, 무단말 온보딩, 수수료 2~5% | ★★★★★ |
| **비플식권** (비즈플레이/제로페이) | KR | 후불형, **제로페이 QR 인프라 편승** | 제로페이 QR(CPM+MPM), 별도 단말 불필요 | 제로페이 정산 **D+2 입금**, 자동 정산 | 시간·한도 정책 | **제로페이 가맹점=즉시 사용처**(가입 장벽 최소) | ★★★★ 인프라 편승 참조 |
| **올리브 식권** (스마트올) | KR | 후불형, 구내식당~외부~편의점~배달 통합 | 모바일 앱(국내 표준) | 기업별 정책 기반 대행 | 채널별 정책 | 구내+외부 병행 | ★★★ |
| **Swile** | FR/EU | **선불형** 카드 rail (고용주 월 사전충전) | QR 아님 — 카드 tap/Apple Pay | 발행사가 가맹점 직접지급, 고용주 선충전 | **법정**: 일 €25, 월~토, 근무일당 1매 | CNTR 승인 필요 | ★★ 정책 참조(자금모델 상이) |
| **Edenred** Ticket Restaurant | FR/45개국 | **선불형** 카드/디지털 | 카드 rail | Edenred 정산·환급 + 사용 리포트 | 고용주 포털 설정 + 법정 한도 | 어콰이어링 네트워크 연결 | ★★ |
| **Pluxee** (구 Sodexo BRS) | IN/글로벌 | **선불형** 식대카드 | 카드 tap/scan, 일부 QR | 유효거래 후 가맹점 계좌 직접지급 | 식품·무알코올 MCC 제한 | 수수료 3.5~4.55% | ★★ |
| ~~Notch~~ | CA | **모델 불일치** — 레스토랑 **도매 공급망** 발주/인보이스 SaaS | 해당 없음 | 해당 없음 | 해당 없음 | 식자재 공급 온보딩 | ✗ 벤치마크 제외 |
| ~~Forkable~~ | US | **모델 상이** — 오피스 **케이터링/그룹오더** | 직원 QR 결제 없음(사전 발주→배달) | 출근 인원 기반 과금 | 배달 스케줄 중심 | 인하우스 메뉴가 등록 | ✗ 벤치마크 제외 |

### 1.2 핵심 통찰

1. **국내 3사(식권대장·식신·비플)가 TASPA의 직접 원형**이고, 유럽 3사(Swile·Edenred·Pluxee)는 **선불 카드 rail**이라 자금 모델이 근본적으로 다르다. 유럽 사례는 **정책(법정 일 한도·요일·카테고리 제한)의 참조**로만 쓰고, 아키텍처는 국내 후불 모델을 따른다.
2. **식권대장의 "점원이 결제확정 버튼을 누른다"** 는 설계가 양도·위조 방지의 핵심 UX다. TASPA도 **최종 승인 책임을 가맹점 POS 쪽에 두는 CPM**을 1차 채택해야 한다.
3. **수요 선점형 온보딩**(고객사가 이미 쓰던 주변 식당을 우선 제휴)이 국내 성장의 열쇠였다. 식당은 후불 정산을 꺼리므로, **무단말 온보딩(식신페이)·즉시 사용처화(비플=제로페이)** 로 진입 장벽을 낮춘 사례를 참조한다.
4. 수익원은 공통적으로 **(1) 조직 솔루션 사용료 + (2) 식당 정산 수수료(2~5%)**. 배달앱 대비 저수수료가 식당 온보딩의 세일즈 포인트.

<a name="s1-src"></a>
> **각주 ¹ (§1 출처)** 식권대장/현대벤디스 서비스소개서 https://sikdae.com/sikdae · https://together.ehyundai.com/vol026/sub0102_ver4.html · 식신 e식권 https://www.siksine.com/ · https://www.edaily.co.kr/news/read?newsId=01446486632297760 · 비플식권 https://www.mealzeropay.com/ · https://mealzeropay.gitbook.io/intro-guide/ · Swile https://www.swile.co/solutions/meal-vouchers · Edenred https://frenchly.us/frances-ticket-restaurant-or-ticket-resto-program-explained/ · Pluxee https://www.pluxee.in/products/meal-employee-benefits/ · 프랑스 규제 https://www.innovorder.com/en/blog/luncheon-vouchers-regulations-in-force · (모델불일치) Notch https://app.notch.financial/ · Forkable https://forkable.com/

---

## 2. 도메인 모델 · 바운디드 컨텍스트 (기존 IdP 경계 준수)

### 2.1 경계 원칙 — taspa는 IdP로 남긴다

taspa의 ADR 0001은 "**중앙 IdP + Resource Server는 JWT만 검증**"을 결정했다. 식대 플랫폼을 taspa 모놀리스에 흡수하면 이 경계가 무너진다. 따라서:

- **taspa 안(인증 도메인)**: 신원(`users`) · 조직 테넌시(`organizations`, `org_memberships`) · 서명키(JWK) · OIDC 토큰/스코프 · QR 결제토큰 발급 · 감사.
- **taspa 밖(각각 별도 서비스·DB·Resource Server)**: 식당 디렉터리 · 한도/자격 · 거래 · 원장 · 청구 · 정산.

> **왜 조직·소속·서명은 taspa 안인가?** 결제 승인·후불 청구의 귀속(누가·어느 조직 소속인가)과 QR 서명은 **인증 도메인**이다. 결제/정산 서비스가 이것을 신뢰하려면 IdP가 소유·발급해야 한다. 반대로 금전 원장·정책·거래는 인증과 무관하므로 밖으로 뺀다(ADR 0001 중복제거 원칙).

### 2.2 컨텍스트 맵 (ASCII)

```
                         ┌──────────────────────────────────────────────┐
                         │            taspa  (중앙 IdP / 경계 안)          │
                         │                                              │
  개인/조직관리자/식당주 ──▶│  users(sub=UUID)  organizations  org_members  │
      (OIDC 로그인)       │  merchant_members?  JWK(서명·회전)  OIDC/scope │
                         │  QR 결제토큰 발급 엔드포인트  audit_events      │
                         └───────┬───────────────┬──────────────┬────────┘
                                 │ access_token  │ QR JWT       │ client_credentials(M2M)
                                 │ (sub,org,role)│ (sub,org,jti)│ (ledger.write 등)
        ┌────────────────────────┼───────────────┼──────────────┼───────────────────┐
        ▼                        ▼               ▼              ▼                   ▼
┌───────────────┐      ┌──────────────────┐  ┌─────────┐  ┌─────────────┐  ┌──────────────┐
│ Merchant       │      │ Entitlement /    │  │ Trans-  │  │  Ledger     │  │ Billing +    │
│ Directory      │◀────▶│ Policy Engine    │◀▶│ action  │─▶│ (이중부기,   │─▶│ Settlement   │
│ (식당 온보딩·   │ 적격  │ (일/끼 한도,     │승인│ (QR스캔→ │원장│  append-only)│파생│ (조직 월청구 │
│  merchant_role)│ 검증  │  식사시간창,     │  │ 승인/거절│  │ 조직 미수/  │  │  ·식당 payout│
│                │      │  사용처 화이트)   │  │ 멱등키)  │  │ 식당 미지급) │  │  ·3-way 대사)│
└───────────────┘      └──────────────────┘  └─────────┘  └─────────────┘  └──────────────┘
   Merchant Dir DB        Policy DB            Txn DB        Ledger DB(불변)   Billing/Settle DB
                                                                                    │
                                                                          외부 PG/에스크로/펌뱅킹
                                                                          (실제 자금이체 위탁)
```

### 2.3 바운디드 컨텍스트 정의

| 컨텍스트 | 소유 데이터 | 책임 | 외부 참조키 |
|---|---|---|---|
| **조직·소속** (taspa 내) | `organizations`, `org_memberships`, `merchant_memberships` | 1급 테넌트, 다중소속·부서·역할의 진실 원천 | — |
| **식당 디렉터리** | 제휴 식당, 심사·영업상태, 소유/직원 | 온보딩/심사, 가맹점 적격성 | `owner = users.id(sub)` |
| **한도/자격 (Entitlement)** | 조직별 정책(한도·시간창·화이트리스트), 버전드 | 결제 authorize 시점 정책 평가 | `org_id`, 입력: `org_membership` |
| **거래 (Transaction)** | 결제 이벤트, 승인번호, 멱등키(`jti`,`posTxnId`) | QR 스캔→검증→승인/거절→매출확정 | `sub`, `org_id`, `merchant_id` |
| **원장 (Ledger)** | append-only 복식부기 분개 | 개인 사용·조직 미수·식당 미지급·수수료 | 거래ID |
| **청구 (Billing)** | 조직 월청구서, 세금계산서, 수납 | 월말 실사용액 집계→후불 청구 | `org_id` |
| **정산 (Settlement)** | 식당 payout, 수수료, 보류, 이체지시 | 식당 B2B 정산, 외부 PG 위탁 | `merchant_id` |

> **주의**: 원장은 taspa `audit_events`와 분리한다. 감사 이벤트는 REQUIRES_NEW 격리·자유 JSON으로 **보안 로그**용이지, 마감·정합성이 필요한 **금전 원장**이 아니다.

<a name="s2ctx-src"></a>
> **각주 (§2 출처)** taspa ADR 0001 `docs/adr/0001-central-idp-with-oidc.md` · ADR 0002 `docs/adr/0002-granular-roles-and-groups.md`(대안 C org_memberships) · `token/TokenCustomizerConfig.kt`(sub=users.id) · `domain/audit/AuditEvent.kt`

---

## 3. QR 결제 트랜잭션 흐름

### 3.1 QR 방향 결정: 주 CPM + 보조 MPM

| 항목 | CPM (소비자제시형) | MPM (가맹점제시형) |
|---|---|---|
| 흐름 | 앱이 QR 표시 → **점원이 스캔** | 매장 QR 게시 → 직원이 스캔 |
| 금액 입력 | **POS(신뢰)** | 직원(위조 가능) |
| 신뢰 앵커 | **연결된 POS**가 서버에 인가 요청 | 앱 |
| 재생/양도 | 초단명·1회성으로 완화 | **스티커 교체(quishing) 취약** |
| **TASPA 적합** | **주(主) 채널** ✅ | **보조**(무인 키오스크·구내식당) |

**결론**: 후불 네트워크는 한도·시간·잔여예산을 **서버에서 실시간 결정**해야 하므로, 온라인 POS가 스캔 즉시 TASPA에 인가를 요청하는 **CPM이 1차**다. 금액은 소비자가 아니라 POS가 넣으므로 실사용액 청구의 신뢰성이 확보된다. MPM은 무인 시나리오로 한정하되 **네트워크 발급 동적/서명 MPM**을 쓰고, 앱이 서명 검증 후 "가맹점명+금액"을 확정 화면에 표시해 quishing을 막는다.[⁵](#s2-src)

### 3.2 결제 시퀀스 (ASCII, 온라인 CPM 단일메시지 purchase)

```
 직원 앱            식당 POS              TASPA(발급)     Entitlement      Transaction+Ledger
   │                  │                      │              │                    │
   │ 1.잠금해제(생체)  │                      │              │                    │
   │──토큰 요청(세션+활성org)───────────────▶│              │                    │
   │◀─서명 QR JWT{sub,org,jti,exp≤60s,aud}──│  (activeKid로 RS/ES256 서명)      │
   │ 2.QR 렌더         │                      │              │                    │
   │ =====(화면 제시)=▶│ 3.금액 입력·QR 스캔   │              │                    │
   │                  │──POST /authorize────────────────────────────────────────▶│
   │                  │  {token, merchantId, amount, posTxnId(멱등키)}          │
   │                  │                      │              │                    │
   │                  │            4.서명·exp 검증(JWKS kid) │                    │
   │                  │            jti 미사용 확인            │                    │
   │                  │                      │              │                    │
   │                  │            5.정책 평가 ──요청────────▶│                    │
   │                  │              (시간창·요일·1회/일/월 한도·가맹점적격·잔여예산)│
   │                  │              ◀──approve/decline──────│                    │
   │                  │                      │              │                    │
   │                  │            6.원자적 확정 (단일 ACID Txn)───────────────────▶│
   │                  │              (a) jti 원자 소비(UNIQUE)                     │
   │                  │              (b) 조직 accrual 미수↑ / 식당 payable 미지급↑  │
   │                  │                  (SELECT..FOR UPDATE 예산행 잠금)          │
   │                  │              (c) 거래행 insert UNIQUE(merchantId,posTxnId) │
   │                  │◀──approve + authId ─────────────────────────────────────│
   │◀─결제 푸시 통지 ──│ 7.영수증 출력          │              │      8.정산 큐 적재  │
   │  (오청구 즉시 인지)│                      │              │      (익월 payout)  │
```

### 3.3 QR = 초단명 서명 토큰 (taspa 자산 정합)

- **정적 QR 금지**: 계정 식별자만 담은 정적 QR은 스크린샷 공유·복제·재생이 자명하다. 가치이전 QR은 반드시 **단명·1회성·기기바인딩**.[⁵](#s2-src)
- **taspa 자산 재사용**: taspa는 이미 (i) DB영속 RSA-2048 JWK를 `kid` 기반 회전(30일 주기, 7일 유예, 일1회 `JwkRotationJob`, 유출 시 즉시 `rotate()`), (ii) `activeKid()`로 서명키 고정, (iii) `/oauth2/jwks` 공개, (iv) OIDC 발급/`TokenCustomizer`를 보유. **QR을 단명 서명 토큰으로 발급/검증하는 것은 신규 인프라가 아니라 기존 파이프라인의 자연스러운 확장**이다.[⁶](#taspa-src)
- **페이로드(권장)**: `{iss:taspa, aud:"meal-network", sub:userId(UUID), org:orgId, membership_id, jti:<nonce>, iat, exp:iat+30~60s, policy_hash}`. **금액·잔여한도·PII는 넣지 않는다**(POS가 금액, 서버가 한도 조회 — 금융위 표준 "QR 내 민감정보 금지"와 정합).[⁵](#s2-src)
- **서명 알고리즘 주의**: RS256(RSA-2048) 서명은 base64로 ~344자 → QR 밀도↑·스캔성↓. 권장:
  - **ES256(EC P-256, ~64B)** 또는 **EdDSA(Ed25519)** 로 QR을 조밀하게. taspa JWK 스토리지는 RSA 특화이므로 **결제용 EC 키 프로파일을 병행 추가**.
  - **또는 불투명 핸들(opaque handle)**: 128비트 랜덤만 QR에 담고 클레임은 서버 상태로 보관. **온라인 CPM 환경에서 가장 작고 안전(정보 0)** — 오프라인 검증이 불필요하면 최우선 후보.

  > **권고**: **온라인 CPM = 불투명 단명 핸들이 최소·최강. 오프라인 검증 여지를 남기려면 EC/EdDSA 단명 JWS.** 어느 쪽이든 `jti` 단일사용 + 짧은 `exp`가 재생방지의 핵심.

### 3.4 이중결제 · 재생 · 양도 방지 (다층 방어)

```
 위협              1차                2차                 3차                 앵커
 ────────────────────────────────────────────────────────────────────────────────
 재생(replay)   exp 30~60s   →   jti 단일사용 nonce  →   기기/세션 바인딩   →   서버 인가
 이중결제        UNIQUE(jti)  →   UNIQUE(merchantId,   →  원자적 원장 차감   →   DB 유일제약
 (double-spend)                   posTxnId)              (SELECT..FOR UPDATE)   (하나만 성립)
 양도(transfer) 인증세션+기기 →   앱 잠금해제(생체)    →  1회/일/월 한도 상한 →   step-up 재인증
                바인딩 발급        후에만 QR 노출          +식사시간창+이상탐지    (고액)
 위조(forgery)  서명 필수      →   JWKS kid 검증       →  서버측 인가 최종     →   무서명 QR엔
                                                          신뢰 앵커              가치 無
```

- **멱등성**: 인가에 `posTxnId`, 환불에 별도 refund 키를 강제. 동일 키 재요청은 재처리 없이 저장된 결과 반환(Stripe식). 네트워크 재시도·더블클릭·웹훅 재전송의 이중청구를 차단.[⁵](#s2-src)
- **원자성**: `jti` 원자 소비 + 원장 차감 + 거래기록을 **하나의 ACID 트랜잭션**으로. 동일 토큰이 두 POS에서 exp 창 내 동시 인가돼도 **하나만 승인, 나머지 즉시 거절**.
- **오프라인 처리**: 가치=조직 크레딧이므로 **full-offline 가치이전은 금지**, 허용해도 staged(즉시 재동기화)까지. POS가 오프라인이면 캐시된 JWKS로 서명·exp만 오프라인 검증하고, **EMV floor limit**(소액·건수 제한)에서만 store-and-forward → 재연결 시 중복 `jti` 발견 시 거절/플래그. 이는 EMV의 1회성 크립토그램 + ATC 단조성의 **소프트웨어 대응물**이다.[⁵](#s2-src)
- **후불형 단순화**: 카드식 2단계(authorize hold→capture)보다 **단일메시지 승인 + 즉시 accrual**이 적합. 월말 조직별 집계 후불 청구.

<a name="s2-src"></a>
> **각주 (§3 출처)** EMVCo QR CPM/MPM https://www.emvco.com/emv-technologies/qr-codes/ · 금융위 QR코드 결제 표준 https://www.fsc.go.kr/po010106/73398 · EMV 크립토그램/ATC https://corebaseit-site.pages.dev/corebaseit_posts/emv-cryptograms-arqc/ · Replay attacks https://dple.github.io/posts/2023/05/replay-attacks-payments/ · Quishing https://scamwatch.com/article/quishing-how-qrcode-payment-scams-work · Idempotency https://simplico.net/2026/04/04/idempotency-in-payment-apis/ · Offline double-spend https://pmc.ncbi.nlm.nih.gov/articles/PMC9921608/ · 식권대장 QR(토스플레이스) https://www.pointe.co.kr/news/articleView.html?idxno=51887

---

## 4. 후불 정산 흐름 + 이중부기 원장

### 4.1 전체 자금·정보 흐름 (ASCII)

```
  [실시간]                    [월말 컷오프]                  [정산 주기]
   개인 QR 결제         ┌──▶ 조직 청구서 생성 ────▶ 조직 후불 수납      식당 payout
       │               │    (실사용액 집계)       (계좌이체/자동이체)   (bank 이체)
       ▼               │         │                     │                  ▲
 ┌───────────────┐     │         ▼                     ▼                  │
 │ 거래 승인      │─────┤   세금계산서 위탁발행     조직 미수금(AR) 상계    │
 │ (accrue)      │     │   (부가세 포함, 1건)                            │
 └───────────────┘     │                                                │
       │ 매 건 실시간 분개│                                          외부 PG/에스크로
       ▼               │                                          (실제 이체 위탁)
 ┌─────────────────────┴──────────────────────────────────────────────┐
 │  이중부기 원장 (append-only, 정수 minor unit, zero-sum)              │
 │                                                                     │
 │  결제 시(후불): 조직 미수(AR) ↑  ┃  식당 미지급(AP) ↑ + 플랫폼 수수료수익 │
 │  청구 확정:     조직 미수 → 확정 청구액 재분류                          │
 │  조직 수납:     현금 ↑ / 조직 미수 ↓                                   │
 │  식당 정산:     식당 미지급 ↓ / 현금(정산) ↓  (net = 매출 − 수수료)      │
 │  환불/취소:     역분개 posting(삭제 금지, 감사추적 유지)                │
 └─────────────────────────────────────────────────────────────────────┘
                                    │
                          [3-way 대사(reconciliation)]
        (a)거래 원장 ─── (b)조직 청구서(수납) ─── (c)식당 payout(은행 실지급)
              항등식: Σ개인사용액 = 조직청구액 = 식당정산액 + 플랫폼수수료
```

### 4.2 이중부기 원장 스케치

- **append-only 이중부기**: 수정·삭제 없이 역분개 posting만. 정수 minor unit(원 단위), **3중 잔액(posted / available / pending)**.
- **마켓플레이스 계정 위계**: `조직 미수(AR)` · `식당 미지급(AP)` · `플랫폼 수수료수익` · `현금`. 후불형은 결제 시 **개인 잔액 차감이 아니라 조직 accrual + 식당 payable 동시 발생**(카드식 hold→capture 불필요).
- **bi-temporal**: 청구 확정 시점의 거래 집합을 lock(as-of 스냅샷). 이후 도착하는 지연 거래/환불은 **다음 주기로 이월**.

### 4.3 후불 누적 · 조직 월청구 파이프라인

`① 주기 confirmed 거래 스냅샷 → ② 조직·부서·개인 집계 → ③ invoice draft → ④ 조정(환불·취소·분쟁) → ⑤ finalize(immutable) → ⑥ 세금계산서 발행(부가세 포함) → ⑦ 수납`. 청구액 = 조직 실사용액 + 플랫폼 수수료. **개인 자기부담금(한도 초과분)은 조직 청구에서 제외**.[³](#s3-src)

### 4.4 식당 정산(payout)

- 결제 즉시 식당 **payable 인식** → 주기(일/주/월) 실지급. 식당용 앱에서 기업별·기간별 매출·정산 예정/완료 실시간 조회.
- **수수료 부과 지점**: (a) 식당 정산 시 차감(payout=매출−수수료, 권장), (b) 조직 청구 가산, (c) 혼합. 원장상 수수료는 결제 시점에 플랫폼 수익계정으로 분개, payout은 net.
- **보류(rolling reserve)**: 분쟁·환불·이상거래·조직 미수 대비. `settled` vs `pending` 세그리게이션.

### 4.5 미수·연체(후불형 고유 신용위험)

후불은 TASPA가 **조직 신용위험(credit exposure)** 을 부담한다. 조직별 여신한도(credit limit), 연체 aging(30/60/90일), 서비스 정지, **지급보증/자동이체 동의 표준화**. 국내 자영업 시장에서 미수금이 실제 리스크로 지적된다.[³](#s3-src)

### 4.6 취소·환불·부분취소

- **취소(Void, 청구 전)**: 원자적 예산 원복, `jti`-거래 voided, reversal 거래행. 월중 취소 대부분.
- **환불(Refund, 청구 후)**: 다음 청구주기 크레딧 조정, 식당 정산도 차기 차감.
- **원 QR 토큰 재사용 금지**: 환불은 임직원 QR이 아니라 **원거래 `authId` 기준**으로 가맹점/관리자(인증 필요)가 개시. 환불마다 별도 idempotency key로 이중 크레딧 방지.

### 4.7 3-way 대사

`(a) 거래 원장 ↔ (b) 조직 청구서(수납) ↔ (c) 식당 payout(은행 실지급)` + 세무축(세금계산서 발행액) 교차검증. 2-way(내부↔정산)만으로는 "실제 은행 도달"이 미확인 → 3번째 leg(은행)로 settlement certainty. 매칭: 정규화 → 결정적 ID 매칭 → 확률적 매칭(부분·분할·지연·수수료 net-out) → 예외 워크플로.[³](#s3-src)

<a name="s3-src"></a>
> **각주 ³ (§4 출처)** 이중부기: Formance https://www.formance.com/blog/financial-operations/ledger-balance-for-product-and-engineering · SDK.finance https://sdk.finance/blog/what-is-a-double-entry-ledger-in-fintech/ · Stripe holds https://stripe.com/resources/more/authorization-holds-explained · 대사: Juspay Hyperswitch Reconciliation Architecture(3-way) · Trio Payment Reconciliation Guide · 환불/취소: Akurateco https://akurateco.com/blog/three-types-of-payment-reversals · Heartland refund vs reversal · PG 규제 §7 참조

---

## 5. 한도 / 정책 엔진 (Entitlement)

### 5.1 정책 계층 · 결제 컨텍스트 라우팅

```
 Org (조직/청구 대상)
  └─ CostCenter (부서/그룹)       ← 상속·오버라이드
      └─ Member (개인)
 개인은 여러 조직 소속 → 결제 컨텍스트 = (member_id, org_id, policy_id)
 QR 스캔 시점에 "어느 조직 지갑/정책으로 결제할지" 라우팅이 핵심 (멀티테넌트 멤버십)
```

### 5.2 규칙 차원 (국내 3사 실제 정책 준용)

| 축 | 내용 | 예시 |
|---|---|---|
| **금액 한도** | 월(필수)·1일·1회·끼니별(조/중/석) | 월 20만, 일 1만 |
| **시간창(meal window)** | 30분 단위 다수 구간, 평일/토/공휴일 ON·OFF | 평일 11:00~13:00 & 17:00~22:00 |
| **횟수** | 1일 1식/N식 제한 | 하루 1회만 |
| **사용처** | 제휴 화이트리스트, 카테고리(식당 vs 편의점/카페 — **비과세 인정 직결**), 반경/지오펜스 | 식당만 비과세 |
| **함께결제(split)** | 4~10인 합산, 결제자 정책 기준 차감 | — |

### 5.3 리셋 시맨틱 · 집행 아키텍처

- **비이월이 표준**: 일 한도는 잔액이 아니라 **rate-limit 카운터**(익일 회수). 이월 여부는 정책 플래그. 월 한도는 조직 청구 상한(cap)으로도 작동.
- **집행**: authorize 시점에 `(a) 시간창·요일 검사 → (b) 잔여 일/월/회 한도 원자 차감(reserve) → (c) 초과분 자동 거절 또는 개인부담 분리`. **정책 평가(순수 함수)** 와 **카운터 차감(원장 hold)** 을 분리하되 **하나의 원자 트랜잭션**으로 커밋(이중 사용 방지). 정책은 버전드(`effective_from/to`)로 감사 추적.
- **주의**: 초과분 개인카드 폴백을 지원하면 순수 폐쇄루프를 벗어나 **PG 필요성이 생긴다**(§7). MVP는 초과 시 단순 거절 권장.

<a name="s5-src"></a>
> **각주 (§5 출처)** 비플식권 한도형 도입 가이드 https://mealzeropay.gitbook.io/guide/admin/2./1. · 식권대장 사용정책(일 한도·식사시간) https://icanbuja.com/entry/식권대장-사용법과-사용처-사내복지 · Corpay card controls https://www.corpay.com/resources/blog/card-controls-spend-policies

---

## 6. 데이터 모델 초안 (핵심 테이블)

> taspa 내(인증) 테이블은 **[T]**, 별도 서비스 테이블은 **[S]** 표기. 개인 참조키는 **이메일이 아니라 `users.id`(sub UUID)** 를 저장한다.

### 6.1 조직·소속 (taspa 내, ADR 0002 대안 C)

```
[T] organizations                    -- 1급 테넌트(=청구 대상). SSO 없이 QR만 쓰는 조직도 표현
    id UUID PK, name, biz_reg_no(사업자번호), status, credit_limit, created_at

[T] org_memberships                  -- 다중소속·부서·역할의 진실 원천 (명시 행)
    id UUID PK, user_id FK→users, org_id FK→organizations,
    role(EMPLOYEE|ORG_ADMIN|ORG_FINANCE), department, status,
    UNIQUE(user_id, org_id)

[T] sso_connections (기존)            -- organization의 인증연동 하위요소로 종속(org_id FK 추가)
[T] sso_domains (기존)                -- 도메인→조직 매핑(신규가입 자동소속 힌트, 최종은 org_membership)
```

### 6.2 식당 디렉터리 (별도 서비스)

```
[S] merchants
    id UUID PK, name, biz_reg_no, category(RESTAURANT|CONVENIENCE|CAFE),
    status(PENDING|ACTIVE|SUSPENDED), payout_bank_account, fee_rate, geo(lat,lng)
[S] merchant_memberships
    id PK, user_id(=taspa sub), merchant_id FK, role(MERCHANT_OWNER|MERCHANT_STAFF)
[S] pos_terminals
    id PK, merchant_id FK, terminal_key, mode(CPM|MPM), last_seen_at
```

### 6.3 한도/정책 (별도 서비스, 버전드)

```
[S] policies
    id PK, org_id, cost_center_id NULL, effective_from, effective_to, version, active
[S] policy_rules
    id PK, policy_id FK, dimension(AMOUNT_MONTH|AMOUNT_DAY|AMOUNT_TXN|MEAL_WINDOW|
             COUNT_PER_DAY|MERCHANT_WHITELIST|CATEGORY|GEOFENCE), config JSONB, rollover BOOL
[S] entitlement_counters                 -- rate-limit 카운터(비이월)
    id PK, membership_id, period(DAY|MONTH), window_key, used_amount, used_count, reset_at
```

### 6.4 거래 (별도 서비스)

```
[S] transactions
    id UUID PK, auth_id UNIQUE, sub(user UUID), org_id, merchant_id, pos_terminal_id,
    amount_minor BIGINT, status(APPROVED|DECLINED|VOIDED|REFUNDED),
    jti UNIQUE,                          -- QR 토큰 1회성 소비
    pos_txn_id, UNIQUE(merchant_id, pos_txn_id),   -- 멱등키
    self_paid_minor,                     -- 한도 초과 개인부담분(조직청구 제외)
    created_at, meal_window_matched
[S] nonce_registry                       -- jti check-and-set (원자적 소비)
    jti PK, consumed_at
```

### 6.5 원장 (별도 불변 저장소)

```
[S] ledger_accounts
    id PK, type(ORG_AR|MERCHANT_AP|PLATFORM_FEE_REVENUE|CASH), owner_ref(org|merchant), currency
[S] ledger_entries                       -- append-only, 이중부기(zero-sum)
    id PK, journal_id, account_id FK, direction(DEBIT|CREDIT), amount_minor BIGINT,
    txn_ref(→transactions.auth_id), posting_type(PAYMENT|BILLING|SETTLEMENT|REVERSAL),
    as_of_date, recorded_at
    -- 불변: 수정/삭제 금지, 정정은 역분개 posting
```

### 6.6 청구·정산 (별도 서비스)

```
[S] invoices
    id PK, org_id, cycle(YYYY-MM), status(DRAFT|FINALIZED|PAID),
    subtotal_minor, fee_minor, vat_minor, total_minor, finalized_at, tax_invoice_no
[S] invoice_lines
    id PK, invoice_id FK, department, membership_id, txn_count, amount_minor
[S] payouts
    id PK, merchant_id, cycle, gross_minor, fee_minor, net_minor,
    status(PENDING|HELD|PAID), bank_transfer_ref, reserve_minor, paid_at
[S] reconciliation_exceptions
    id PK, leg(TXN_VS_INVOICE|INVOICE_VS_BANK|PAYOUT_VS_BANK), ref, delta_minor, status
```

---

## 7. 보안 · 규제 체크리스트

### 7.1 보안 (QR·인증·인가)

- [ ] **QR = 인증(taspa 신원·MFA)과 인가(서버 정책·원장)를 잇는 단명 전달체일 뿐, 신뢰의 근원이 아니다.** 서명·단일사용·기기바인딩·서버 인가가 네 겹으로 겹칠 것.
- [ ] QR 토큰은 **인증·비정지·조직소속 클레임을 가진 계정에만** 발급(taspa OIDC/MFA/passkey 전제 재사용).
- [ ] 초단명 `exp`(30~60초) + 단일사용 `jti` + 기기/세션 바인딩 + 앱 잠금해제(생체) 후 노출.
- [ ] 금액·잔여한도·PII를 QR에 평문 금지 → 불투명 핸들 또는 최소 클레임 서명 토큰(금융위 표준 정합).
- [ ] POS↔TASPA TLS 강제, `aud` 바인딩, nonce.
- [ ] **기기 분실**: taspa 원격 세션 로그아웃/전세션 종료로 토큰 발급 즉시 차단(발급은 라이브 세션 필요). 비밀번호 재설정 시 전 세션·신뢰기기 폐기(기존 taspa 동작).
- [ ] **가맹점 사기**: 서버측 금액 확정 + 가맹점 적격성/건별·일별 캡 + 실시간 영수 통지 + 분쟁 홀드백 + 정기 대사.
- [ ] 고액 결제는 taspa **step-up 재인증** 게이트.
- [ ] MPM 사용 시 매장 QR 서명 + 앱 검증 + "가맹점명+금액" 확정 표시(quishing 방지).

### 7.2 전자금융거래법 (가장 결정적 리스크)

- [ ] **PG 등록 트리거**: 플랫폼이 "재화·용역 대가의 정산을 대행·매개"하면 PG 등록 필수. TASPA가 개인 결제대금을 받아 식당에 재정산 = **정면 해당 소지**. 미등록 재정산은 전금법 §49 형사처벌(3년 이하/2천만원 이하).
- [ ] **완화책(MVP)**: 외부 전문 PG/에스크로사에 실제 자금정산 위탁, TASPA는 입점사 계좌정보·거래·청구 정보만 운영 → **등록 예외**. 신용카드 가맹점 정산은 여전법으로 규율되어 별도 등록 불요.
- [ ] **후불형이 규제상 이점**: 개인 선불충전이 없어 "선불전자지급수단 발행·관리업" 이슈를 상당 부분 회피(선불충전금 100% 별도관리 의무 등 회피). 선불 모델을 절대 도입하지 말 것.
- [ ] **여신 성격화**: 조직 후불 미수를 "선불전자지급수단"이 아니라 **상거래 외상매출**로 성격화되도록 계약·자금흐름 설계.
- [ ] PG 등록 로드맵(규모 확대 시): 자본금 3억+, 부채비율 200% 이내, 전산인력 5인+, 백업·정보보호시스템.
- [ ] **반드시 국내 금융 규제 전문 법률자문 병행**.

### 7.3 세무 (식대 비과세·세금계산서)

- [ ] **비과세 요건(월 20만, 2026 기준)**: (a) 음식업자와 **식사제공 계약** + (b) **현금환급 불가 식권**(폐쇄루프)이어야 비과세. → 편의점·커피숍 사용은 비과세 불인정 → **사용처 카테고리 통제(§5)와 직결**.
- [ ] **세금계산서 위탁발행**: 위탁판매 구조에서 TASPA가 조직 앞 **월 1건 세금계산서 위탁발행** → 조직은 법인세 손금산입 + 부가세 10% 매입세액공제. (개별 식당 정산·개별 계산서 수취 대체 = 식권대장 핵심 효익.)
- [ ] 원장 메타데이터로 **비과세 20만 초과분·비인정 사용처를 과세 분리 태깅** → 연말정산·원천징수 데이터 산출.
- [ ] 적격증빙 3종(세금계산서·신용카드매출전표·현금영수증) 관리, 수수료 매출 부가세 처리.
- [ ] **대사 연계**: 세금계산서 발행액 ↔ 원장 청구액 ↔ 실제 정산액 일치 검증.

### 7.4 개인정보

- [ ] QR·URL·쿼리스트링에 개인·신용정보 평문 배제(금융위 표준).
- [ ] 결제/거래 서비스는 자체 신원 저장소를 두지 않고 **taspa 토큰 클레임(sub/org_id/merchant_id)만** 참조(중복 최소화).
- [ ] 거래 이력·위치(지오펜스) 수집 최소화·목적 구속·보존기간.

<a name="s7-src"></a>
> **각주 (§7 출처)** 금융위 "정산 관여 시 PG 등록" https://fsc.go.kr/no010102/82523 · 선불충전금 100% 보호 https://fsc.go.kr/no010101/83004 · 율촌 PG 규제정비 · 김·장 전금법 개정 · 국세청 식대 상담사례 https://call.nts.go.kr/call/qna/selectQnaInfo.do · 클로브 모바일식권 세금비교 https://clobe.ai/blog/meal-allowance-management-mobile-vs-corporate-card-vs-cash-tax-comparison · 금융위 QR 표준 https://www.fsc.go.kr/po010106/73398

---

## 8. 핵심 설계 결정 갈림길 · 권고

### 8.1 QR 방향 — **CPM 주 + MPM 보조**

| 선택지 | 장점 | 단점 | 권고 |
|---|---|---|---|
| CPM 온라인 | 서버 실시간 한도·정책, 금액 위조 방어, quishing 무관 | 상시 연결 필요 | **✅ 주 채널** |
| MPM(동적·서명) | 무인 키오스크·구내식당 | 스티커 교체 위협 | **보조 한정** |
| MPM(정적 인쇄) | 초저비용 | 위조·금액 위조 | **❌ 가치이전 금지** |

### 8.2 폐쇄루프 vs PG — **폐쇄루프 + 외부 PG 위탁 → 후일 PG 등록**

| 선택지 | 규제 | 권고 |
|---|---|---|
| 순수 폐쇄루프 + 자체정산 | PG 등록 트리거(대가정산 매개) | 자체정산은 등록 후에만 |
| **폐쇄루프 + 외부 PG/에스크로 위탁** | 등록 예외 활용 | **✅ MVP** |
| 선불충전 모델 | 선불전자지급수단 규제(100% 별도관리) | **❌ 채택 금지** |

> **권고**: 현금환급 불가 폐쇄루프(세무·선불 이슈 최소화) + 초기 외부 PG 위탁(라이선스 회피) + 규모 확대 시 PG 등록 로드맵.

### 8.3 원장 위치 — **별도 불변 저장소**

`audit_events`(자유 JSON, 보안 감사)는 금전 원장이 아니다. 마감·정합성·bi-temporal이 필요한 **append-only 이중부기 원장을 별도 서비스·DB로 신설**. **✅**

### 8.4 모놀리식 vs 서비스 분리 — **IdP는 taspa, 나머지는 서비스 분리(단, MVP는 모듈러 모놀리스 허용)**

| 선택지 | 권고 |
|---|---|
| 전부 taspa 흡수 | **❌** ADR 0001 경계 붕괴 |
| taspa(IdP) + 6개 마이크로서비스 즉시 분리 | 목표 아키텍처, 운영비용↑ |
| **taspa(IdP) + 식대 백엔드 1개(모듈러 모놀리스)** | **✅ MVP** — 컨텍스트는 논리 분리(패키지·스키마), 물리 분리는 트래픽 성장 시 |

> **권고**: 경계는 taspa/식대로 명확히 가르되, 식대 쪽은 MVP에서 **모듈러 모놀리스**(거래·원장·청구·정산·한도·식당을 논리 모듈로)로 시작해 나중에 서비스로 쪼갠다. taspa client_credentials(M2M) 토큰으로 서비스간 인증은 처음부터 준비.

### 8.5 QR 토큰 형식 — **온라인=불투명 핸들, 오프라인 여지=EC/EdDSA 단명 JWS**

RS256은 QR 밀도 문제 → 결제용 EC 키 프로파일 병행 또는 불투명 핸들. **✅**

---

## 9. taspa 자산 재사용 맵 · 신규 개발 범위

### 9.1 재사용 (그대로 / 소폭 확장)

| taspa 자산 | 파일 | 재사용 방식 |
|---|---|---|
| **신원(sub=UUID)** | `domain/user/User.kt`, `TokenCustomizerConfig.kt` L56-58 | 결제 주체 안정 키. 식당·거래·원장은 **이메일 아닌 sub UUID** 저장 |
| **서명키 스택** | `token/JwkStorageService.kt`(activeKid, 회전, purge), `JwkConfig.kt`(JWKS), `JwkRotationJob.kt` | **QR 결제토큰 서명/검증 전체 스택 재사용**(EC 키 프로파일만 추가) |
| **OIDC/M2M** | `oidc/AuthorizationServerConfig.kt`, `RegisteredClientConfig.kt` L65(client_credentials), `AdminClientService.kt` | 식당POS·정산·거래 백엔드 서비스간 인증 토큰 발급 |
| **조직 경계 원천** | `domain/sso/SsoConnection.kt`, `SsoDomain.kt`, `SsoConnectionService.kt`(도메인 검증·공개메일 blocklist) | "이 직원이 이 회사 소속" 1차 판정, 신규가입 자동소속 힌트 |
| **개인↔조직 엣지** | `domain/federation/FederatedIdentity.kt`(connection_id), `FederatedLoginSuccessHandler.kt` | JIT 프로비저닝 확장 → `org_memberships` upsert |
| **관리·감사·인가** | `admin/*`(changeRole/suspend/revokeSessions), `audit/AuditEventService.kt`, `login/LoginUserDetailsService.kt` | 관리콘솔·부트스트랩·보안감사 재사용(금전 원장은 별도) |
| **Resource Server 스타터** | `client/spring-boot-starter/TaspaResourceServerAutoConfiguration.kt` | 신규 식대 RS가 issuer-uri만으로 JWT 검증(단 authorities 변환기 추가 필요) |
| **세션·재인증** | README: 원격 로그아웃, step-up 재인증, passkey | 기기 분실 차단·고액 게이트 |

### 9.2 taspa에 추가할 최소 변경

- [ ] `organizations` + `org_memberships` 신설(ADR 0002 대안 C) — 1급 테넌트, 다중소속·부서·역할. `sso_connections`에 `org_id` FK 추가(SSO 없이 QR만 쓰는 조직 표현).
- [ ] JIT 프로비저닝 확장: SSO 로그인 성공 시 `org_memberships` upsert.
- [ ] 역할 확장: 전역 `role`은 `PLATFORM_ADMIN`/`USER` 최소 유지, 조직/식당 스코프 역할은 `org_memberships.role`/`merchant_memberships.role`로 분리(다대다).
- [ ] **`AdminClientService` ALLOWED_SCOPES 화이트리스트 확장**(현재 `{openid,profile,email}` 하드코딩, L212) → `meal.pay`, `merchant.*`, `settlement.*`, `billing.*`, `ledger.write` 추가. **← 클라이언트 등록 관점 1순위 수정 지점.**
- [ ] `TokenCustomizer` 확장: `authorizedScopes`+`registeredClient` 조건부로 `org_id`/`org_role`/`merchant_id` 클레임 발급(클라이언트 허용 역할 ∩ 사용자 보유 역할, 최소권한).
- [ ] **QR 결제토큰 발급 엔드포인트** 신설(로그인 세션 + 활성 org 컨텍스트 근거, 서명키·JWKS 재사용). EC/EdDSA 결제 키 프로파일 추가.

### 9.3 신규 개발(taspa 밖)

- 식당 디렉터리 서비스 / 한도(Entitlement) 서비스 / 거래 서비스 / **이중부기 원장 서비스** / 청구 서비스 / 정산 서비스.
- 식당 POS 앱(CPM 스캔·결제확정 버튼) / 직원 앱 QR 발급·영수 통지 / 조직 관리 콘솔(정책·청구) / 식당 관리 콘솔(매출·정산).
- 외부 PG/에스크로/펌뱅킹 연동.
- 리소스 서버 authorities 변환기(SCOPE_/org_role → GrantedAuthority) — 스타터에 부재.

### 9.4 알려진 제약(코드 근거)

- **즉시 회수 한계**: JWT 상태없음 → 역할/소속 변경이 토큰 만료까지 지연(ADR 0002 L87-89). 결제 승인처럼 민감한 판정은 **단수명 토큰 + introspection/서버측 한도조회 병행**.
- **키 회전 60초 캐시 + purgeExpired**: RETIRED 키 삭제 시 그 키 서명 토큰 전부 무효 → **QR은 반드시 초/분 단위 단수명**(결제 QR 특성과 다행히 일치).
- **mTLS 미지원**: 현재 CLIENT_SECRET_BASIC/NONE만 → M2M 기본은 client_credentials+secret. mTLS 필요 시 SAS 클라이언트 인증 확장 신규 작업.

<a name="taspa-src"></a>
> **각주 ⁶ (§9 출처, taspa 절대경로)** `server/src/main/kotlin/com/taspa/server/token/JwkStorageService.kt`, `JwkConfig.kt`, `TokenCustomizerConfig.kt` · `token`/`oidc/AuthorizationServerConfig.kt`, `RegisteredClientConfig.kt` · `admin/AdminClientService.kt`(ALLOWED_SCOPES L212) · `domain/user/User.kt`, `domain/sso/SsoConnection.kt`, `SsoDomain.kt`, `domain/federation/FederatedIdentity.kt` · `enterprise/SsoConnectionService.kt`, `federation/FederatedLoginSuccessHandler.kt` · `client/spring-boot-starter/TaspaResourceServerAutoConfiguration.kt` · `docs/adr/0001-central-idp-with-oidc.md`, `docs/adr/0002-granular-roles-and-groups.md`

---

## 10. 단계적 로드맵 (MVP → 확장)

### Phase 0 — 기반 (taspa 최소 확장)
- `organizations`/`org_memberships` 신설, `sso_connections.org_id` FK.
- `AdminClientService` ALLOWED_SCOPES 확장, `TokenCustomizer` org/role 클레임.
- EC 결제 키 프로파일 + QR 결제토큰 발급 엔드포인트.
- **법률/세무 자문 착수**(PG·비과세·위탁발행 구조 확정).

### Phase 1 — MVP (폐쇄루프 + 외부 PG 위탁, 모듈러 모놀리스)
- 온라인 **CPM** 결제(불투명 단명 핸들), 식당 POS "결제확정 버튼".
- 한도 엔진(월/일/1회 한도 + 식사시간창 + 화이트리스트), 초과 시 **단순 거절**.
- 이중부기 원장(결제 시 조직 미수 + 식당 미지급 동시 발생).
- 월말 조직 청구서 + **세금계산서 위탁발행 1건**, 외부 PG 위탁 식당 payout.
- 3-way 대사 리포트, 단일 조직·소수 식당 파일럿(수요 선점형 온보딩).

### Phase 2 — 확장
- 다중소속(파견/겸직), 부서별 예산 실시간 조회, ORG_FINANCE 역할.
- **MPM 보조 채널**(무인 키오스크·구내식당, 동적 서명 MPM).
- 무단말 온보딩(식신페이형), 편의점/프랜차이즈 제휴(단 비과세 태깅 분리).
- 부분취소·환불·rolling reserve, 연체 aging·여신관리.
- 이상탐지(속도·지리·가맹점 불일치), step-up 고액 게이트.

### Phase 3 — 스케일 · 규제 정식화
- **PG 등록**(자체정산 전환) 또는 여전법 경로 확정.
- 컨텍스트 물리 분리(마이크로서비스), 오프라인 floor-limit 승인.
- 오픈뱅킹/펌뱅킹 자동이체, 카드사 공통 QR·EMV 레일 브리징 검토.
- 다지역·다통화(글로벌 확장 시 minor unit·bi-temporal 원장이 기반).

```
 Phase0(기반)     Phase1(MVP)          Phase2(확장)         Phase3(스케일)
 taspa확장·자문 ─▶ CPM·한도·원장·후불청구 ─▶ 다중소속·MPM·무단말 ─▶ PG등록·MSA·오프라인
 [2~3주]          [파일럿 1조직]         [다조직·다식당]        [정식 규제·글로벌]
```

---

## 부록: 참조 표준 요약

| 표준 | 시사점 |
|---|---|
| **EMVCo QRCPS v1.1** (CPM/MPM 분리) | taspa `jti`+`exp` = EMV 크립토그램 신선도 + ATC 단조성의 소프트웨어 대응물. 카드 레일 브리징 시 TLV 참조 |
| **금융위 QR코드 결제 표준(2019)** | QR 내 민감정보 금지, 변동형 = 보안앱 발급 → 불투명/최소클레임 토큰 + taspa 인증 결합 |
| **제로페이** | 공동 QR 허브·저수수료 정산 패턴 = 다가맹점 네트워크 정산 참조(가치모델은 상이) |
| **카드사 공통 QR(2024~)** | 향후 상호운용/카드 결합 인터페이스 참조 |

---

*본 보고서의 규제·세무 서술은 공개 자료 기반이며, 시행 전 반드시 국내 금융 규제·세무 전문가의 원문(금융위·국세청 고시) 확인이 필요하다.*
