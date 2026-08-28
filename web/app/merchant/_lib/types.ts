/**
 * 가맹 관리자 콘솔이 쓰는 서버 계약 미러.
 *
 * 필드 이름은 서버 DTO(`meal/dto/MerchantConsoleDtos.kt`)와 1:1 이다 — 이름이 어긋나면 컴파일은 통과하고
 * 런타임에 조용히 `undefined` 가 되므로 추측하지 않는다.
 *
 * ★이 화면들이 다루는 데이터에는 **손님 개인 식별자가 없다**. 서버 DTO 에 userId·이메일·이름 자리가
 * 아예 없기 때문이며(설계된 제약), 화면도 그 경계를 넘는 필드를 만들어내지 않는다.
 */

/** 내가 관리하는 가맹점(멤버십 ACTIVE ∧ 매장 ACTIVE) — 콘솔에 **들어갈 수 있는** 매장. */
export interface MyMerchant {
  merchantId: string;
  name: string;
  category: string;
  status: string;
  /** 이 매장의 하루 경계 앵커. 조직 타임존을 빌려 쓸 수 없어 매장마다 따로 갖는다. */
  timezone: string;
  role: string;
}

/**
 * 관리자로 지정돼 있지만 **매장 상태 때문에** 지금은 열 수 없는 가맹점.
 *
 * 링크로 그리지 않는다 — 진입 집합에 없으므로 누르면 403 이다. 목적은 "지정은 됐는데 화면에 아무것도
 * 없다"는 침묵을 없애는 것이지 접근을 여는 것이 아니다.
 */
export interface BlockedMerchant {
  merchantId: string;
  name: string;
  /** PENDING(아직 활성화 전) | SUSPENDED(정지). */
  status: string;
}

/** GET /api/merchant-console/mine */
export interface MyMerchantsResponse {
  merchants: MyMerchant[];
  blocked: BlockedMerchant[];
}

/** 거래 로그 1행. 금액은 전부 minor 단위 정수(KRW = 원). */
export interface MerchantTransaction {
  /** 승인 식별자 — 매장 자신의 대사(對査) 키다(손님 정보가 아니다). */
  authId: string;
  /** POS 가 보낸 거래번호(멱등키). */
  posTxnId: string;
  /** 결제한 손님이 소속된 조직. 조직이 삭제된 과거 거래는 null. */
  orgName: string | null;
  mealWindow: string;
  amountMinor: number;
  /** 조직 부담(= 청구 대상) = amountMinor − selfPaidMinor. */
  orgPaidMinor: number;
  /** 한도 초과분 개인 부담. */
  selfPaidMinor: number;
  status: string;
  approvedAt: string;
  voidedAt: string | null;

  /**
   * 환불 누계와 주머니별 분담, 그리고 환불 전 원금.
   *
   * ★부분 환불은 `amountMinor`·`selfPaidMinor` 를 **소급 변경**한다. 원금과 환불액이 함께 있어야 매장이
   * 자기 POS 기록·영수증과 맞춰볼 수 있다. `refundCount` 는 여러 번 나눠 환불한 건을 한 건으로
   * 착각하지 않게 한다.
   */
  refundedMinor: number;
  orgRefundedMinor: number;
  selfRefundedMinor: number;
  originalAmountMinor: number;
  refundCount: number;
  lastRefundedAt: string | null;
}

/**
 * GET /api/merchant-console/{id}/transactions
 *
 * `from`/`to` 는 **실제 조회에 쓰인** 매장-로컬 날짜, `requestedFrom`/`requestedTo` 는 요청값 그대로다.
 * 상한에 걸려 좁혀졌으면 `windowTruncated`(기간)·`rowsTruncated`(행 수)로 드러난다 — 화면은 이 사실을
 * 반드시 표시한다(부분 데이터를 전부인 것처럼 보여주지 않는다).
 */
export interface MerchantTransactionsResponse {
  merchantId: string;
  timezone: string;
  from: string;
  to: string;
  requestedFrom: string;
  requestedTo: string;
  windowTruncated: boolean;
  limit: number;
  rowsTruncated: boolean;
  rows: MerchantTransaction[];
}

/**
 * 예측 산출 근거. 조직 예측과 달리 재실 인원(headcount) 항목이 없다 — 매장에는 "재실 인원"이라는
 * 모수 자체가 존재하지 않기 때문이다(불특정 다수 손님).
 */
export interface MerchantForecastBasis {
  /** 전주 동요일 실적(인분). 4주 평균 폴백이면 null. */
  lastWeekActual: number | null;
  /** 폴백 평균에 실제로 쓰인 주 수(전주 동요일=1, 4주 평균=1~3, 데이터 없음=0). */
  sampleWeeks: number;
}

/** (날짜 × 끼니) 예측 셀. `predicted === null` 은 **0 인분이 아니라 "데이터 없음"** 이다. */
/**
 * 조직 분해 한 조각 — 매장 총 예측은 이 조각들의 합이고, 캘린더·연차 신호는 조각 단위로 적용된다
 * (매장 총합에 A 조직 휴일을 곱하면 B 조직 손님까지 깎이기 때문).
 */
export interface MerchantOrgSlice {
  orgId: string;
  orgName: string;
  predicted: number | null;
  method: string;
  holiday: boolean;
  holidayName: string | null;
  event: boolean;
  eventName: string | null;
  absentWeight: number;
}

export interface MerchantForecastCell {
  date: string;
  mealWindow: string;
  predicted: number | null;
  method: string;
  basis: MerchantForecastBasis;
  orgs?: MerchantOrgSlice[];
  /** 일부 조직 근거 없음 — predicted 는 아는 조직의 합(하한)이다. */
  partial?: boolean;
  /** 매장-로컬 오늘 셀에서 지금까지 이미 나간 인분(nowcast 하한의 근거). 다른 날은 없다. */
  soFar?: number | null;
}

/** GET /api/merchant-console/{id}/forecast — from 미지정 시 매장-로컬 내일부터 7일. */
export interface MerchantForecastResponse {
  merchantId: string;
  timezone: string;
  from: string;
  to: string;
  requestedFrom: string;
  requestedTo: string;
  windowTruncated: boolean;
  mealWindow: string | null;
  cells: MerchantForecastCell[];
  orgs?: MerchantOrgInfo[];
}

/** 이 매장을 이용 중인 조직 한 줄(실적순 정렬). */
export interface MerchantOrgInfo {
  orgId: string;
  name: string;
  recentPortions: number;
  upcomingHolidays: number;
  upcomingEvents: number;
  upcomingAbsentWeight: number;
}

/** 백테스트 셀 — "그 시점에 예측했을 값" vs 실적. 실적이 없는 셀의 actual 은 0 이다. */
export interface MerchantBacktestCell extends MerchantForecastCell {
  actual: number;
}

/** 백테스트 요약 지표(조직 예측과 같은 정의를 재사용 — 해석이 화면마다 갈리지 않게). */
export interface BacktestSummary {
  cells: number;
  scoredCells: number;
  /** 실적이 0 인 셀은 분모에서 제외되며 그 개수가 mapeExcludedZeroActual 이다. */
  mape: number | null;
  mapeExcludedZeroActual: number;
  wape: number | null;
  /** 양수 = 과대예측(잔반 위험), 음수 = 과소예측(품절 위험). */
  bias: number | null;
}

/** GET /api/merchant-console/{id}/backtest — 어제까지의 과거 구간만 평가한다. */
export interface MerchantBacktestResponse {
  merchantId: string;
  timezone: string;
  from: string;
  to: string;
  requestedFrom: string;
  requestedTo: string;
  windowTruncated: boolean;
  mealWindow: string | null;
  cells: MerchantBacktestCell[];
  summary: BacktestSummary;
}

/** 정산 명세의 조직별 한 줄 — `MerchantSettlementLine`. */
export interface MerchantSettlementLine {
  orgId: string;
  orgName: string | null;
  approvedCount: number;
  /** 이 조직 손님의 결제 중 **조직이 부담**하는 금액 = 우리가 매장에 줄 몫. */
  orgPaidMinor: number;
  /** 손님이 계산대에서 **이미 낸** 금액. 우리가 줄 돈이 아니다. */
  selfPaidMinor: number;
  refundedMinor: number;
}

/**
 * GET /api/merchant-console/{id}/settlement — `MerchantSettlementView`.
 *
 * ★`payableMinor` 만이 taspa 가 매장에 지급할 금액이다. `selfPaidTotalMinor` 는 손님이 계산대에서 직접
 * 낸 돈이라 **매장이 이미 받았다** — 둘을 더해 보여주면 매장은 받을 돈을 두 배로 기대한다.
 * 창은 **매장 타임존** 월 경계라 조직 청구서와 경계일 거래만큼 정당하게 다를 수 있다.
 */
export interface MerchantSettlement {
  merchantId: string;
  merchantName: string;
  period: string;
  timezone: string;
  periodStart: string;
  periodEnd: string;
  approvedCount: number;
  voidedCount: number;
  payableMinor: number;
  selfPaidTotalMinor: number;
  refundedTotalMinor: number;
  lines: MerchantSettlementLine[];
}

/** GET /forecast/cell — 셀 하나의 근거 상세(taspa MerchantCellDetail 미러). */
export interface MerchantBasisPoint {
  date: string;
  actual: number;
}

export interface MerchantOrgSliceDetail {
  slice: MerchantOrgSlice;
  basis: MerchantBasisPoint[];
  headcount: number | null;
}

export interface MerchantMenuShare {
  name: string;
  corner: string | null;
  category: string;
  plannedPortions: number | null;
  share: number | null;
  predicted: number | null;
  sampleQuantity: number;
}

export interface MerchantCellDetail {
  date: string;
  mealWindow: string;
  timezone: string;
  cell: MerchantForecastCell;
  orgs: MerchantOrgSliceDetail[];
  menus: MerchantMenuShare[];
  menuLearnFrom: string | null;
  menuLearnTo: string | null;
}
