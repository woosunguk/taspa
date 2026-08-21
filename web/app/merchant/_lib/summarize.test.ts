import { describe, expect, it } from "vitest";
import { summarize } from "./summarize";
import type { MerchantTransaction } from "./types";

/**
 * 식수 로그 합계.
 *
 * ★서버(`aggregateMerchantSettlementByOrg`)와 **같은 규칙**이어야 한다: 금액축은 APPROVED 만,
 * 환불축은 상태 무관. 전액 환불이 거래를 VOIDED 로 수렴시키므로 이 규칙을 어기면 같은 앱이
 * 같은 기간에 대해 세 가지 환불액을 주장한다(요약 카드 / 표의 행 / 정산 명세).
 */
function row(overrides: Partial<MerchantTransaction>): MerchantTransaction {
  return {
    authId: "a",
    posTxnId: "p",
    orgName: "회사",
    mealWindow: "LUNCH",
    amountMinor: 0,
    orgPaidMinor: 0,
    selfPaidMinor: 0,
    status: "APPROVED",
    approvedAt: "2026-07-15T03:00:00Z",
    voidedAt: null,
    refundedMinor: 0,
    orgRefundedMinor: 0,
    selfRefundedMinor: 0,
    originalAmountMinor: 0,
    refundCount: 0,
    lastRefundedAt: null,
    ...overrides,
  };
}

describe("summarize", () => {
  it("★전액 환불(VOIDED 수렴)도 환불 합계에 남는다", () => {
    // 10,000 전액 환불(→VOIDED) + 8,000 중 3,000 부분 환불(→APPROVED, 잔액 5,000).
    const totals = summarize([
      row({
        status: "VOIDED",
        amountMinor: 0,
        refundedMinor: 10000,
        originalAmountMinor: 10000,
        refundCount: 1,
      }),
      row({
        amountMinor: 5000,
        orgPaidMinor: 5000,
        refundedMinor: 3000,
        originalAmountMinor: 8000,
        refundCount: 1,
      }),
    ]);

    // 환불축 — 상태 무관. 13,000 이 아니면 정산 명세와 갈린다.
    expect(totals.refunded).toBe(13000);
    expect(totals.refundedCount).toBe(2);
    // 금액축 — APPROVED 만.
    expect(totals.approvedCount).toBe(1);
    expect(totals.voidedCount).toBe(1);
    expect(totals.amount).toBe(5000);
    expect(totals.orgPaid).toBe(5000);
  });

  it("환불이 전액 환불 하나뿐이어도 합계가 0 이 되지 않는다", () => {
    // 이 케이스가 더 나쁘다 — totals.refunded===0 이면 화면이 환불 카드를 아예 렌더하지 않아,
    // 표에는 10,000원 환불 행이 보이는데 요약에는 환불 항목 자체가 사라진다.
    const totals = summarize([
      row({ status: "VOIDED", amountMinor: 0, refundedMinor: 10000, originalAmountMinor: 10000 }),
    ]);

    expect(totals.refunded).toBe(10000);
    expect(totals.refundedCount).toBe(1);
  });

  it("순수 취소는 환불 합계를 올리지 않는다(대조군)", () => {
    // refundedMinor 는 환불만이 올린다 — 이 대조군이 없으면 위 테스트가 "VOIDED 를 다 더한다"와
    // 구별되지 않는다.
    const totals = summarize([
      row({ status: "VOIDED", amountMinor: 9000, originalAmountMinor: 9000 }),
      row({ amountMinor: 7000, orgPaidMinor: 7000, originalAmountMinor: 7000 }),
    ]);

    expect(totals.refunded).toBe(0);
    expect(totals.refundedCount).toBe(0);
    expect(totals.voidedCount).toBe(1);
    expect(totals.approvedCount).toBe(1);
    expect(totals.amount).toBe(7000);
  });
});
