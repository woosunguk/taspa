"use client";

import { MinusIcon, TrendingDownIcon, TrendingUpIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { ButtonLink } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { Section, Stat, TableScroll } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { formatCount, formatMinor, formatRatio, invoiceStatusLabel } from "../../_lib/labels";
import type { OrgSpend } from "./types";
import { formatInZone, monthLabel } from "./org-calendar";

/**
 * 식대(조직 부담) 요약 — 조직관리자 대시보드의 1번 질문 "이번 달 얼마 나가고 있나"에 답한다.
 *
 * 금액은 `GET /api/orgs/{orgId}/spend` 하나에서 온다. 이 조회는 **청구서를 만들지 않고** 청구서와 같은
 * 규칙(APPROVED 만, 조직부담 = 결제액 − 자기부담, org 타임존 월 경계)으로 집계하므로, 확정 전에도
 * 청구서와 같은 숫자를 볼 수 있다.
 *
 * ★기간은 서버가 정한다 — period 를 보내지 않고 응답의 periodStart/periodEnd 를 그대로 표시한다.
 * 브라우저 달력으로 "이번 달"을 만들면 조직 타임존과 어긋나 화면과 청구서가 다른 달을 가리킨다(V18).
 * 그래서 조직 타임존을 몰라도 이 구획은 정상 동작한다.
 */
export function SpendSection({ orgId, base }: { orgId: string; base: string }) {
  const spend = useApi<OrgSpend>(orgPath(orgId, "/spend"), [orgId]);
  const data = spend.data;

  return (
    <div className="flex flex-col gap-5">
      <Section
        title="식대 (조직 부담)"
        description={
          data
            ? `${data.timezone} 달력 ${monthLabel(data.period)} · ${formatInZone(data.periodStart, data.timezone)} ~ ${formatInZone(data.periodEnd, data.timezone)}` +
              (data.inProgress
                ? ` (진행 중 · ${formatInZone(data.asOf, data.timezone)} 기준)`
                : " (기간 종료)")
            : "승인 거래의 조직 부담분 합계입니다. 개인 자기부담분은 포함하지 않습니다."
        }
        action={
          <ButtonLink variant="outline" size="sm" href={`${base}/invoices`}>
            청구서 탭
          </ButtonLink>
        }
      >
        {spend.error && <ErrorNotice message={spend.error} onRetry={spend.reload} />}
        {/* 로딩 중을 "0원"으로 그리지 않는다 — 없는 값과 0 원은 다른 사실이다. */}
        {spend.loading && <RowsSkeleton rows={2} />}

        {!spend.loading && !spend.error && data && (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat
                label={`${monthLabel(data.period)} 조직 부담`}
                value={formatMinor(data.orgPaidMinor)}
                hint={`승인 거래 ${formatCount(data.txnCount)}건 · 개인 부담 ${formatMinor(data.selfPaidMinor)}`}
              />
              <Stat
                label={data.previous ? `전월 동기간 (${monthLabel(data.previous.period)})` : "전월 동기간"}
                value={
                  data.previous ? (
                    formatMinor(data.previous.orgPaidMinor)
                  ) : (
                    <span className="text-base font-normal text-muted-foreground">비교 없음</span>
                  )
                }
                hint={
                  data.previous
                    ? `${formatInZone(data.previous.periodStart, data.timezone)} ~ ${formatInZone(data.previous.periodEnd, data.timezone)} · ${formatCount(data.previous.txnCount)}건`
                    : "비교할 전월 구간을 잡지 못했습니다"
                }
              />
              <ChangeStat spend={data} />
              <InvoiceStat spend={data} />
            </div>

            {data.txnCount === 0 && (
              <p className="text-sm text-muted-foreground">
                이 기간에는 아직 승인된 식권 거래가 없습니다. 거래가 발생하면 이 화면에 바로 반영되며,
                청구서를 만들지 않아도 금액을 확인할 수 있습니다.
              </p>
            )}
          </>
        )}
      </Section>

      {!spend.loading && !spend.error && data && data.departments.length > 0 && (
        <DepartmentBreakdown spend={data} base={base} />
      )}
    </div>
  );
}

/**
 * 전월 대비 — **방향을 함께** 보여준다. 숫자만 두면 좋은 건지 나쁜 건지 알 수 없고, 이 화면에서
 * 관리자가 실제로 얻고 싶은 신호는 "늘고 있는가"이기 때문이다.
 *
 * 비율이 정의되지 않는 경우(전월 0원)에도 **증감액은 말할 수 있다** — 비교 자체를 포기하지 않는다.
 */
function ChangeStat({ spend }: { spend: OrgSpend }) {
  const previous = spend.previous;
  if (!previous) {
    return (
      <Stat
        label="전월 대비"
        value={<span className="text-base font-normal text-muted-foreground">비교 불가</span>}
        hint="전월 동기간 값이 없습니다"
      />
    );
  }

  const delta = spend.orgPaidMinor - previous.orgPaidMinor;
  const Icon = delta > 0 ? TrendingUpIcon : delta < 0 ? TrendingDownIcon : MinusIcon;
  // 지출 증가를 "성공"처럼 초록으로 칠하지 않는다 — 증가는 주의 신호에 가깝다.
  const tone = delta > 0 ? "text-destructive" : delta < 0 ? "text-muted-foreground" : "text-muted-foreground";

  return (
    <Stat
      label="전월 대비"
      value={
        <span className={`flex items-center gap-1.5 ${tone}`}>
          <Icon className="size-4 shrink-0" aria-hidden />
          <span>
            {delta > 0 ? "+" : delta < 0 ? "−" : ""}
            {formatMinor(Math.abs(delta))}
          </span>
        </span>
      }
      hint={
        previous.changeRatio === null
          ? "전월 동기간이 0원이라 증감률은 계산하지 않습니다"
          : `${previous.changeRatio > 0 ? "+" : ""}${formatRatio(previous.changeRatio)} · ${
              delta > 0 ? "늘었습니다" : delta < 0 ? "줄었습니다" : "같습니다"
            }`
      }
    />
  );
}

/**
 * 청구서 상태를 금액 **바로 옆에** 둔다. 같은 기간의 값이라도 확정 전 진행값과 확정된 청구액은 의미가
 * 다르고, 그 구분이 화면에 없으면 관리자가 진행값을 확정액으로 착각한다.
 */
function InvoiceStat({ spend }: { spend: OrgSpend }) {
  const invoice = spend.invoice;

  if (!invoice) {
    return (
      <Stat
        label="청구서"
        value={<span className="text-base font-normal text-muted-foreground">미생성</span>}
        hint="위 금액은 청구서 없이 계산한 진행값입니다"
      />
    );
  }

  const finalized = invoice.status === "FINALIZED";
  // 확정 청구서와 진행값이 다르면 확정 이후 취소·추가 거래가 있었다는 뜻이다 — 조용히 넘기지 않는다.
  const mismatch = invoice.subtotalMinor !== spend.orgPaidMinor;

  return (
    <Stat
      label="청구서"
      value={
        <Badge variant={finalized ? "default" : "secondary"}>{invoiceStatusLabel(invoice.status)}</Badge>
      }
      hint={
        mismatch
          ? `청구서 금액 ${formatMinor(invoice.subtotalMinor)} — 위 진행값과 다릅니다${finalized ? " (확정 이후 거래 변동)" : " (재생성하면 맞춰집니다)"}`
          : finalized
            ? "확정된 금액입니다"
            : "초안이라 거래 취소·추가에 따라 계속 바뀝니다"
      }
    />
  );
}

/**
 * 부서별 소계 — "어디에 많이 나가나"에 답한다.
 *
 * 개인별 라인은 **여기에 올리지 않는다**. 청구서 상세(정산 문서)에는 이메일+금액이 정당하게 있지만,
 * 상시로 열어 두는 대시보드에 개인 금액이 올라오면 화면 성격이 정산에서 감시로 바뀐다.
 * (서버 응답에도 개인 라인이 없다 — 화면 규율이 아니라 API 경계다.)
 */
function DepartmentBreakdown({ spend, base }: { spend: OrgSpend; base: string }) {
  const total = spend.departments.reduce((sum, row) => sum + row.orgPaidMinor, 0);
  const top = spend.departments.slice(0, 5);

  return (
    <Section
      title="부서별 비용"
      description={`${monthLabel(spend.period)} 진행값 기준 · 금액이 큰 순 상위 ${top.length}개 부서입니다. 부서는 현재 조직도 기준이며, 확정된 청구서는 생성 시점 부서명을 씁니다.`}
      action={
        <ButtonLink variant="outline" size="sm" href={`${base}/invoices`}>
          전체 내역
        </ButtonLink>
      }
    >
      <TableScroll>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>부서</TableHead>
              <TableHead className="text-right">거래 수</TableHead>
              <TableHead className="text-right">조직 부담</TableHead>
              <TableHead className="text-right">비중</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {top.map((row) => (
              <TableRow key={row.departmentId ?? "unassigned"}>
                <TableCell>
                  {row.departmentName ?? <span className="text-muted-foreground">부서 미배정</span>}
                </TableCell>
                <TableCell className="tabular text-right">{formatCount(row.txnCount)}건</TableCell>
                <TableCell className="tabular text-right">{formatMinor(row.orgPaidMinor)}</TableCell>
                <TableCell className="tabular text-right">
                  {total > 0 ? formatRatio(row.orgPaidMinor / total) : "—"}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableScroll>

      {spend.departments.length > top.length && (
        <p className="text-sm text-muted-foreground">
          나머지 {formatCount(spend.departments.length - top.length)}개 부서는 청구서 탭에서 볼 수 있습니다.
        </p>
      )}
    </Section>
  );
}
