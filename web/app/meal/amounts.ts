import type { MealTransaction } from "./types";

/**
 * 사용내역 한 줄이 **어떤 숫자를 말해야 하는가**.
 *
 * ★환불 정보는 취소 여부와 무관하게 드러나야 한다. 전액 환불은 거래를 VOIDED 로 수렴시키면서
 * `amountMinor`·`selfPaidMinor` 를 0 으로 만든다(`MealRedeemService.refund`). 그래서 환불 표시를
 * "취소가 아닐 때"로 묶으면, 15,000원을 쓰고 계산대에서 3,000원을 **현금으로 돌려받은** 사람의
 * 이력이 "0원 · 취소됨" 한 줄로 남는다 — 돈이 실제로 오간 거래일수록 숫자가 더 많이 사라진다.
 * (대조: 환불 없는 순수 취소는 `amountMinor` 가 그대로라 원래 금액이 정상 표시된다.)
 *
 * 렌더링에서 떼어 둔 이유: 이 판단이 JSX 안에 있으면 회귀 테스트를 쓸 수 없다.
 */
export interface RowAmounts {
  /** 머리 금액. 환불이 있으면 **원금**이다 — 0원은 사용자에게 아무 뜻도 아니다. */
  headlineMinor: number;
  /** 머리 금액에 취소선을 긋는가(청구되는 금액이 없다는 뜻). */
  struck: boolean;
  /** "취소됨" 배지 노출 — 전액 환불도 여기 포함된다. */
  showVoidedBadge: boolean;
  /** "회사 N · 내 부담 M" 노출 — 살아 있는 거래에만 의미가 있다. */
  showSplit: boolean;
  /** 환불 줄 노출. */
  showRefund: boolean;
  /** 부분 환불에서 계속 청구되는 남은 금액. 전액 환불이면 null(남은 것이 없다). */
  remainingMinor: number | null;
}

export function rowAmounts(transaction: MealTransaction): RowAmounts {
  const voided = transaction.status === "VOIDED";
  const refunded = transaction.refundedMinor > 0;
  return {
    headlineMinor: refunded ? transaction.originalAmountMinor : transaction.amountMinor,
    struck: voided,
    showVoidedBadge: voided,
    showSplit: !voided,
    showRefund: refunded,
    remainingMinor: refunded && !voided ? transaction.amountMinor : null,
  };
}
