"use client";

import { Button } from "@/components/ui/button";
import { formatTime, formatWon, mealWindowLabel, type Approval } from "./types";

/**
 * 이 단말에서 방금 처리한 승인들. **화면 상태일 뿐 장부가 아니다** — 새로고침하면 사라진다.
 *
 * 그래도 필요한 이유: "이미 사용된 QR" 거절이 나왔을 때 계산원이 가장 먼저 확인해야 하는 것이
 * "방금 이 손님 결제가 이미 됐는가"다. 그 답이 눈앞에 없으면 손님을 세워 둔 채 본사에 전화하게 된다.
 * 매장 전체 내역은 가맹 관리자 화면에서 taspa 가 보관한 것을 본다.
 *
 * ★**여기서 다시 열 수 있어야 한다.** '다음 손님'을 누르는 순간 취소·환불 패널이 사라져,
 * 그 뒤에 손님이 "방금 그거 취소해 주세요"라고 하면 계산대에는 **아무 수단도 없었다**
 * (가맹 관리자 콘솔에는 조회만 있고 승인 경로가 없다 — 의도된 분리다). 그래서 현장의 답은
 * 손님을 돌려보내거나 본사에 전화하는 것뿐이었다.
 * 재승인이 아니라 **같은 패널을 다시 여는 것**이라, 취소·환불 규칙은 한 곳에만 남는다.
 */
export function RecentApprovals({
  approvals,
  onReopen,
}: {
  approvals: Approval[];
  /** 그 승인을 다시 '승인 완료' 화면으로 열어 취소·환불 패널을 쓰게 한다. */
  onReopen?: (approval: Approval) => void;
}) {
  if (approvals.length === 0) {
    return (
      <p className="px-1 py-3 text-base text-muted-foreground">이 단말에서 처리한 결제가 아직 없습니다.</p>
    );
  }

  return (
    <ul className="divide-y divide-border rounded-2xl border border-border bg-card">
      {approvals.map((approval) => {
        const voided = approval.status === "VOIDED";
        return (
          <li key={approval.authId} className="flex items-center justify-between gap-4 px-5 py-3">
            <div className="min-w-0">
              <p className="text-lg font-medium text-foreground">
                <span className="tabular-nums">{formatWon(approval.totalMinor)}</span>
                {voided && <span className="ml-2 text-base text-muted-foreground">취소됨</span>}
              </p>
              <p className="text-sm text-muted-foreground">
                {formatTime(approval.at)} · {mealWindowLabel(approval.mealWindow)}
                {!voided && approval.selfPaidMinor > 0 && <> · 개인 {formatWon(approval.selfPaidMinor)}</>}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-3">
              {onReopen && !voided && (
                <Button variant="outline" size="sm" onClick={() => onReopen(approval)}>
                  취소·환불
                </Button>
              )}
              <span className="font-mono text-xs text-muted-foreground">{approval.authId.slice(0, 8)}</span>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
