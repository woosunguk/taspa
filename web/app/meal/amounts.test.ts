import { describe, expect, it } from "vitest";
import { rowAmounts } from "./amounts";
import type { MealTransaction } from "./types";

/**
 * 사용내역 한 줄의 숫자 규칙.
 *
 * ★핵심은 **전액 환불**이다. 그 경로는 거래를 VOIDED 로 만들면서 amountMinor·selfPaidMinor 를 0 으로
 * 되돌리므로, 환불 표시를 "취소가 아닐 때"에만 두면 15,000원을 쓰고 3,000원을 현금으로 돌려받은
 * 사람의 이력이 "0원 · 취소됨" 한 줄로 남는다 — 돈이 실제로 오간 거래일수록 더 많이 사라진다.
 */
function tx(overrides: Partial<MealTransaction>): MealTransaction {
  return {
    authId: "a",
    orgId: "o",
    merchantName: "구내식당",
    amountMinor: 0,
    selfPaidMinor: 0,
    mealWindow: "LUNCH",
    status: "APPROVED",
    approvedAt: "2026-07-15T03:00:00Z",
    voidedAt: null,
    refundedMinor: 0,
    selfRefundedMinor: 0,
    originalAmountMinor: 0,
    lastRefundedAt: null,
    ...overrides,
  };
}

describe("rowAmounts", () => {
  it("★전액 환불은 원금을 보여주고 환불 정보를 숨기지 않는다", () => {
    // 15,000 결제(조직 12,000 + 개인 3,000) → 전액 환불 → VOIDED, 금액 0, 개인에게 3,000 현금 반환.
    const shown = rowAmounts(
      tx({
        status: "VOIDED",
        amountMinor: 0,
        selfPaidMinor: 0,
        refundedMinor: 15000,
        selfRefundedMinor: 3000,
        originalAmountMinor: 15000,
        voidedAt: "2026-07-15T04:00:00Z",
        lastRefundedAt: "2026-07-15T04:00:00Z",
      }),
    );

    // 0원이 아니라 원금을 말한다 — 0원은 사용자에게 아무 뜻도 아니다.
    expect(shown.headlineMinor).toBe(15000);
    expect(shown.showRefund).toBe(true);
    expect(shown.showVoidedBadge).toBe(true);
    // 청구되는 금액이 없으므로 분담은 말하지 않고, 남은 금액도 없다.
    expect(shown.showSplit).toBe(false);
    expect(shown.remainingMinor).toBeNull();
  });

  it("부분 환불은 원금과 남은 금액을 함께 말한다", () => {
    const shown = rowAmounts(
      tx({
        amountMinor: 12000,
        selfPaidMinor: 0,
        refundedMinor: 3000,
        selfRefundedMinor: 3000,
        originalAmountMinor: 15000,
        lastRefundedAt: "2026-07-15T04:00:00Z",
      }),
    );

    expect(shown.headlineMinor).toBe(15000);
    expect(shown.remainingMinor).toBe(12000);
    expect(shown.showRefund).toBe(true);
    expect(shown.showSplit).toBe(true);
    expect(shown.struck).toBe(false);
  });

  it("환불 없는 순수 취소는 원래 금액을 그대로 보여준다(대조군)", () => {
    // 이 대조군이 없으면 위 테스트가 "VOIDED 면 무조건 원금"인지 "환불이면 원금"인지 구별하지 못한다.
    const shown = rowAmounts(
      tx({
        status: "VOIDED",
        amountMinor: 15000,
        originalAmountMinor: 15000,
        voidedAt: "2026-07-15T04:00:00Z",
      }),
    );

    expect(shown.headlineMinor).toBe(15000);
    expect(shown.showRefund).toBe(false);
    expect(shown.showVoidedBadge).toBe(true);
    expect(shown.showSplit).toBe(false);
  });

  it("평범한 승인 거래는 환불 줄을 내지 않는다(대조군)", () => {
    const shown = rowAmounts(tx({ amountMinor: 9000, originalAmountMinor: 9000 }));

    expect(shown.headlineMinor).toBe(9000);
    expect(shown.showRefund).toBe(false);
    expect(shown.showVoidedBadge).toBe(false);
    expect(shown.showSplit).toBe(true);
    expect(shown.remainingMinor).toBeNull();
  });
});
