"use client";

import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useApi } from "@/lib/useApi";
import { Section, Stat, TableScroll } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import { MEAL_WINDOW_OPTIONS, formatCount, mealWindowLabel } from "../../_lib/labels";
import { formatInZone, monthLabel } from "./org-calendar";
import type { ConsumptionAggregateResponse } from "./types";

/**
 * 이번 달 식수 실적 — **집계만** 조회한다.
 *
 * 서버가 `date × meal_window` 로 접은 카운트만 내려주고 개별 이벤트·이용자 식별자는 응답에 존재하지
 * 않는다. 화면도 그 경계를 넘지 않는다(개인별 이용 순위 같은 파생 지표를 만들지 않는다 — 소규모
 * 조직에서는 결근·행동 추정 신호가 된다).
 *
 * 날짜 버킷은 서버가 조직 타임존으로 자르므로(V18), 조회 창의 `from` 도 **조직 로컬 월초**여야 한다.
 * 브라우저 로컬 월초를 보내면 KST 조직을 UTC 에서 열었을 때 월초 하루가 지난달로 샌다. 타임존을 모르면
 * 아예 조회하지 않는다.
 */
export function ConsumptionSection({
  orgId,
  monthKey,
  monthStart,
  timezone,
}: {
  orgId: string;
  /** 조직 타임존 기준 이번 달 'YYYY-MM'. */
  monthKey: string | null;
  /** 그 달 1일 00:00(조직 로컬)의 절대 시각(ISO). 계산 불가면 null. */
  monthStart: string | null;
  timezone: string | null;
}) {
  // `to` 는 보내지 않는다 — 서버 기본값이 '지금'이라 클라이언트 시계에 의존할 이유가 없다.
  const path = monthStart
    ? orgPath(
        orgId,
        `/consumption-events/aggregate?groupBy=date,meal_window&from=${encodeURIComponent(monthStart)}`,
      )
    : null;
  const aggregate = useApi<ConsumptionAggregateResponse>(path, [orgId]);

  const rows = aggregate.data?.rows ?? [];
  const totalCount = rows.reduce((sum, row) => sum + row.count, 0);
  const totalQuantity = rows.reduce((sum, row) => sum + row.quantity, 0);
  const days = new Set(rows.map((row) => row.date)).size;

  const byWindow = new Map<string, { count: number; quantity: number }>();
  for (const row of rows) {
    const bucket = byWindow.get(row.mealWindow) ?? { count: 0, quantity: 0 };
    bucket.count += row.count;
    bucket.quantity += row.quantity;
    byWindow.set(row.mealWindow, bucket);
  }
  // 서버 enum 순서(하루의 시간 순)로 먼저 그리고, 모르는 값이 오면 뒤에 원문으로 덧붙인다.
  const knownWindows = MEAL_WINDOW_OPTIONS.map((option) => option.value);
  const windows = [
    ...knownWindows.filter((value) => byWindow.has(value)),
    ...[...byWindow.keys()].filter((value) => !knownWindows.includes(value)),
  ];

  return (
    <Section
      title="이번 달 식수 실적"
      description={
        timezone
          ? `${monthLabel(monthKey)} 1일부터 지금까지, 조직 타임존 ${timezone} 기준 달력으로 집계했습니다. 취소된 거래는 제외되며 집계 수치만 조회합니다.`
          : "조직 타임존을 알 수 없어 이번 달 경계를 계산하지 못했습니다."
      }
    >
      {!monthStart && (
        <p className="text-sm text-muted-foreground">
          조직 타임존이 확인되면 이번 달 실적을 표시합니다. 브라우저 시간대로 대신 계산하지 않습니다 — 월
          경계가 하루 어긋나 청구서와 다른 숫자가 나옵니다.
        </p>
      )}

      {aggregate.error && <ErrorNotice message={aggregate.error} onRetry={aggregate.reload} />}
      {aggregate.loading && <RowsSkeleton rows={3} />}

      {aggregate.data && rows.length === 0 && (
        <p className="text-sm text-muted-foreground">
          이번 달 소비 기록이 아직 없습니다. (0건이 아니라 기록 자체가 없습니다 — 식권 결제가 일어나면 여기에
          쌓입니다.)
        </p>
      )}

      {rows.length > 0 && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat
              label="총 이용 건수"
              value={`${formatCount(totalCount)}건`}
              hint={`${monthLabel(monthKey)} 1일 ~ 현재`}
            />
            <Stat
              label="총 수량"
              value={`${formatCount(totalQuantity)}인분`}
              hint="한 건이 2인분일 수 있어 건수와 다릅니다"
            />
            <Stat
              label="이용이 있었던 날"
              value={`${formatCount(days)}일`}
              hint="기록이 하나도 없는 날은 세지 않습니다"
            />
            <Stat
              label="집계 창"
              value={
                <span className="text-sm font-normal">{formatInZone(aggregate.data?.from, timezone)}</span>
              }
              hint={`~ ${formatInZone(aggregate.data?.to, timezone)} (서버가 돌려준 실효 구간)`}
            />
          </div>

          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>끼니</TableHead>
                  <TableHead className="text-right">이용 건수</TableHead>
                  <TableHead className="text-right">수량</TableHead>
                  <TableHead className="text-right">비중(건수)</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {windows.map((value) => {
                  const bucket = byWindow.get(value)!;
                  return (
                    <TableRow key={value}>
                      <TableCell>{mealWindowLabel(value)}</TableCell>
                      <TableCell className="tabular text-right">{formatCount(bucket.count)}건</TableCell>
                      <TableCell className="tabular text-right">{formatCount(bucket.quantity)}인분</TableCell>
                      <TableCell className="tabular text-right">
                        {totalCount > 0 ? `${((bucket.count / totalCount) * 100).toFixed(1)}%` : "—"}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableScroll>
        </>
      )}
    </Section>
  );
}
