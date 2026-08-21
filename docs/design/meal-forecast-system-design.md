# TASPA 위 구내식당 식수(食數) 수요 예측 시스템 — 설계

> 출발점: TASPA(OIDC IdP + 조직소속 `sso_domains` + 식대결제 플랫폼) 위에, 결제/배식 **소비 이벤트를 정답데이터로** 삼는 식수 예측 시스템을 올린다.
> 선행 문서: 결제 플랫폼 설계 [meal-platform-system-design.md](./meal-platform-system-design.md) · 배경 리서치 [meal-platform-design.md](../research/meal-platform-design.md) · 경계 원칙 [ADR 0001](../adr/0001-central-idp-with-oidc.md) · 재실 모수 [ADR 0002](../adr/0002-granular-roles-and-groups.md).
> 예측 대상: **끼니 × 식당 × 일** 그레인의 (1) 총식수(총원) + (2) 메뉴별 선택 분포. 두 결정지평: 익일/주간 베이스라인(발주·조리량) + 당일 실시간 보정(노쇼·잔반).

---

## 1. 설계 명제 — 왜 예측이 TASPA의 다음 층인가

**한 줄 명제.** 결제 플랫폼이 만드는 **소비 이벤트 스트림이 곧 라벨된 정답데이터**다. TASPA는 이 정답을 *공짜로, 지속적으로, 개인·조직·메뉴 그레인으로* 생성한다. 예측 시스템은 이 데이터층 위에 얹는 **가치층**이다.

- **결제 = 데이터층 (진실의 생성기).** `[S] transactions`의 `status=APPROVED` 1건 = 실제 배식 1건. 별도 센서·설문 없이도 "언제·어느 식당·어느 끼니창·(메뉴태깅 시)어느 메뉴"가 원천에서 라벨로 확정된다. QR 발급 이벤트(발권했으나 미결제) − 실배식 = **노쇼 신호**, entitlement 예약/카운터 = **수요 예약 신호**. 즉 사전(발권)·실시간(결제)·사후(정산) 세 시점의 신호를 하나의 신뢰 원천에서 얻는다.
- **예측 = 가치층 (돈이 되는 층).** 데이터 자체는 비용이다. 이 데이터가 발주량·조리 배치량을 흔들어 **잔반·폐기·품절을 줄일 때** 비로소 가치가 실현된다. 학술·업계 실증은 이 가치가 실재함을 보인다: S시청 직원식당 사례는 경험 예측 오차 10~11% → ML 6~7%로 단축하며 **잔반 약 40% 감축, 연 약 5천만원 절감**을 보고했고[^s시청], 구내식당 ML 도입 연구들은 폐기 **14~52% 감소**를 보고한다[^opus][^mdpi].
- **왜 TASPA가 유리한가 — 경쟁사가 못 가진 3개 구조적 신호.**
  1. **개인 단위 소비 이력**(TASPA `sub`=UUID). 삼성웰스토리·누비랩은 비전 카메라로 *집계 잔반*만 사후 측정한다[^welstory][^nuvilab]. TASPA는 개인 참여확률을 학습해 **상향식(bottom-up) 총원 추정**과 개인화 분포까지 갈 수 있다(단, 프라이버시 경계 필수 — §7).
  2. **조직 인사·재실 모수**(`sso_domains`/`org_memberships`). DACON LH 우승 통찰의 핵심은 "실근무인원 = 정원 − (휴가+출장+재택+시간외)"라는 파생변수였다[^dacon][^brunch]. TASPA는 이 모수를 IdP 경계 안에서 **구조적으로** 얻는다 — 남들은 못 얻는 신호다.
  3. **발권-결제 갭 = 사전 노쇼 신호.** 비전 카메라는 잔반을 *먹은 뒤에* 안다. QR 발권/entitlement 예약은 *먹기 전에* 신호를 준다 → 당일 조리 배치를 미리 조정. 사전(TASPA)과 사후(비전, 선택적 연동)는 상호보완적이다.
- **분업 원칙.** 잔반 *계량*(그램 단위)은 성숙한 외부 비전 솔루션(누비랩 등)과 연동하고[^ourhome], TASPA는 **소비 이벤트 기반 식수예측 코어**에 집중한다. 아워홈×누비랩 제휴가 이 분업이 업계 표준임을 방증한다.

**색으로 읽는 경계** (선행 문서의 2색 체계를 계승·확장):
- **틸(TASPA)** — 신원·서명키·조직소속·M2M 인가. *이미 존재.*
- **퍼시먼(식대 결제)** — 식당·거래·원장·정산. *소비 이벤트를 생성. 정답 원천.*
- **세이지(예측)** — 피처스토어·학습·예측·발주연동. *본 문서에서 신규 개발. 결제와 별도 런타임(Python).*

---

## 2. 예측 문제 정의

### 2.1 대상 그레인과 2단계 × 2지평

예측 단위(그레인)는 **(끼니 × 식당 × 일)**. 한 예측 셀 = 예: `2026-07-20 / 본사 3층식당 / 중식`.

| | **지평 A: 익일/주간 베이스라인** (D-1 ~ D-7) | **지평 B: 당일 실시간 보정** (D-0, 나우캐스팅) |
|---|---|---|
| **결정(액션)** | 식자재 발주 · 최초 조리 배치량 | 후속 조리 배치(밥·국·즉석) 상향/하향, 노쇼·잔반 폐기 감소 |
| **정보집합** | 과거 이력, 캘린더, **날씨 예보**, 확정 메뉴, 사전 승인된 휴가/출장/재택/행사 | + 당일 실측: 오전 예약, 출입/재실, 실제 날씨, 초반 끼니 QR 소비율 |
| **비용 구조** | 리드타임 긺·되돌릴 수 없음 → **상위 분위수**(안전분 확보) | 배치 사이 재예측 가능 → **하향 보정**으로 폐기 최소화 |

- **Stage 1 — 끼니별 총식수(총원).** 회귀/시계열 문제. 핵심 재정의: 구내식당 총원은 순수 "수요"가 아니라 **재실 인원 × 식당 이용률(참여율)**이다. 따라서 아래처럼 분해해 모델링한다.
  ```
  총식수 = 재실_모집단  ×  참여율
           └─(인사·행사로 확정, 선행 관측)   └─(ML로 예측: 요일·메뉴·날씨·lag)
  ```
  모집단 변동(휴가·재택)과 성향 변동(메뉴가 좋아서 더 옴)을 분리하면 훨씬 안정적이다.
- **Stage 2 — 메뉴별 선택 분포.** 배식 형태에 따라 (a) 단일선택(A/B코스 택1 → share 합=1, multinomial) 또는 (b) 다중선택/셀프바(반찬별 take-rate 0~1). 어느 쪽이든 **Σ(메뉴별 예측) = 총식수 예측**의 정합성(coherence)이 발주 정확도의 관건.

### 2.2 출력 명세 (점예측이 아니라 분포)

각 예측 셀에 대해 **분위수 벡터**를 출력한다 — `{P10, P25, P50, P70, P90}` 등. 이유는 §4.3(newsvendor). 발주·조리 시스템은 항목별 임계분위수를 골라 소비한다.

### 2.3 스코프에서 명시적으로 제외/조건부

- 날씨는 **조건부 2차 보정**으로만. DACON 대회에서 체감기온 등 날씨 피처가 오히려 성능을 떨어뜨려 제외한 팀이 다수였다[^dacon]. 총원 수준은 캘린더·재실이 잡고, 날씨는 우천·폭염·한파·미세먼지 같은 **이벤트성 잔차**에만 투입한다(§3.2).
- 잔반 *그램 계량*은 외부 비전 연동(선택). 코어 라벨은 배식 카운트 + 식당 입력 조리량.

---

## 3. 데이터 · 피처 카탈로그

### 3.1 정답데이터(ground truth) — 소비 이벤트에서 라벨을 만든다

| 라벨 | 정의 | 원천 | 지평 |
|---|---|---|---|
| **총식수** | 끼니창 내 `status=APPROVED` transaction count (`meal_window_matched`) | `[S] transactions` | A·B |
| **메뉴별 식수** | 메뉴 태깅된 배식 분포(POS 라인/코너 or 메뉴마스터 조인) | transactions × menu master | A·B |
| **노쇼** | QR 발급/entitlement 예약 − 실배식 | taspa QR 발급 이벤트 − transactions | B |
| **잔반** | 조리량 − 실배식 (식당 입력/센서, 선택적 비전 연동) | 식당 콘솔 입력 / 외부 비전 | 피드백 |

- **라벨 위생.** `VOIDED`/`REFUNDED`는 라벨에서 제외. 지연도착(late-arriving) 이벤트는 **bi-temporal 재적재**로 과거 라벨을 정정(정산 지연·오프라인 배치 업로드 대비).
- **누수 방지 원칙.** 피처는 *예측 시점에 알 수 있는 값만*. D-1 예측엔 날씨 **예보**(실측 아님), 미래 예약은 컷오프까지만, lag는 예측지평만큼 떨어진 것만(익일=lag≥1, 주간=lag≥7).

### 3.2 요인별 피처 카탈로그

| 피처군 | 대표 피처 | 소스 | 지평 가용성 | 비고 |
|---|---|---|---|---|
| **① 캘린더** | 요일(요일×끼니 상호작용 필수), 공휴일 전후·**브릿지/샌드위치데이**, 공휴일까지 남은일수, 급여일±1·경과일수, 월중 근무일 순번, 월말 잔여근무일, (캠퍼스형)학기/시험/방학, 조직 고유 정기이벤트(예: 마지막 수요일 '문화가 있는 날'→석식 급감) | 한국천문연구원 특일정보 API, 조직 근무캘린더(테넌트별) | A·B (전부 사전확정) | **가장 강력·안정.** 조직별(sso_domains 테넌트) 캘린더 분리 관리 |
| **② 재실 인사** | **실근무인원 = 정원 − 휴가 − 출장 − 재택 (− 파견/교육)**, 재실률·휴가율·출장률·재택률, (석식)시간외근무 승인건수 | HRIS/근태·전자결재 → **집계만** 반입 | A(사전승인분 확정) + B(당일 조퇴·연차 갱신) | DACON 우승 핵심. 개인→집계는 **신뢰영역 안에서** 변환(§7) |
| **③ 메뉴 매력도** | 과거 평균 선택률(베이지안 스무딩), 최근 N회 이동평균, **직전 제공일 경과(recency)**·최근4주 유사메뉴 제공횟수(포화도), 신메뉴 플래그, 카테고리(한/양/중/일·밥/면/국물·단백질·조리법·매운맛·칼로리), **킬러메뉴 사전**(돈까스·제육·치킨…), 특식/명절 플래그 | 식단관리시스템 + TASPA 소비이력(집계) | A·B (메뉴는 사전확정) | 대학사례 일 177~536명(3배) 편차의 주범 |
| **④ 행사·이벤트** | 이벤트 여부·유형(회식/워크숍/교육/특식)·영향끼니·**추정 참석규모**·영향부서, 이벤트 전일/익일 | 그룹웨어·전자결재·회의실/차량예약 + 경량 등록 UI | A(사전확정분) + B(당일 취소/추가) | 규모를 **재실모집단에서 차감**하여 인사피처와 일관 통합 |
| **⑤ 날씨(조건부)** | 기온·기온²(U자), 체감온도, 강수(점심시간대 국지 강수), 미세먼지 예보등급(PM10/2.5), 폭염·한파·호우 특보 | 기상청 단기예보 API(발주용 **예보**), 초단기실황(당일 보정용 **실측**), 에어코리아 | A=예보, B=실측 | **요일×날씨·계절×메뉴** 상호작용으로 국소화. 부호가 조직·계절마다 반대 |
| **⑥ lag/rolling** | **lag_7·14·21(동요일)**, lag_1, rolling mean/median 7·28·56일, **동요일 최근4~8주 중앙값**, EWMA, 변동계수, 전주대비 증감률, 추세항 | TASPA 소비이력 시계열 | 지평만큼 떨어진 것만 | 참여율에 적용(모집단 분리). median/트림평균으로 이벤트 이상치 강건화 |
| **⑦ 메뉴 share 이력** | 메뉴별 과거 take-rate, 코너 점유율, 메뉴 임베딩(콜드스타트용) | 소비이력(메뉴 태깅) | A·B | Stage 2 입력 |

**소스 신뢰/운영 주의.** 기상청 단기예보는 5km 격자·1일 8회(02·05·08·11·14·17·20·23시)·1시간 단위이므로 사업장 위경도→격자(nx,ny) 매핑 + 발표지연·결측 폴백(전일예보/평년값) 필요[^kma]. 날씨·급여일의 **부호(방향)**는 일반 외식과 구내식당(저가·편의 채널)에서 반대일 수 있어 조직 데이터로 검증한다.

### 3.3 피처 → 모델 흐름 (ASCII)

```
 [정답 원천]                    [외생 소스]
 transactions(APPROVED) ──┐     한국천문연 특일 API ─┐
 QR발급/entitlement 예약 ──┤     기상청/에어코리아 ───┤
                          │     HRIS·전자결재(집계) ─┤
                          ▼                          ▼
        ┌───────────────────────────────────────────────────┐
        │   피처 엔지니어링 (offline·online 단일 정의)        │
        │  ①캘린더 ②재실인사 ③메뉴매력도 ④행사 ⑤날씨 ⑥lag ⑦share │
        └───────────────────────────────────────────────────┘
              │  point-in-time correct join (누수 차단)
              ▼
     ┌─────────────────────┐        ┌──────────────────────────┐
     │ 재실_모집단 (확정)   │        │  참여율 모델 (LightGBM     │
     │ = 정원-휴가-출장-재택│──× ────▶│  quantile) + 계절 baseline │
     └─────────────────────┘        │  앙상블  → Stage1 총식수    │
                                     └──────────┬───────────────┘
                                                │ (분위수 벡터)
                                                ▼
                              ┌──────────────────────────────────┐
                              │ Stage2 메뉴 take-rate (LightGBM,   │
                              │ 메뉴속성/임베딩·recency 피처)      │
                              └──────────────┬───────────────────┘
                                             ▼
                              ┌──────────────────────────────────┐
                              │ MinT reconciliation               │
                              │ Σ(메뉴별) ≡ 총식수 (정합/coherent) │
                              └──────────────┬───────────────────┘
                                             ▼
                            분위수 예측(총원 + 메뉴별) → 발주/조리
```

---

## 4. 모델 설계

### 4.1 Stage 1 — 총식수: 트리부스팅 주력 + 시계열 베이스라인 앙상블

- **주 모델: LightGBM (quantile objective).** 문제를 테이블 회귀로 재구성(§3.2 피처). 비선형·다수 외생변수·결측에 강하고, **여러 식당·여러 끼니를 하나의 글로벌 모델**에 넣되 `식당ID·끼니`를 범주형으로 부여. M5 상위권이 전부 LightGBM인 것이 실무 우위의 방증이며[^m5], 실제 카페테리아 게이트데이터 사례에서 XGBoost 일단위 MAE≈16.23·MAPE≈8.32%로 최고였다[^opus]. 범주형(메뉴 텍스트 인덱스 등)이 많으면 CatBoost도 후보 — DACON에서 CatBoost가 대체로 최고였다[^dacon].
- **점심/석식 분리.** 패턴이 크게 다르다. DACON 상위권은 예외 없이 중식/석식을 별도 타깃으로 학습했다[^dacon]. 우리는 끼니를 범주형으로 넣되 **끼니×요일·끼니×야근** 상호작용을 명시하고, 석식엔 시간외근무 승인건수를 전용 선행지표로 투입한다.
- **베이스라인 겸 앙상블 요소: seasonal-naive(전주 동요일) + Prophet.** Prophet은 holiday regressor로 공휴일·연휴를 쉽게·해석가능하게 처리하나 정확도는 트리부스팅에 열세[^mdpi_forecast] → **장기지평 안정성/폴백**과 앙상블 다양성 용도. seasonal-naive는 반드시 이겨야 할 최소 베이스라인(§6).
- **분포 가정.** 식수는 카운트다. 저볼륨 셀·간헐 특식은 Poisson·음이항(NegBin)·ZIP 우도가 적합 — 사원식당 메뉴별 수요를 음이항 GAM으로 다룬 연구가 최robust로 보고했다[^posch]. 트리 quantile과 병용/대안.
- **추세 외삽 한계 보정.** 트리는 학습구간 밖 추세를 외삽 못한다. 신규 사업장 성장·재택 확대에 따른 구조적 증감은 **재실모집단 피처**로 흡수시키고, 필요 시 detrend 후 잔차를 학습한다.

### 4.2 Stage 2 — 메뉴배분: 계층적 예측 + Reconciliation

- **뼈대: 계층적 예측 + MinT reconciliation.** 총원(상위)과 메뉴별(하위)을 각각 예측한 뒤 coherent하게 조정한다. 실용 출발점은 "**총원을 강하게 예측 → 코스 선택비율로 top-down 배분**", 여기에 **MinT(Minimum Trace)**로 정합화 — MinT는 오차공분산 trace 최소화로 이론적으로 base보다 나쁘지 않음이 보장되고 BU/TD를 능가한다[^mint]. 분위수까지 정합화하려면 **coherent probabilistic reconciliation**(계층 관계 하 분위수 동시 조정)[^srqf].
- **take-rate 모델.** per-menu LightGBM으로 각 메뉴의 take-rate를 예측(피처: 메뉴 속성/임베딩, recency, 포화도, 날씨×메뉴 상호작용). 단일선택 구조면 **softmax/MNL share 레이어**를 대안으로 — 단, "복잡한 통합모델보다 기능영역별 단순 MNL 분리추정이 예측타당도에서 자주 우수"하므로 과설계를 경계한다[^menuchoice].
- **메뉴 임베딩·매력도.** 구내식당 메뉴는 매일 로테이션되어 "같은 아이템 시계열"이 성립하지 않는 게 근본 난점. 메뉴를 **밥/국/주찬/부찬 성분으로 분해**해 속성·임베딩 벡터로 표현하고, 과거 선택으로 학습한 **매력도 점수**를 take-rate 모델의 피처로 투입 → 처음 보는 조합도 일반화. 임베딩은 처음엔 사전기반 태깅+과거선택률로 시작하고, 데이터가 쌓이면 도메인(식단) 코퍼스 word2vec/FastText로 승급 — 도메인 임베딩이 범용보다 우수하다[^caviar]. 단 데이터가 작을 땐 인덱싱 원핫이 임베딩보다 나을 수 있다(DACON 경험)[^brunch].

### 4.3 분위수 예측 → 발주 안전재고 (Newsvendor)

**프레이밍: 이것은 신문팔이(newsvendor) 문제다.** 최적 발주/조리량 = 수요분포의 **임계분위수** α = Cu/(Cu+Co) = 부족비용/(부족비용+폐기비용). 비대칭 비용에서 최적 점예측은 평균이 아니라 특정 분위수다[^gneiting]. "예측 후 안전재고 더하기"가 아니라 **요구 서비스레벨에 해당하는 분위수를 직접 예측**한다.

- **항목별 α 차등 (핵심 설계).**

  | 항목 유형 | 예 | 권장 α(분위수) | 근거 |
  |---|---|---|---|
  | 주식·저가·상온보관·재사용 가능 | 밥·국·김치·상온 식자재 | **높음 ≈0.9+** | 품절이 비싸고 폐기비용 낮음 |
  | 총식수 발주 기준선 | 끼니 총원 | **P60~P70** | 균형점 |
  | 고가·부패성·재사용 불가 완제품 | 프리미엄 단백질, 조리완료 특식 | **낮음** | 폐기가 비싸고 ESG 비용 |

- **산출 방법.** 목표 분위수마다 LightGBM 1개(pinball/quantile loss). M5 Uncertainty 우승이 정확히 이 방식(집계수준·분위수별 LightGBM + rolling 피처 + 표본증강, 9개 분위수 0.005…0.995)[^m5]. 분포무관 커버리지 보장은 conformal prediction으로 보정.
- **최적화 레이어.** 단순형=각 항목의 α-분위수 예측치를 그대로 발주. 정교형=실제 Cu/Co로 예측분포 위에서 **기대비용 최소화**, 공유 식자재(한 재료가 여러 메뉴)·유통기한·배치크기 제약 결합. **주의:** 최말단 메뉴는 랜덤성이 커 벤치마크 대비 개선폭이 급감하므로(M5: 레벨1 44%→레벨12 2%) 상위에서 top-down 배분이 유리할 수 있다[^m5].

### 4.4 콜드스타트

- **신규 식당.** 글로벌 모델의 **transfer/zero-shot** — 유사 식당(규모·업종·인원구성)으로부터 계층적 차용. 초기엔 seasonal-naive/유사식당 평균으로 시작 → 실측 누적에 따라 Bayesian prior + fast update로 빠르게 개인화.
- **신메뉴.** content-based/analogous forecasting — 속성·임베딩으로 표현해 **유사 메뉴의 take-rate를 차용**, 카테고리 평균으로 shrinkage. 드문 특식/이벤트 메뉴는 간헐수요(Croston/TSB, ZIP)로 처리[^posch].

### 4.5 2지평 결합 — Base + Correction 2모델 계층

```
 D-1 야간                                   D-0 당일 (컷오프마다 재추정)
 ┌────────────────────────┐        online 신호: 사전예약·출입/재실·초반 QR소비율
 │ Base 모델 (LightGBM     │                   │
 │  quantile, 예보날씨)    │                   ▼
 │  → 발주 + 1차 조리 배치 │        ┌────────────────────────────┐
 └───────────┬────────────┘        │ Correction/nowcast 모델      │
             │ base 예측 저장       │  입력: online 피처            │
             └─────────────────────▶│  출력: base 대비 승수 또는    │
                                    │        잔차 (전량 재예측 X)   │
                                    │  제약: online 신호 없으면      │
                                    │        정확히 base로 수렴      │
                                    │        (consistency)          │
                                    └───────────┬──────────────────┘
                                                ▼
                                   후속 조리 배치(밥·국·즉석) 상향/하향
```

- **역할 분담.** 발주는 되돌릴 수 없으므로 D-1은 **비대칭비용 반영 상위 분위수**로, 조리량은 D-0에서 **하향 보정**해 폐기 최소화. "오전 10시 실측을 반영한 예측이 3일 전 예측보다 항상 정보우위"라는 pickup/proportion-of-day 원리(콜센터·호텔 revenue mgmt 차용)[^living]. 상태공간/칼만필터로 재적합 없이 순차 갱신도 가능[^kalman].
- **consistency 제약**(핵심): online 정보가 없을 때 correction이 정확히 base로 수렴하도록 설계 → 두 지평이 서로 모순되지 않음. 잔차/승수 외삽 방식이라 cold-start·안정성 확보(전량 재예측 회피).

---

## 5. 시스템 아키텍처

### 5.1 런타임 분리 — Python ML 서비스 vs JVM 백엔드

ML 생태계는 Python, TASPA·결제 백엔드는 JVM(Kotlin/Spring). **언어·런타임을 분리한다**: JVM=인증·결제·거래·발주, Python=학습·예측. 둘은 **TASPA M2M(client_credentials) 토큰으로 인증된 REST/이벤트**로만 연계한다. TASPA가 언어중립 OIDC 경계를 제공하므로 ADR 0001의 "IdP 경계 + Resource Server는 토큰만 검증" 원칙을 그대로 확장한다. **예측서비스를 TASPA/결제 모놀리스에 흡수하지 않는다**(경계 유지).

### 5.2 전체 아키텍처 (ASCII)

```
┌──── TASPA (틸, 재사용) ────┐   ┌──── 식대 결제 (퍼시먼) ────┐   ┌──────── 예측 (세이지, 신규 · Python) ────────┐
│ IdP: users(sub=UUID)       │   │ transactions [S]           │   │                                              │
│ JWK 서명/JWKS/회전         │   │  (sub,org_id,merchant_id,  │   │  ┌────────────────────────────────────────┐  │
│ M2M client_credentials     │   │   meal_window_matched,     │   │  │ 수집 2경로 (단일 피처정의로 skew 제거)   │  │
│ sso_domains/connections    │   │   status,created_at)       │   │  │  · 배치 ELT(야간): raw→정제→offline피처   │  │
│ org_memberships (모수)     │   │ QR발급/entitlement 예약    │   │  │  · 스트리밍(당일): outbox/CDC→Kafka       │  │
│ ALLOWED_SCOPES(확장지점)   │   │ merchants·menu master      │   │  └───────────────┬────────────────────────┘  │
└──────────┬─────────────────┘   └──────────┬─────────────────┘   │                  ▼                            │
           │  M2M 토큰                       │  소비 이벤트         │  ┌────────────────────────────────────────┐  │
           │  (meal.consumption.read /       │  (pull API / Kafka)  │  │ 피처스토어 (offline+online, 단일 view)    │  │
           │   meal.forecast.read|write)     │─────────────────────▶│  │  offline=Parquet/DW(point-in-time,학습)  │  │
           └─────────────────────────────────┘                      │  │  online=Redis(저지연,서빙)               │  │
                                                                     │  └───────┬──────────────────┬─────────────┘  │
                                                                     │          ▼                  ▼                │
                                                                     │  ┌──────────────┐   ┌──────────────────────┐ │
                                                                     │  │ 학습          │   │ 서빙 2경로            │ │
                                                                     │  │ (주기+드리프트│   │ · 배치예측(야간)      │ │
                                                                     │  │  트리거)      │   │   → prediction store  │ │
                                                                     │  │ MLflow 레지스트리 │   │ · 당일 온라인 보정 API │ │
                                                                     │  │ DVC 데이터버전 │   │   (FastAPI/BentoML)   │ │
                                                                     │  └──────┬───────┘   └───────────┬──────────┘ │
                                                                     │         │ 백테스트             │              │
                                                                     │         │ (rolling-origin)     │              │
                                                                     └─────────┼──────────────────────┼─────────────┘
                                                                               ▼                      ▼
                                              ┌───────────────────────────────────────────────────────────────┐
                                              │ prediction store (Postgres/DW): 끼니×식당×일 분위수 + 메뉴별   │
                                              └───────────────────────────┬───────────────────────────────────┘
                                                                          │  taspa RS 스타터(issuer-uri JWT검증)
                                          ┌───────────────────────────────┼───────────────────────────────┐
                                          ▼                               ▼                               ▼
                                   조리량 산정·식자재 발주        식당 콘솔 / 영양사 도구            혼잡도·운영 대시보드
                                   (JVM 클라이언트)               (override·가드레일 UI)

   [피드백 루프]  끼니 종료 → 실배식·잔반·노쇼 라벨 확정 → 예측 vs 실제 자동 조인 → 편차/bias 산출
                  → 드리프트 감지(PSI/KS, 개념드리프트) → 임계 초과 시 재학습 트리거 + 알림 → 학습으로 환류
```

### 5.3 수집 — 학습-서빙 왜곡(skew) 제거

별도 배치·스트리밍 파이프라인은 학습-서빙 왜곡을 부른다 — DoorDash 이중 파이프라인에서 **35.7% 피처 불일치**, 프로덕션 모델 40%가 영향받았다[^skew]. 따라서 **피처 변환 로직을 단일 정의**로 공유한다(Feast on-demand/streaming transform 또는 Tecton).

- **배치(야간 ELT).** transactions·QR발급·인사(집계)·메뉴마스터·행사·날씨 → raw→정제→offline 피처. **point-in-time correct** 조인.
- **스트리밍(당일).** 결제/배식 이벤트를 결제 DB **outbox/CDC → Kafka/Kinesis** → online 피처(현재까지 배식 누적, 노쇼율, 잔여시간 대비 배식 페이스)를 초·분 단위 갱신 → 당일 보정 서빙에 공급.

### 5.4 학습 · 서빙 · 레지스트리

- **학습(주기 재학습).** 주 1회 정기 배치 + 드리프트/계절·메뉴개편 트리거 시 즉시. **MLflow** 모델 레지스트리에 버전·메트릭·데이터 스냅샷·**피처정의 해시** 기록, staging→prod 승격 게이트, 원클릭 롤백. 데이터/피처 버전은 **DVC**. 매번 전체 재학습은 대개 불필요 — 점진적 업데이트 + recency weighting의 **비용인지형(cost-aware) 선택 갱신**이 낫다[^retrain].
- **서빙 2경로.** ① 익일/주간 배치예측을 야간에 산출해 prediction store(Postgres/DW)에 기록(안정·기본값). ② 당일 온라인 보정 API가 online 피처로 **잔차/승수 보정**(전량 재예측 아님). **챔피언/챌린저 + shadow + 카나리**로 신모델 안전 검증.

### 5.5 피드백 루프 · 드리프트 · 휴먼인더루프

- **라벨 환류.** 끼니 종료 후 실배식·잔반·노쇼가 라벨로 확정 → 예측 vs 실제 자동 조인 → 편차·bias 산출. 지연도착은 bi-temporal 재계산.
- **드리프트 3종.** ① 데이터 드리프트(피처 분포 이동, **PSI>0.2** 시 조사), ② 예측 드리프트, ③ 개념 드리프트(동일 피처→다른 식수: 재택정책·메뉴선호 변화). **예측 드리프트 3일 연속 임계 초과 또는 MAPE 임계 초과 시 자동 재학습 트리거+알림**(Evidently/whylogs). 성능 저하 시 baseline 폴백[^drift]. **핵심 원칙: 알려진 원인은 드리프트가 아니라 피처로** — 회식·정책변경·물가변동을 외생 플래그로 명시 투입해 모델이 "설명"하게 하고 드리프트로 오인하지 않게 한다.
- **휴먼인더루프(영양사/조리관리자).** (a) **Override**: 행사·특별메뉴·현장지식으로 예측치 조정 → 별도 기록되어 당일 조리 즉시 반영 + 재학습 시 보정신호로 환류. (b) **가드레일**: 이상예측(전주 동요일 대비 ±X% 이탈, 극단 분위)은 사람 확인 게이트를 거쳐야 발주 반영. (c) override 빈도·방향을 대시보드에 노출해 모델이 체계적으로 틀리는 구간을 식별. 미등록 이벤트로 인한 급락은 잔반/노쇼 실시간 신호로 사후 감지해 **이벤트 캘린더에 피드백(능동학습)**.
- **정확도 대시보드.** 끼니·식당·조직·메뉴 축으로 MAPE/MAE·**bias**·잔반율·품절건수·서비스레벨 달성률·예측구간 커버리지·노쇼율 추이. 운영지표는 Prometheus/Grafana. **베이스라인 대비 개선폭을 상시 노출**(§6).

---

## 6. 평가 · 베이스라인 · 백테스트

- **점예측 지표.** MAE·RMSE 기본. 저카운트 메뉴는 MAPE 불안정 → **WAPE/가중MAPE**(메뉴 집계), **MASE**(scale-free, 여러 식당/메뉴 교차비교 — MASE<1이 최소 합격선). 비대칭 현실 반영 위해 **실제 비용(원)으로 환산한 오차**도 병행 보고.
- **확률예측 지표.** pinball/quantile loss, **WSPL(M5)**, **CRPS**, 구간 커버리지·calibration(reliability diagram).
- **운영/비즈니스 KPI (최종 판정 지표).** 잔반율·폐기율·결품율·서비스레벨 달성률·식자재 비용·예측오차→비용 환산. **통계지표가 아니라 이 KPI로 성과를 판정**한다(S시청 잔반 40%↓·연 5천만원 절감이 ROI 논거[^s시청]).
- **백테스트.** **rolling-origin / expanding-window** 시계열 CV(예측 원점을 시간축으로 전진, 여러 원점 오차 평균)[^tscv]. **무작위 split 금지** — DACON에서 Public 1위가 Private 51위로 추락한 사례가 시간기준 검증·리크 방지의 필요성을 보인다[^dacon]. **결정지평별 분리 평가**(D-1 발주 vs D-0 나우캐스트를 각 정보집합에서). 공휴일·행사·계절을 가로지르는 다수 원점 확보.
- **반드시 이겨야 할 베이스라인 (정직한 평가의 핵심).** ① seasonal-naive(전주 동요일), ② 이동평균, ③ 작년 동요일/동일자, ④ 메뉴수준=마지막 제공 시 take-rate, ⑤ **현행 수기 예측(영양사 경험치)**. 모델이 seasonal-naive(MASE<1)와 수기 베이스라인을 유의하게 이기지 못하면 **도입 정당성 없음**. 개선폭을 상시 대시보드화.

---

## 7. 프라이버시 · 규제 — 개인 메뉴선택·휴가 = 민감정보

**민감성 판정.** 개인 메뉴선택 이력(sub 단위 식습관 → 건강·종교·기호 유추 가능)과 인사정보(휴가·재택·출장 → 근태·건강 유추 가능)는 민감정보. 수요예측은 이 데이터를 **개인 식별 형태로 쓰지 않는다.**

- **집계만 사용 (privacy-by-design).** 학습·서빙 피처는 **끼니×식당×조직×메뉴 카운트** 등 집계로만. 재실은 "개인 김OO 휴가"가 아니라 **조직/부서 재실률**로만 반입. 개인→집계 변환은 **인사시스템 경계 안(신뢰영역)**에서 수행하고 예측 서버로는 숫자 카운트만 전달.
- **개인화 추천과 분리(목적구속).** 개인 선호를 쓰는 개인화 추천(별도 제공 시)과 수요예측을 **데이터·목적·저장소로 분리**. 개인선호는 추천 도메인에만, 예측엔 익명 집계만.
- **최소수집.** 예측서비스는 TASPA PII를 저장하지 않는다. `TokenCustomizerConfig`가 access_token엔 `sub`/`scope`만 싣고 PII(email 등)는 id_token에만 싣는 원칙을 계승 — 예측서비스는 sub 원문 대신 **조직·식당·끼니·메뉴 차원 집계만 보관**.
- **가명화·익명 조인.** 개인 라벨 조인이 필요해도 `sub`(이미 이메일과 분리된 가명 UUID)로만. **QR 소비 이벤트를 인사 집계와 조인할 때 개인키 조인 금지 — 사업장×날짜×끼니 그레인에서만 조인**해 재식별 차단.
- **재식별 방지.** 소집단(부서·소인원 사업장)에서 특정 개인의 배식·휴가가 재식별될 수 있으므로 **k-익명(최소셀 k<5 억제/롤업/노이즈)** 강제. 필요시 차분 프라이버시.
- **거버넌스.** 목적구속·보존기간 명시, 근태 원천은 집계 산출 후 원천 미보관·집계 스냅샷만 학습저장소에 보존, 별도 감사 로깅. TASPA `audit_events`(보안감사)와 예측 데이터 저장소는 **분리**(AuditEvent 분리 원칙과 정합).
- **로드맵 함의.** 1단계는 집계 피처+재실모수로 베이스라인 구축 → **개인 참여확률·개인 선호는 동의·거버넌스 확보 후 신뢰영역 내부에서만** 산출해 집계로 환원. 개인화의 한계이득이 프라이버시 비용을 넘는지 사업장별로 판단.

---

## 8. TASPA 자산 재사용 맵 · 신규 개발

| 재사용 (그대로/소폭 확장) | 코드 근거(절대경로) | 신규 개발 (세이지·예측) |
|---|---|---|
| 정답데이터 = 소비 이벤트 | `docs/research/meal-platform-design.md` `[S] transactions` | 피처스토어(offline+online 단일 정의) |
| 조직소속 = 재실 모수 | `server/.../domain/sso/SsoDomain.kt`·`SsoConnection.kt` + ADR 0002 `org_memberships`(대안 C) | 학습 파이프라인·MLflow/DVC |
| 인사연동 = 재실률 피처 | `server/.../federation/FederatedLoginSuccessHandler.kt`(JIT 확장→org_memberships upsert), `domain/federation/FederatedIdentity.kt`(connection_id=개인↔조직 엣지) | 배치예측 + 당일 온라인 보정 API |
| M2M 인증 = 서비스간 | `server/.../oidc/RegisteredClientConfig.kt` L65 CLIENT_CREDENTIALS | prediction store(Postgres/DW) |
| 예측 API = Resource Server | `client/spring-boot-starter/.../TaspaResourceServerAutoConfiguration.kt`(issuer-uri만으로 JWT 검증) | 드리프트 모니터링·피드백 루프 |
| 서명·검증 스택 | `server/.../token/JwkStorageService.kt`(activeKid/JWKS/회전) | 영양사 override·가드레일 콘솔 |
| 최소수집·가명 원칙 | `server/.../token/TokenCustomizerConfig.kt`(sub=UUID L58, PII는 id_token만) | 이벤트 등록 경량 UI |

**TASPA 1순위 수정 지점** (코드 근거로 확인):
1. **`AdminClientService.ALLOWED_SCOPES`** (`server/.../admin/AdminClientService.kt` L212)가 `{openid, profile, email}` **하드코딩** → `meal.consumption.read`(라벨 읽기)·`meal.forecast.read|write`로 최소권한 화이트리스트 확장. *(확인: L212 `private val ALLOWED_SCOPES = setOf(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL)`)*
2. **`TokenCustomizerConfig`**가 scope 기반으로 `org_id` 등 org 클레임을 access_token에 발급하도록 확장(ADR 0002 대안 A/C의 "클라이언트 허용 ∩ 사용자 보유" 최소권한).
3. 예측서비스를 **M2M client_credentials 클라이언트**로 등록(`RegisteredClientConfig` 패턴).
4. **`org_memberships` 신설**(ADR 0002 대안 C) — "이 끼니에 이 식당 소속 조직원 N명"의 재실 모수.

*결제 설계의 1순위 수정 지점(`meal.pay`·`merchant.*` scope 확장)과 동일 파일·동일 메커니즘을 공유하므로, 결제·예측 scope를 함께 화이트리스트에 추가하는 것이 효율적이다.*

---

## 9. 단계적 로드맵

| 단계 | 산출물 | 이기는 목표 | TASPA 의존 |
|---|---|---|---|
| **P0 — 규칙기반 베이스라인** | 전주 동요일 + 재실모수 보정 규칙, 정확도 대시보드, 백테스트 하네스 | seasonal-naive·수기 예측 대비 측정 시작 | 소비 이벤트 read scope, org_memberships 초안 |
| **P1 — ML 총원(Stage 1)** | LightGBM quantile 총식수(끼니 분리) + Prophet/seasonal-naive 앙상블, D-1 배치예측 → prediction store | seasonal-naive(MASE<1) 유의 우위 | M2M scope 확장, 재실률 피처(집계) |
| **P2 — 메뉴배분(Stage 2)** | per-menu take-rate + MinT reconciliation, 메뉴 속성/임베딩, 신메뉴 콜드스타트 | 메뉴수준 "마지막 take-rate" 베이스라인 우위, coherence 보장 | menu master 조인 |
| **P3 — 당일 보정(D-0)** | 스트리밍 online 피처(배식 페이스·노쇼율) + correction/nowcast 모델(consistency 제약) | D-1 대비 폐기·품절 KPI 개선 | outbox/CDC→Kafka, entitlement 예약 신호 |
| **P4 — 개인화(선택, 거버넌스 후)** | 개인 참여확률 bottom-up·개인 선호(신뢰영역 내부, 집계 환원) | 집계 모델 대비 한계이득이 프라이버시 비용 초과 시에만 채택 | 동의·k-익명·차분프라이버시 |

**원칙: 각 단계는 이전 베이스라인을 유의하게 이겨야 다음으로 간다.** 발주·조리 연동(하류 가치사슬)은 P1부터 prediction store를 통해 열어두되, 자동발주 전환은 KPI가 수기 대비 개선을 입증한 후.

---

## 부록. 핵심 설계 결정 요약 (비타협)

1. **총식수 = 재실모집단 × 참여율**로 분해 — 모집단 변동과 성향 변동 분리.
2. **끼니 분리 학습** + 글로벌 모델(식당·끼니 범주형).
3. **분위수 예측(newsvendor α 항목별 차등)** — 점예측 금지, 발주는 분포에서 소비.
4. **Σ메뉴 ≡ 총원 정합(MinT)** — 발주 정확도의 관건.
5. **Base+Correction consistency** — online 없으면 base로 수렴.
6. **단일 피처정의(offline=online)** — 학습-서빙 skew 원천 차단.
7. **집계만·가명·k-익명·그레인 조인** — 개인정보 경계 비타협.
8. **Python 예측서비스 ≠ JVM 백엔드** — M2M 토큰 경계로 연계, 모놀리스 흡수 금지.
9. **KPI(잔반·폐기·품절)로 판정**, 통계지표는 중간 신호.

---

### 출처

[^dacon]: DACON '구내식당 식수 인원 예측 AI 경진대회'(LH 주최, 정형 회귀, MAE). Private 1위 MAE 83.12. 실근무인원 파생·끼니 분리·CatBoost·K-fold. https://dacon.io/competitions/official/235743/overview/description
[^brunch]: DACON 참가 후기 — 실근무인원=정원−(야근+재택+출장+휴가), 메뉴 FastText vs 밥/국/반찬 인덱싱, '문화가 있는 날' 석식 0명. https://brunch.co.kr/@tobesoft-ai/17
[^s시청]: 기계학습을 활용한 집단급식소(S시청) 식수 예측 — 오차 10~11%→7%대, 잔반 40%↓·연 5천만원 절감. 대한영양사협회지. https://koreascience.or.kr/article/JAKO201912261946958.page
[^opus]: Machine Learning Techniques for Cafeteria Demand Forecasting — XGBoost 일단위 MAE≈16.23·MAPE≈8.32%, 잔반 6.2%↓. https://dergipark.org.tr/en/pub/opusjsr/article/1649256
[^mdpi]: Reducing Food Waste in Campus Dining, MDPI Sustainability 17(2):379. https://www.mdpi.com/2071-1050/17/2/379
[^mdpi_forecast]: Comparing Prophet and Deep Learning to ARIMA, MDPI Forecasting 3(3):40. https://www.mdpi.com/2571-9394/3/3/40
[^posch]: Posch et al., A Bayesian Approach for Predicting Food and Beverage Sales in Staff Canteens — 음이항 GAM, 다중 계절성·이상치. Int. J. Forecasting 2022. https://arxiv.org/abs/2005.12647
[^m5]: The M5 Uncertainty competition: results/findings — 분위수별 LightGBM 우승, WSPL, 하위집계 개선폭 급감. https://www.sciencedirect.com/science/article/pii/S0169207021001722
[^mint]: Wickramasuriya, Athanasopoulos, Hyndman (2019), MinT reconciliation. https://metricgate.com/docs/hierarchical-forecast-reconciliation-mint/
[^srqf]: Simultaneously Reconciled Quantile Forecasting of Hierarchically Related Time Series. https://arxiv.org/pdf/2102.12612
[^menuchoice]: Menu-Based Choice Models — 단순 MNL 분리추정 우위. https://www.sciencedirect.com/science/article/abs/pii/S1094996820301018
[^caviar]: Caviar/Square 메뉴 word2vec 태깅 — 도메인 코퍼스 임베딩 우수. https://developer.squareup.com/blog/caviars-word2vec-tagging-for-menu-item-recommendations/
[^gneiting]: Gneiting, Quantiles as optimal point forecasts — 비대칭 비용에서 최적 점예측=분위수. https://www.researchgate.net/publication/222339257
[^living]: The Living Forecast: Day-Ahead → Intraday. https://arxiv.org/html/2510.12271v2
[^kalman]: Kalman Filter for Time Series Forecasting. https://forecastegy.com/posts/kalman-filter-for-time-series-forecasting-in-python/
[^skew]: Eliminate training-serving skew(DoorDash 35.7% 불일치)·Feast/Tecton. https://www.confluent.io/blog/eliminate-training-serving-skew-mlops/
[^drift]: 드리프트 모니터링·자동 재학습(PSI>0.2, 3일 연속 임계). https://www.evidentlyai.com/ml-in-production/data-drift
[^retrain]: On the retraining frequency of global models in retail demand forecasting. https://arxiv.org/pdf/2505.00356
[^tscv]: Hyndman, Time series cross-validation / rolling origin, FPP3 §5.10. https://otexts.com/fpp3/tscv.html
[^kma]: 기상청 단기예보 조회서비스(공공데이터포털) 5km 격자·1일 8회. https://www.data.go.kr/data/15084084/openapi.do · 에어코리아 미세먼지 https://www.airkorea.or.kr/web/board/1/387/
[^welstory]: 삼성웰스토리 잔반분석 AI(퇴식구 카메라, 수집정확도 90%). https://www.sedaily.com/NewsView/29LOMG6DQ3
[^nuvilab]: 누비랩 AI Food Scanner 3.0 — 분석정확도 98%·리텐션 95%. https://www.koreaherald.com/article/3254032
[^ourhome]: 아워홈×누비랩 AI 급식관리(급식 대기업의 푸드테크 제휴 표준 패턴). https://www.joongangenews.com/news/articleView.html?idxno=487238

---

*본 문서의 TASPA 파일 근거(절대경로)는 §8 표에 명시. 규제·프라이버시 서술은 공개자료 기반이며 시행 전 국내 개인정보·세무·금융 규제 전문가 확인 필수. TASPA 코드 인용은 읽기전용 확인분(예: `AdminClientService.kt` L212 `ALLOWED_SCOPES`, `TokenCustomizerConfig.kt` sub=UUID·PII는 id_token만, `RegisteredClientConfig.kt` CLIENT_CREDENTIALS).*
