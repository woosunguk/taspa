"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { formatWon, mealWindowLabel, type Approval } from "./types";

/**
 * 승인 결과 — **이 화면의 존재 이유**.
 *
 * 회사 한도를 넘은 금액은 거절되지 않고 개인 부담으로 **분리 승인**된다. 즉 승인 성공 화면을 보고도
 * 계산원은 손님에게 돈을 더 받아야 할 수 있다. 그래서 가장 크게, 가장 먼저 보이는 숫자는 총액도
 * 회사 부담도 아닌 **손님에게 받을 금액**이다. 그 아래에 총액·회사 부담을 근거로 붙인다.
 */
export function ApprovalPanel({
  approval,
  onVoid,
  onRefund,
  voiding,
  voidError,
  onNext,
}: {
  approval: Approval;
  onVoid: () => void;
  onRefund: (amountMinor: number) => void;
  voiding: boolean;
  voidError: string | null;
  onNext: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [refunding, setRefunding] = useState(false);
  const [refundDigits, setRefundDigits] = useState("");
  const voided = approval.status === "VOIDED";
  const due = voided ? 0 : approval.selfPaidMinor;
  const cashBack = approval.cashBackMinor;
  const refundAmount = Number(refundDigits.replace(/[^0-9]/g, "")) || 0;
  const refundable = approval.totalMinor;

  return (
    <div className="flex flex-col gap-5">
      <div
        className={`rounded-2xl px-6 py-6 text-center ${
          cashBack !== null && cashBack > 0
            ? "bg-[color:var(--taspa-warning-soft)]"
            : voided
              ? "bg-muted"
              : due > 0
                ? "bg-[color:var(--taspa-warning-soft)]"
                : "bg-[color:var(--taspa-success-soft)]"
        }`}
      >
        {cashBack !== null && cashBack > 0 ? (
          <>
            {/* 승인 화면이 "받을 금액"을 가장 크게 보여주듯, 환불 직후엔 "돌려줄 금액"이 그 자리다. */}
            <p className="text-lg font-medium text-foreground">환불되었습니다</p>
            <p className="mt-3 text-base text-muted-foreground">손님에게 돌려줄 금액</p>
            <p className="tabular-nums text-6xl font-bold text-[color:var(--taspa-warning)]">
              {formatWon(cashBack)}
            </p>
            <p className="mx-auto mt-3 max-w-sm text-base text-foreground">
              손님이 직접 낸 금액에서 돌아간 몫입니다. 회사 부담분은 정산에서 조정됩니다.
            </p>
          </>
        ) : voided ? (
          <>
            <p className="text-2xl font-semibold text-foreground">취소됨</p>
            <p className="mt-2 text-base text-muted-foreground">
              이 거래는 취소됐습니다. 받은 금액이 있으면 손님에게 돌려주세요.
            </p>
          </>
        ) : (
          <>
            <p className="text-lg font-medium text-foreground">승인되었습니다</p>
            <p className="mt-3 text-base text-muted-foreground">
              {due > 0 ? "손님에게 받을 금액" : "손님에게 받을 금액 없음"}
            </p>
            <p
              className={`tabular-nums text-6xl font-bold ${
                due > 0 ? "text-[color:var(--taspa-warning)]" : "text-[color:var(--taspa-success)]"
              }`}
            >
              {formatWon(due)}
            </p>
            {due > 0 && (
              <p className="mx-auto mt-3 max-w-sm text-base text-foreground">
                회사 한도를 넘은 금액입니다. 초과분만 손님에게 직접 받으세요.
              </p>
            )}
          </>
        )}
      </div>

      <dl className="divide-y divide-border rounded-2xl border border-border bg-card">
        <Row label="결제 금액" value={formatWon(approval.totalMinor)} />
        {approval.refundedMinor > 0 && (
          <Row label="환불된 금액" value={`-${formatWon(approval.refundedMinor)}`} />
        )}
        <Row label="회사 부담" value={formatWon(voided ? 0 : approval.orgPaidMinor)} />
        <Row label="개인 부담" value={formatWon(due)} />
        <Row label="끼니" value={mealWindowLabel(approval.mealWindow)} />
        <Row label="승인 번호" value={approval.authId} mono />
      </dl>

      {voidError && (
        <p role="alert" className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive">
          {voidError}
        </p>
      )}

      <div className="flex flex-col gap-3">
        <Button type="button" size="lg" className="h-16 rounded-xl text-xl" onClick={onNext}>
          다음 손님
        </Button>

        {/*
          부분 환불 — 식사는 있었는데 금액만 틀린 경우(주문 하나가 잘못 나감).
          전액 취소로 대신하려면 손님이 QR 을 다시 받아야 하고(토큰은 단일 사용) 장부에 거래가 둘로 남는다.
          돌려줄 주머니(회사/개인)는 서버가 정한다 — 여기서 계산하면 화면과 장부가 갈라진다.
        */}
        {!voided &&
          !confirming &&
          (refunding ? (
            <div className="flex flex-col gap-3 rounded-2xl border border-border p-4">
              <label htmlFor="refund-amount" className="text-base text-muted-foreground">
                돌려줄 금액 (최대 {formatWon(refundable)})
              </label>
              <input
                id="refund-amount"
                inputMode="numeric"
                autoComplete="off"
                className="h-14 rounded-xl border border-input bg-transparent px-4 text-right text-2xl tabular-nums"
                value={refundDigits}
                onChange={(event) => setRefundDigits(event.target.value.replace(/[^0-9]/g, ""))}
                placeholder="0"
              />
              <p className="text-sm text-muted-foreground">
                손님이 낸 금액이 있으면 그쪽부터 돌아갑니다. 전액을 넣으면 거래가 취소됩니다.
              </p>
              <div className="flex gap-3">
                <Button
                  type="button"
                  size="lg"
                  className="h-14 flex-1 rounded-xl text-lg"
                  disabled={voiding || refundAmount <= 0 || refundAmount > refundable}
                  onClick={() => {
                    onRefund(refundAmount);
                    setRefunding(false);
                    setRefundDigits("");
                  }}
                >
                  {voiding ? "처리하는 중" : `${formatWon(refundAmount)} 환불`}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="lg"
                  className="h-14 rounded-xl px-5 text-lg"
                  onClick={() => {
                    setRefunding(false);
                    setRefundDigits("");
                  }}
                  disabled={voiding}
                >
                  그만두기
                </Button>
              </div>
            </div>
          ) : (
            <Button
              type="button"
              variant="outline"
              size="lg"
              className="h-14 rounded-xl text-lg"
              onClick={() => setRefunding(true)}
            >
              일부 금액 환불
            </Button>
          ))}

        {!voided &&
          !refunding &&
          (confirming ? (
            <div className="flex gap-3">
              <Button
                type="button"
                variant="destructive"
                size="lg"
                className="h-14 flex-1 rounded-xl text-lg"
                onClick={onVoid}
                disabled={voiding}
              >
                {voiding ? "취소하는 중" : "정말 취소합니다"}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="lg"
                className="h-14 rounded-xl px-5 text-lg"
                onClick={() => setConfirming(false)}
                disabled={voiding}
              >
                그만두기
              </Button>
            </div>
          ) : (
            /* 취소는 장부를 되돌리는 작업이다 — 손이 스쳐서 눌리지 않게 한 번 더 묻는다. */
            <Button
              type="button"
              variant="outline"
              size="lg"
              className="h-14 rounded-xl text-lg"
              onClick={() => setConfirming(true)}
            >
              이 거래 취소
            </Button>
          ))}
      </div>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 px-5 py-3">
      <dt className="text-base text-muted-foreground">{label}</dt>
      <dd
        className={`text-right text-lg font-medium text-foreground ${mono ? "font-mono text-sm break-all" : "tabular-nums"}`}
      >
        {value}
      </dd>
    </div>
  );
}
