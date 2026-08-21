"use client";

import Link from "next/link";
import { useState } from "react";
import { DownloadLink } from "@/components/DownloadLink";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useApi } from "@/lib/useApi";
import { PageHeader, Section, StatCard, TableScroll } from "../_components/kit";
import type { PlatformPayablesView } from "../_lib/types";

/**
 * 전역 지급 현황 — **운영자의 자금 계획 도구**.
 *
 * 매장별 정산은 사장이 자기 몫을 확인할 때 연다. 이 화면은 반대 방향이다: 매장이 100개면 하나씩 열어
 * 볼 수 없고, 열어 보지 않으면 이번 달 총 지급액을 아무도 모른다.
 *
 * ★두 가지를 화면이 스스로 말해야 한다:
 *  1. **실 자금이동은 이 시스템에 없다.** 여기 숫자는 집계이고 실제 지급은 별도 절차다.
 *  2. 총액 0 이 "지급할 게 없다"인지 "**아무것도 안 봤다**"인지 — `scanned` 를 함께 보여 구분한다.
 */
export default function AdminPayablesPage() {
  const [periodInput, setPeriodInput] = useState("");
  const [period, setPeriod] = useState("");

  const query = period ? `?period=${encodeURIComponent(period)}` : "";
  const payables = useApi<PlatformPayablesView>(`/api/admin/payables${query}`);
  const data = payables.data;
  const rows = data?.lines ?? [];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="지급 현황"
        description="가맹점에 지급할 금액을 한 달 단위로 모아 봅니다. 각 매장의 정산 명세와 같은 계산을 쓰므로, 여기 금액과 매장이 자기 화면에서 보는 금액은 항상 일치합니다."
      />

      <Section
        title="기간"
        description="비워 두면 이번 달을 봅니다 — 지급은 진행 중인 달을 계획해야 하기 때문입니다(정합성 대사가 지난달 기본인 것과 다릅니다)."
      >
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            setPeriod(periodInput.trim());
          }}
        >
          <div className="flex flex-col gap-1.5">
            <label htmlFor="payables-period" className="text-sm font-medium text-foreground">
              기간 (YYYY-MM)
            </label>
            <Input
              id="payables-period"
              value={periodInput}
              onChange={(event) => setPeriodInput(event.target.value)}
              placeholder={data?.period ?? "2026-07"}
              className="tabular w-40"
            />
          </div>
          <Button type="submit">조회</Button>
          <DownloadLink href={`/api/admin/payables/csv${query}`}>CSV 내려받기</DownloadLink>
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

      {payables.error ? (
        <ErrorNotice message={payables.error} onRetry={payables.reload} />
      ) : payables.loading && !data ? (
        <RowsSkeleton rows={4} />
      ) : !data ? null : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="대상 기간" value={data.period} hint="매장별 타임존으로 각각 계산" />
            <StatCard
              label="지급 예정 총액"
              value={formatWon(data.totalPayableMinor)}
              hint="조직 부담 합계 — 실제 지급은 별도 절차"
            />
            <StatCard
              label="대상 매장"
              value={data.scanned}
              hint={
                data.scanned === 0 ? "이 기간에 거래가 있는 매장이 없습니다" : "이 기간에 거래가 있던 매장"
              }
            />
            <StatCard
              label="승인"
              value={`${data.totalApprovedCount.toLocaleString("ko-KR")}건`}
              hint={
                data.totalRefundedMinor > 0 ? `환불 ${formatWon(data.totalRefundedMinor)} 반영 후` : undefined
              }
            />
          </div>

          {/* ★총액이 실제보다 적을 수 있다는 사실을 말하지 않으면 이체 계획이 그만큼 어긋난다. */}
          {data.failed > 0 && (
            <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2.5 text-sm text-[color:var(--taspa-warning)]">
              {data.failed}개 매장은 집계에 <strong>실패</strong>했습니다(일시적 오류). 위 지급 예정 총액은{" "}
              <strong>그만큼 적습니다</strong> — 이체 전에 다시 조회하세요.
            </p>
          )}

          {data.skipped > 0 && (
            <p className="rounded-lg border border-[color:var(--taspa-warning)]/40 bg-[color:var(--taspa-warning-soft)] px-3 py-2.5 text-sm text-[color:var(--taspa-warning)]">
              거래가 있는 매장이 서버 상한(500)을 넘어 {data.skipped}개를 집계하지 못했습니다. 위 총액은
              <strong> 전부가 아닙니다</strong>.
            </p>
          )}

          <p className="rounded-lg border border-border bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground">
            이 화면은 <b className="font-medium text-foreground">집계</b>입니다 — taspa 는 실제 자금이동을
            수행하지 않습니다. 이체는 별도 절차로 진행하고, 여기 금액은 그 근거로만 사용하세요.
          </p>

          <Section
            title="매장별 지급 예정액"
            description="금액이 큰 순입니다. 손님이 계산대에서 직접 낸 금액은 매장이 이미 받았으므로 여기 포함되지 않습니다."
          >
            {data.scanned === 0 ? (
              <EmptyState
                title="이 기간에 거래가 있는 매장이 없습니다"
                description="아무것도 집계하지 않았습니다 — 지급액 0 과는 다른 상태입니다. 기간을 바꿔 보세요."
              />
            ) : rows.length === 0 ? (
              <EmptyState
                title="지급할 금액이 없습니다"
                description={`${data.scanned}개 매장을 집계했지만 조직 부담이 발생한 거래가 없습니다.`}
              />
            ) : (
              <TableScroll>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>가맹점</TableHead>
                      <TableHead className="text-right">승인</TableHead>
                      <TableHead className="text-right">지급 예정액</TableHead>
                      <TableHead className="text-right">환불</TableHead>
                      <TableHead className="w-24" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((line) => (
                      <TableRow key={line.merchantId}>
                        <TableCell>
                          <p className="font-medium">{line.merchantName}</p>
                          <p className="text-xs text-muted-foreground">{line.timezone}</p>
                        </TableCell>
                        <TableCell className="tabular text-right whitespace-nowrap">
                          {line.approvedCount.toLocaleString("ko-KR")}건
                        </TableCell>
                        <TableCell className="tabular text-right font-medium whitespace-nowrap">
                          {formatWon(line.payableMinor)}
                        </TableCell>
                        <TableCell className="tabular text-right whitespace-nowrap text-muted-foreground">
                          {line.refundedMinor > 0 ? formatWon(line.refundedMinor) : "—"}
                        </TableCell>
                        <TableCell className="text-right">
                          <Link
                            href={`/merchant/${line.merchantId}/settlement`}
                            className="text-sm font-medium text-primary hover:underline"
                          >
                            명세 열기
                          </Link>
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

/** 원 단위 표기. 관리 콘솔 전역 포매터가 없어 이 화면에서 정의한다(가맹 콘솔 formatWon 과 같은 규칙). */
function formatWon(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) return "—";
  return `${minor.toLocaleString("ko-KR")}원`;
}
