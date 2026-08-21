"use client";

import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { DownloadLink } from "@/components/DownloadLink";
import { useApi } from "@/lib/useApi";
import { cn } from "@/lib/utils";
import { Notice, Section, Segmented, Stat, TableScroll } from "../../_components/kit";
import { merchantPath, useMerchant } from "../../_lib/merchant-context";
import {
  daysBetween,
  formatCount,
  formatDate,
  formatDateTime,
  formatWon,
  mealWindowLabel,
  rangeQuery,
  transactionStatusLabel,
} from "../../_lib/format";
import { summarize } from "../../_lib/summarize";
import type { MerchantTransaction, MerchantTransactionsResponse } from "../../_lib/types";

/** 서버 상한(MerchantConsoleService) — 넘겨 보내면 좁혀지므로 화면에서 먼저 안내한다. */
const MAX_WINDOW_DAYS = 92;

const LIMIT_OPTIONS = [
  { value: 50, label: "50건" },
  { value: 100, label: "100건" },
  { value: 200, label: "200건" },
  { value: 500, label: "500건" },
];

/**
 * 식수 로그(거래 내역) — 매장의 대사(對査)용 화면.
 *
 * 원천은 예측과 다르다: 예측은 확정 소비 이벤트(몇 인분)를, 이 화면은 **장부인 meal_transactions**(금액·정산)를
 * 읽는다. 그래서 취소(VOIDED)된 건도 그대로 보인다 — 집계에서 빠지는 것과 "매장이 그 사실을 아는 것"은
 * 다른 문제다.
 *
 * ★손님이 누구인지는 여기에 없다. 서버 응답 DTO 에 userId·이메일·이름 자리가 아예 없고(설계된 제약),
 * 매장이 필요로 하는 것은 인분 수와 정산용 조직·금액·거래 참조키뿐이다.
 */
export default function MerchantTransactionsPage() {
  const { merchantId } = useMerchant();
  const [from, setFrom] = useState<string | null>(null);
  const [to, setTo] = useState<string | null>(null);
  const [limit, setLimit] = useState(50);

  // 기간을 고르지 않으면 파라미터를 보내지 않는다 — 서버가 매장 타임존으로 최근 7일을 잡는다.
  const span = from && to ? daysBetween(from, to) : null;
  const invalidRange = span !== null && span <= 0;

  const path = invalidRange
    ? null
    : merchantPath(merchantId, `/transactions${rangeQuery({ from, to, limit })}`);
  const query = useApi<MerchantTransactionsResponse>(path);
  const data = query.data;
  // 매 렌더마다 새 배열이 생기면 아래 합계 useMemo 가 무의미해진다.
  const rows = useMemo(() => data?.rows ?? [], [data]);

  // 입력칸은 사용자가 고른 값이 없으면 **서버가 실제로 적용한 구간**을 보여준다(요청값이 아니라 실효값).
  const fromValue = from ?? data?.from ?? "";
  const toValue = to ?? data?.to ?? "";

  const totals = useMemo(() => summarize(rows), [rows]);

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="식수 로그"
        description="매장에서 승인된 식권 결제 내역입니다. 금액은 조직 부담(청구 대상)과 개인 부담으로 나뉘어 있습니다."
        action={
          <div className="flex items-center gap-2">
            <Segmented value={limit} onChange={setLimit} options={LIMIT_OPTIONS} ariaLabel="표시 건수" />
            {/* 화면과 **같은 조회 조건**을 그대로 넘긴다 — 파일이 화면보다 많거나 적으면 대사가 어긋난다. */}
            {!invalidRange && (
              <DownloadLink
                href={merchantPath(merchantId, `/transactions/csv${rangeQuery({ from, to, limit })}`)}
              >
                CSV
              </DownloadLink>
            )}
          </div>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="txn-from">시작일</Label>
            <Input
              id="txn-from"
              type="date"
              value={fromValue}
              onChange={(event) => setFrom(event.target.value || null)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="txn-to">종료일</Label>
            <Input
              id="txn-to"
              type="date"
              value={toValue}
              onChange={(event) => setTo(event.target.value || null)}
            />
          </div>
        </div>

        {invalidRange && (
          <p className="text-sm text-destructive">
            시작일이 종료일보다 앞서야 합니다. 조회 기간은 최대 {MAX_WINDOW_DAYS}일입니다.
          </p>
        )}

        {data?.windowTruncated && (
          <Notice>
            요청한 기간이 서버 상한({MAX_WINDOW_DAYS}일)에 걸려 {formatDate(data.from)} ~{" "}
            {formatDate(data.to)} 로 좁혀졌습니다. 시작일을 앞당겨도 그보다 오래된 건은 보이지 않습니다.
          </Notice>
        )}
        {data?.rowsTruncated && (
          <Notice>
            표시 건수 상한({formatCount(data.limit)}건)에 도달했습니다 — 이 기간에 더 많은 거래가 있을 수
            있습니다. 아래 합계도 <b className="font-medium">불러온 {formatCount(data.limit)}건 기준</b>
            입니다.
          </Notice>
        )}

        {query.error && <ErrorNotice message={query.error} onRetry={query.reload} />}
        {query.loading && !data && <RowsSkeleton rows={5} />}

        {data && rows.length > 0 && (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat
              label="승인"
              value={`${formatCount(totals.approvedCount)}건`}
              hint={totals.voidedCount > 0 ? `취소 ${totals.voidedCount}건 별도` : undefined}
            />
            <Stat label="결제액 합계" value={formatWon(totals.amount)} hint="취소 건 제외" />
            <Stat label="조직 부담(청구 대상)" value={formatWon(totals.orgPaid)} />
            <Stat
              label="개인 부담"
              value={formatWon(totals.selfPaid)}
              hint={totals.refunded > 0 ? "한도 초과분 · 환불 반영 후" : "한도 초과분"}
            />
            {/* 환불이 있을 때만 낸다 — 항상 0 인 칸은 읽는 사람의 주의를 소모할 뿐이다. */}
            {totals.refunded > 0 && (
              <Stat
                label="환불"
                value={formatWon(totals.refunded)}
                hint={`${formatCount(totals.refundedCount)}건 · 위 결제액 합계에는 이미 반영됨`}
              />
            )}
          </div>
        )}

        {!query.loading && data && rows.length === 0 && (
          <EmptyState
            title="이 기간에 거래가 없습니다"
            description="기간을 넓혀 보세요. 결제는 POS 단말에서 손님의 QR 을 읽을 때 기록됩니다."
          />
        )}

        {rows.length > 0 && <TransactionTable rows={rows} timezone={data?.timezone} />}
      </Section>
    </div>
  );
}

/**
 * `timezone` 은 같은 응답이 내려준 **매장 타임존**이다(행과 같은 원천이라 어긋날 수 없다). 서버가 매장
 * 달력으로 필터한 구간을 브라우저 로컬로 그리면 조회 구간 밖 날짜가 표에 섞여 보인다.
 */
function TransactionTable({ rows, timezone }: { rows: MerchantTransaction[]; timezone?: string }) {
  return (
    <TableScroll>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>
              승인 시각
              <span className="ml-1 font-normal text-muted-foreground">
                ({timezone ? `매장 시간 · ${timezone}` : "매장 시간"})
              </span>
            </TableHead>
            <TableHead>끼니</TableHead>
            <TableHead>조직</TableHead>
            <TableHead className="text-right">결제액</TableHead>
            <TableHead className="text-right">조직 부담</TableHead>
            <TableHead className="text-right">개인 부담</TableHead>
            <TableHead className="text-right">환불</TableHead>
            <TableHead>상태</TableHead>
            <TableHead>거래 참조</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TransactionRow key={row.authId} row={row} timezone={timezone} />
          ))}
        </TableBody>
      </Table>
    </TableScroll>
  );
}

function TransactionRow({ row, timezone }: { row: MerchantTransaction; timezone?: string }) {
  const voided = row.status === "VOIDED";
  const money = cn("tabular text-right whitespace-nowrap", voided && "text-muted-foreground line-through");

  return (
    <TableRow>
      <TableCell className={cn("tabular whitespace-nowrap", voided && "text-muted-foreground")}>
        {formatDateTime(row.approvedAt, timezone)}
      </TableCell>
      <TableCell className="whitespace-nowrap">{mealWindowLabel(row.mealWindow)}</TableCell>
      <TableCell className="max-w-40 truncate">
        {row.orgName ?? <span className="text-muted-foreground">(삭제된 조직)</span>}
      </TableCell>
      <TableCell className={money}>{formatWon(row.amountMinor)}</TableCell>
      <TableCell className={money}>{formatWon(row.orgPaidMinor)}</TableCell>
      <TableCell className={money}>
        {row.selfPaidMinor > 0 ? (
          formatWon(row.selfPaidMinor)
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </TableCell>
      {/* 환불이 있으면 결제액이 이미 줄어 있다 — 원금과 환불액을 나란히 두어야 POS 기록과 맞춰볼 수 있다. */}
      <TableCell className="tabular text-right whitespace-nowrap">
        {row.refundedMinor > 0 ? (
          <>
            <span className="text-foreground">{formatWon(row.refundedMinor)}</span>
            <span className="mt-0.5 block text-xs text-muted-foreground">
              원금 {formatWon(row.originalAmountMinor)}
              {row.refundCount > 1 && <> · {row.refundCount}회</>}
            </span>
          </>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </TableCell>
      <TableCell className="whitespace-nowrap">
        {voided ? (
          <>
            <Badge variant="destructive">{transactionStatusLabel(row.status)}</Badge>
            {row.voidedAt && (
              <span className="mt-0.5 block text-xs text-muted-foreground">
                {formatDateTime(row.voidedAt, timezone)}
              </span>
            )}
          </>
        ) : (
          <>
            <Badge variant="secondary">{transactionStatusLabel(row.status)}</Badge>
            {row.refundedMinor > 0 && row.lastRefundedAt && (
              <span className="mt-0.5 block text-xs text-muted-foreground">
                {formatDateTime(row.lastRefundedAt, timezone)} 환불
              </span>
            )}
          </>
        )}
      </TableCell>
      {/* 대사·이의제기 때 특정 건을 지목하는 키. 손님 정보가 아니라 매장(POS)이 이미 가진 값이다. */}
      <TableCell className="max-w-52">
        <span className="block truncate font-mono text-xs text-foreground" title={row.posTxnId}>
          {row.posTxnId}
        </span>
        <span className="block truncate font-mono text-xs text-muted-foreground" title={row.authId}>
          {row.authId}
        </span>
      </TableCell>
    </TableRow>
  );
}
