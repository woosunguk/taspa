import type { MerchantTransaction } from "./types";

/** 식수 로그 화면의 합계 카드. 금액은 전부 minor 단위 정수(KRW = 원). */
export interface Totals {
  approvedCount: number;
  voidedCount: number;
  amount: number;
  orgPaid: number;
  selfPaid: number;
  /** 환불 누계. `amount` 는 **이미 환불이 반영된** 값이라, 이 값이 없으면 왜 줄었는지 알 수 없다. */
  refunded: number;
  refundedCount: number;
}

/**
 * 합계는 **불러온 행 기준**이다 — 상한에 걸리면 그 사실을 화면이 함께 말한다.
 *
 * ★**금액축은 APPROVED 만, 환불축은 상태 무관**이다 — 서버 집계
 * (`MealRepositories.aggregateMerchantSettlementByOrg`)와 정확히 같은 규칙이어야 한다.
 *
 * 전액 환불은 거래를 VOIDED 로 수렴시키므로(`MealRedeemService.refund`), VOIDED 에서 곧바로
 * 빠져나가면 그 환불이 합계에서 통째로 사라진다: 10,000 전액 + 3,000 부분 환불이 있던 창에서
 * 요약 카드는 "환불 3,000원"을 말하는데 **바로 아래 표에는 10,000원 환불 행이 그대로 보이고**,
 * 같은 매장의 정산 명세는 13,000원을 말한다 — 한 앱이 같은 기간에 대해 세 가지를 주장한다.
 * `refundedMinor` 는 환불만이 올리므로(순수 취소는 0), 상태를 가리지 않고 더하는 것이 정확히
 * "되돌아간 금액"이다.
 *
 * 순수 함수로 떼어 둔 이유: 이 규칙이 화면 렌더링과 얽히면 회귀 테스트를 쓸 수 없다.
 */
export function summarize(rows: MerchantTransaction[]): Totals {
  return rows.reduce<Totals>(
    (acc, row) => {
      // 환불축 — 상태 무관(early return 보다 **먼저** 누적해야 한다).
      if (row.refundedMinor > 0) {
        acc.refunded += row.refundedMinor;
        acc.refundedCount += 1;
      }
      if (row.status === "VOIDED") {
        acc.voidedCount += 1;
        return acc;
      }
      // 금액축 — APPROVED 만.
      acc.approvedCount += 1;
      acc.amount += row.amountMinor;
      acc.orgPaid += row.orgPaidMinor;
      acc.selfPaid += row.selfPaidMinor;
      return acc;
    },
    { approvedCount: 0, voidedCount: 0, amount: 0, orgPaid: 0, selfPaid: 0, refunded: 0, refundedCount: 0 },
  );
}
