"use client";

import { Badge } from "@/components/ui/badge";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { rowAmounts } from "./amounts";
import {
  formatDateTime,
  formatWon,
  mealWindowLabel,
  orgPaidMinor,
  statusLabel,
  type MealTransaction,
} from "./types";

/**
 * 최근 사용 내역.
 *
 * 표(table) 대신 행 목록으로 그린다 — 모바일 폭에서 5열 표는 가로 스크롤이 생기고, 매장에서 확인하는
 * 정보는 "언제·어디서·얼마"라 한 행에 2줄로 담는 편이 읽기 쉽다.
 *
 * ★합계는 여기서 내지 않는다. 이번 달 집계는 `SpendSummary` 가 **전체 조회분**을 모수로 계산하는데,
 * 이 목록은 그중 앞 몇 건만 받는다. 두 곳에서 각자 더하면 같은 화면에 다른 "이번 달 금액"이 뜬다.
 */
export function TransactionList({
  transactions,
  loading,
  error,
  onRetry,
}: {
  transactions: MealTransaction[] | undefined;
  loading: boolean;
  error: string | undefined;
  onRetry: () => void;
}) {
  if (error) return <ErrorNotice message={error} onRetry={onRetry} />;
  if (loading && !transactions) return <RowsSkeleton rows={3} />;
  if (!transactions || transactions.length === 0) {
    return (
      <EmptyState
        title="아직 사용 내역이 없습니다"
        description="제휴 매장에서 QR 을 스캔하면 결제 내역이 여기에 쌓입니다."
      />
    );
  }

  return (
    <ul className="divide-y divide-border">
      {transactions.map((transaction) => (
        <TransactionRow key={transaction.authId} transaction={transaction} />
      ))}
    </ul>
  );
}

function TransactionRow({ transaction }: { transaction: MealTransaction }) {
  const orgPaid = orgPaidMinor(transaction);
  // 어떤 숫자를 말할지는 `amounts.ts` 가 정한다 — 그 판단이 JSX 안에 있으면 회귀 테스트를 쓸 수 없고,
  // 전액 환불이 "0원 · 취소됨"으로 사라졌던 결함이 정확히 그렇게 생겼다.
  const shown = rowAmounts(transaction);

  return (
    <li className="flex items-start justify-between gap-3 py-3">
      <div className="min-w-0">
        <p
          className={`truncate text-sm font-medium ${
            shown.struck ? "text-muted-foreground" : "text-foreground"
          }`}
        >
          {transaction.merchantName ?? "이름 없는 가맹점"}
        </p>
        <p className="mt-0.5 text-xs text-muted-foreground">
          {formatDateTime(transaction.approvedAt)} · {mealWindowLabel(transaction.mealWindow)}
          {shown.showVoidedBadge && !shown.showRefund && transaction.voidedAt && (
            <> · {formatDateTime(transaction.voidedAt)} 취소</>
          )}
          {shown.showRefund && transaction.lastRefundedAt && (
            <> · {formatDateTime(transaction.lastRefundedAt)} 환불</>
          )}
        </p>
      </div>

      <div className="shrink-0 text-right">
        <p
          className={`tabular text-sm font-medium ${
            shown.struck ? "text-muted-foreground line-through" : "text-foreground"
          }`}
        >
          {formatWon(shown.headlineMinor)}
        </p>

        {/* 취소 배지는 "이 거래로 청구되는 금액이 없다"는 사실을 말한다(전액 환불도 여기 포함된다). */}
        {shown.showVoidedBadge && (
          <Badge variant="destructive" className="mt-1">
            {statusLabel(transaction.status)}
          </Badge>
        )}

        {/* 분담은 살아 있는 거래에만 의미가 있다 — VOIDED 는 청구되는 금액이 0 이다. */}
        {shown.showSplit && (
          <p className="tabular mt-0.5 text-xs text-muted-foreground">
            회사 {formatWon(orgPaid)}
            {transaction.selfPaidMinor > 0 && <> · 내 부담 {formatWon(transaction.selfPaidMinor)}</>}
          </p>
        )}

        {shown.showRefund && (
          <p className="tabular mt-0.5 text-xs text-muted-foreground">
            <span className="text-foreground">{formatWon(transaction.refundedMinor)} 환불</span>
            {transaction.selfRefundedMinor > 0 && (
              <> · 내가 {formatWon(transaction.selfRefundedMinor)} 돌려받음</>
            )}
            {/* 부분 환불은 남은 금액이 계속 청구된다 — 그 값을 말해 주지 않으면 영수증과 못 맞춘다. */}
            {shown.remainingMinor !== null && <> · 최종 {formatWon(shown.remainingMinor)}</>}
          </p>
        )}
      </div>
    </li>
  );
}
