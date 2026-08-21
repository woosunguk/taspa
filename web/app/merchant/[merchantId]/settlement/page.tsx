"use client";

import { useState } from "react";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { DownloadLink } from "@/components/DownloadLink";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useApi } from "@/lib/useApi";
import { Notice, Section, Stat, TableScroll } from "../../_components/kit";
import { formatCount, formatWon } from "../../_lib/format";
import { useMerchant } from "../../_lib/merchant-context";
import type { MerchantSettlement } from "../../_lib/types";

/**
 * 월 정산 명세 — "이번 달 우리가 얼마를 받는가".
 *
 * 그전까지 매장은 식수 로그를 눈으로 더해야 했다.
 *
 * ★이 화면의 정직함은 두 가지에 걸려 있다:
 *  1. **지급 대상은 조직 부담뿐**이다. 개인 부담은 손님이 계산대에서 이미 냈으므로 합계에 섞으면
 *     매장은 받을 돈을 두 배로 기대한다. 두 숫자를 나란히 두되 **더하지 않는다**.
 *  2. **실 자금이동은 이 시스템에 없다.** 명세는 "얼마를 주고받아야 하는가"이고 실제 지급은 별도
 *     절차다 — 이 문장을 빼면 매장이 입금을 기다리지 않는다.
 */
export default function MerchantSettlementPage() {
  const { merchantId } = useMerchant();
  const [periodInput, setPeriodInput] = useState("");
  const [period, setPeriod] = useState("");

  const query = period ? `?period=${encodeURIComponent(period)}` : "";
  const settlement = useApi<MerchantSettlement>(
    merchantId ? `/api/merchant-console/${merchantId}/settlement${query}` : null,
    [merchantId, period],
  );
  const data = settlement.data;

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="월 정산 명세"
        description="매장 타임존 기준 한 달 동안 승인된 결제를 조직별로 묶은 집계입니다. 조직 청구서와 달력 기준이 달라(조직은 조직 타임존) 경계일 거래만큼 다를 수 있습니다."
      >
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            setPeriod(periodInput.trim());
          }}
        >
          <div className="flex flex-col gap-1.5">
            <label htmlFor="settlement-period" className="text-sm font-medium text-foreground">
              기간 (YYYY-MM)
            </label>
            <Input
              id="settlement-period"
              value={periodInput}
              onChange={(event) => setPeriodInput(event.target.value)}
              placeholder={data?.period ?? "2026-07"}
              className="tabular w-40"
            />
          </div>
          <Button type="submit">조회</Button>
          <DownloadLink
            href={`/api/merchant-console/${encodeURIComponent(merchantId)}/settlement/csv${query}`}
          >
            CSV 내려받기
          </DownloadLink>
          {period && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setPeriodInput("");
                setPeriod("");
              }}
            >
              이번 달로
            </Button>
          )}
        </form>
      </Section>

      {settlement.error ? (
        <ErrorNotice message={settlement.error} onRetry={settlement.reload} />
      ) : settlement.loading && !data ? (
        <RowsSkeleton rows={4} />
      ) : !data ? null : (
        <>
          <Notice>
            이 명세는 <b className="font-medium">집계</b>입니다 — 실제 지급은 별도 절차로 이뤄집니다. 금액이
            다르다고 판단되면 아래 조직별 내역과 식수 로그의 거래 참조키로 문의해 주세요.
          </Notice>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label="대상 기간" value={data.period} hint={`매장 시간 · ${data.timezone}`} />
            <Stat
              label="지급 예정액"
              value={formatWon(data.payableMinor)}
              hint="조직 부담 합계 — taspa 가 매장에 지급"
            />
            <Stat
              label="승인"
              value={`${formatCount(data.approvedCount)}건`}
              hint={data.voidedCount > 0 ? `취소 ${formatCount(data.voidedCount)}건 별도` : undefined}
            />
            {/* ★위 지급액과 더하지 않는다 — 이미 매장이 받은 돈이다. */}
            <Stat
              label="손님이 직접 낸 금액"
              value={formatWon(data.selfPaidTotalMinor)}
              hint="계산대에서 이미 수령 — 지급 대상 아님"
            />
          </div>

          {data.refundedTotalMinor > 0 && (
            <Notice>
              이 기간에 {formatWon(data.refundedTotalMinor)}이 환불되었습니다. 위 지급 예정액은 환불이
              <b className="font-medium"> 이미 반영된</b> 금액입니다.
            </Notice>
          )}

          <Section
            title="조직별 내역"
            description="어느 고객사에서 얼마가 나왔는지 — 이의제기 때 조직 단위로 지목할 수 있습니다."
          >
            {data.lines.length === 0 ? (
              <EmptyState
                title="이 기간에 승인된 결제가 없습니다"
                description="기간을 바꿔 보세요. 결제는 POS 단말에서 손님의 QR 을 읽을 때 기록됩니다."
              />
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>조직</TableHead>
                      <TableHead className="text-right">승인</TableHead>
                      <TableHead className="text-right">지급 예정액</TableHead>
                      <TableHead className="text-right">손님 직접 결제</TableHead>
                      <TableHead className="text-right">환불</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.lines.map((line) => (
                      <TableRow key={line.orgId}>
                        <TableCell className="max-w-52 truncate">
                          {/*
                            ★조직 id 앞자리를 함께 보인다. 이름만으로는 **동명 고객사**를 구분할 수 없어,
                            정산 이의제기 때 매장과 우리가 서로 다른 조직을 말하게 된다(그 자리에서
                            지목할 키가 없다). 전체 UUID 는 title 로 둔다 — 표를 넓히지 않으면서
                            복사할 수 있다.
                          */}
                          {line.orgName ?? <span className="text-muted-foreground">(삭제된 조직)</span>}
                          <span className="ml-2 font-mono text-xs text-muted-foreground" title={line.orgId}>
                            {line.orgId.slice(0, 8)}
                          </span>
                        </TableCell>
                        <TableCell className="tabular text-right whitespace-nowrap">
                          {formatCount(line.approvedCount)}건
                        </TableCell>
                        <TableCell className="tabular text-right font-medium whitespace-nowrap">
                          {formatWon(line.orgPaidMinor)}
                        </TableCell>
                        <TableCell className="tabular text-right whitespace-nowrap text-muted-foreground">
                          {line.selfPaidMinor > 0 ? formatWon(line.selfPaidMinor) : "—"}
                        </TableCell>
                        <TableCell className="tabular text-right whitespace-nowrap text-muted-foreground">
                          {line.refundedMinor > 0 ? formatWon(line.refundedMinor) : "—"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableScroll>
            )}
          </Section>
        </>
      )}
    </div>
  );
}
