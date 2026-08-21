"use client";

import { useMemo, useState } from "react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import type { Query } from "@/lib/useApi";
import { cn } from "@/lib/utils";
import { Notice, Portions, Section, Segmented, Stat, TableScroll } from "../../_components/kit";
import {
  MEAL_WINDOWS,
  forecastMethodLabel,
  formatCount,
  formatDate,
  formatDelta,
  formatRatio,
  isWeekend,
  mealWindowLabel,
  weekdayOf,
} from "../../_lib/format";
import type {
  MerchantBacktestCell,
  MerchantBacktestResponse,
  MerchantForecastResponse,
} from "../../_lib/types";
import { groupByDate, methodShort, sumPredictedOfRow } from "./shared";

export const HORIZON_OPTIONS = [
  { value: 7, label: "7일" },
  { value: 14, label: "14일" },
  { value: 31, label: "31일" },
];

export const BACKTEST_OPTIONS = [
  { value: 14, label: "2주" },
  { value: 28, label: "4주" },
  { value: 92, label: "3개월" },
];

/* ------------------------------------------------------------------ 기간 전망 */

/** 오늘부터 며칠치 전망. 오늘 행은 위 구획과 같은 값이라 표에서 표시만 해 둔다(중복 계산 아님). */
export function OutlookSection({
  forecast,
  horizon,
  onHorizon,
  today,
}: {
  forecast: Query<MerchantForecastResponse>;
  horizon: number;
  onHorizon: (value: number) => void;
  today: string | null;
}) {
  const data = forecast.data;
  const rows = useMemo(() => groupByDate(data?.cells ?? []), [data]);

  return (
    <Section
      title="기간별 예상 식수"
      description="전주 같은 요일 실적을 그대로 쓰고, 없으면 최근 4주 같은 요일 평균으로 대체합니다. 매장에는 재실 인원 모수가 없어 인원 보정은 하지 않습니다."
      action={
        <Segmented value={horizon} onChange={onHorizon} options={HORIZON_OPTIONS} ariaLabel="예측 기간" />
      }
    >
      {data?.windowTruncated && (
        <Notice>
          요청한 기간이 서버 상한에 걸려 {formatDate(data.from)} ~ {formatDate(data.to)} 로 좁혀졌습니다.
        </Notice>
      )}

      {forecast.error && <ErrorNotice message={forecast.error} onRetry={forecast.reload} />}
      {forecast.loading && !data && <RowsSkeleton rows={5} />}

      {!forecast.loading && data && rows.length === 0 && (
        <EmptyState title="예측할 날짜가 없습니다" description="조회 기간을 바꿔 보세요." />
      )}

      {rows.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>날짜</TableHead>
                {MEAL_WINDOWS.map((window) => (
                  <TableHead key={window} className="text-right">
                    {mealWindowLabel(window)}
                  </TableHead>
                ))}
                <TableHead className="text-right">합계</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => {
                const day = sumPredictedOfRow(row);
                const isToday = row.date === today;
                return (
                  <TableRow key={row.date} className={cn(isToday && "bg-muted/40")}>
                    <TableCell
                      className={cn(
                        "tabular whitespace-nowrap",
                        isWeekend(row.date) && !isToday && "text-muted-foreground",
                      )}
                    >
                      {formatDate(row.date)} ({weekdayOf(row.date)})
                      {isToday && <span className="ml-1.5 text-xs font-medium text-foreground">오늘</span>}
                    </TableCell>
                    {MEAL_WINDOWS.map((window) => {
                      const cell = row.byWindow.get(window);
                      return (
                        <TableCell key={window} className="text-right">
                          <Portions value={cell?.predicted ?? null} />
                          {cell && cell.predicted !== null && (
                            <span className="block text-xs text-muted-foreground">{methodShort(cell)}</span>
                          )}
                        </TableCell>
                      );
                    })}
                    <TableCell className="text-right">
                      <Portions value={day.total} emptyLabel="—" />
                      {day.missing > 0 && day.total !== null && (
                        <span className="block text-xs text-muted-foreground">{day.missing}개 끼니 제외</span>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </Section>
  );
}

/* ------------------------------------------------------------------ 최근 실적 */

const RECENT_DAYS = 7;

/**
 * 최근 실적 — 백테스트 응답을 그대로 재사용한다(같은 셀에 실적과 그때의 예측이 함께 들어 있다).
 * 예측 표와 같은 열 구성이라 위아래로 눈을 옮기며 "예측이 최근 실적과 얼마나 떨어져 있나"를 바로 본다.
 */
export function RecentActualsSection({ backtest }: { backtest: Query<MerchantBacktestResponse> }) {
  const rows = useMemo(() => {
    const all = groupByDate(backtest.data?.cells ?? []);
    return all.slice(Math.max(0, all.length - RECENT_DAYS));
  }, [backtest.data]);

  return (
    <Section
      title={`최근 ${RECENT_DAYS}일 실적`}
      description="확정된 소비 이벤트 기준입니다. 취소(void)된 결제는 집계에서 자동으로 빠집니다."
    >
      {backtest.error && <ErrorNotice message={backtest.error} onRetry={backtest.reload} />}
      {backtest.loading && !backtest.data && <RowsSkeleton rows={4} />}

      {!backtest.loading && backtest.data && rows.length === 0 && (
        <EmptyState
          title="아직 실적이 없습니다"
          description="매장에서 식권 결제가 이뤄지면 다음 날부터 여기에 쌓입니다."
        />
      )}

      {rows.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>날짜</TableHead>
                {MEAL_WINDOWS.map((window) => (
                  <TableHead key={window} className="text-right">
                    {mealWindowLabel(window)}
                  </TableHead>
                ))}
                <TableHead className="text-right">합계</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => {
                const total = MEAL_WINDOWS.reduce(
                  (sum, window) => sum + (row.byWindow.get(window)?.actual ?? 0),
                  0,
                );
                return (
                  <TableRow key={row.date}>
                    <TableCell
                      className={cn(
                        "tabular whitespace-nowrap",
                        isWeekend(row.date) && "text-muted-foreground",
                      )}
                    >
                      {formatDate(row.date)} ({weekdayOf(row.date)})
                    </TableCell>
                    {MEAL_WINDOWS.map((window) => {
                      const cell = row.byWindow.get(window);
                      return (
                        <TableCell key={window} className="tabular text-right">
                          <span className="font-medium">{formatCount(cell?.actual ?? 0)}</span>
                          {cell && (
                            <span className="block text-xs text-muted-foreground">
                              예측 {cell.predicted === null ? "—" : formatCount(cell.predicted)}
                            </span>
                          )}
                        </TableCell>
                      );
                    })}
                    <TableCell className="tabular text-right font-medium">{formatCount(total)}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableScroll>
      )}
    </Section>
  );
}

/* ------------------------------------------------------------------ 예측 정확도 */

/**
 * 백테스트 — 과거 각 셀에 "그 시점에 예측했을 값"을 같은 방법으로 계산해 실적과 비교한다.
 * 입력이 D-7·D-14·D-21·D-28 실적뿐이라 타깃 당일 정보가 개입할 경로가 없다(미래정보 누수 없음).
 */
export function AccuracySection({
  backtest,
  days,
  onDays,
}: {
  backtest: Query<MerchantBacktestResponse>;
  days: number;
  onDays: (value: number) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const summary = backtest.data?.summary;
  const cells = backtest.data?.cells ?? [];
  const shown = expanded ? cells : cells.slice(Math.max(0, cells.length - 12));

  return (
    <Section
      title="예측이 얼마나 맞았나"
      description="어제까지의 과거 구간만 평가합니다. 실적이 0인 셀은 MAPE 분모에서 제외되며 그 개수를 함께 보여줍니다."
      action={<Segmented value={days} onChange={onDays} options={BACKTEST_OPTIONS} ariaLabel="평가 기간" />}
    >
      {backtest.data?.windowTruncated && (
        <Notice>
          요청한 기간이 서버 상한에 걸려 {formatDate(backtest.data.from)} ~ {formatDate(backtest.data.to)} 로
          좁혀졌습니다.
        </Notice>
      )}

      {backtest.error && <ErrorNotice message={backtest.error} onRetry={backtest.reload} />}
      {backtest.loading && !backtest.data && <RowsSkeleton rows={4} />}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="MAPE(평균 절대 백분율 오차)"
            value={formatRatio(summary.mape)}
            hint={`채점 ${summary.scoredCells}셀 · 실적 0으로 제외 ${summary.mapeExcludedZeroActual}셀`}
          />
          <Stat label="WAPE(가중 절대 오차)" value={formatRatio(summary.wape)} />
          <Stat
            label="편향(bias)"
            value={formatRatio(summary.bias)}
            hint={
              summary.bias === null
                ? undefined
                : summary.bias > 0
                  ? "과대예측 경향(잔반 위험)"
                  : "과소예측 경향(품절 위험)"
            }
          />
          <Stat label="전체 셀" value={formatCount(summary.cells)} />
        </div>
      )}

      {!backtest.loading && backtest.data && cells.length === 0 && (
        <EmptyState title="평가할 셀이 없습니다" description="평가 기간을 넓혀 보세요." />
      )}

      {cells.length > 0 && (
        <>
          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>날짜</TableHead>
                  <TableHead>끼니</TableHead>
                  <TableHead className="text-right">예측</TableHead>
                  <TableHead className="text-right">실적</TableHead>
                  <TableHead className="text-right">오차</TableHead>
                  <TableHead>산출 방법</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {shown.map((cell) => (
                  <BacktestRow key={`${cell.date}-${cell.mealWindow}`} cell={cell} />
                ))}
              </TableBody>
            </Table>
          </TableScroll>

          {cells.length > shown.length && (
            <Button variant="outline" size="sm" onClick={() => setExpanded(true)}>
              전체 {formatCount(cells.length)}셀 보기
            </Button>
          )}
          {expanded && cells.length > 12 && (
            <Button variant="outline" size="sm" onClick={() => setExpanded(false)}>
              최근 12셀만 보기
            </Button>
          )}
        </>
      )}
    </Section>
  );
}

function BacktestRow({ cell }: { cell: MerchantBacktestCell }) {
  const diff = cell.predicted === null ? null : cell.predicted - cell.actual;

  return (
    <TableRow>
      <TableCell className="tabular whitespace-nowrap">
        {formatDate(cell.date)} ({weekdayOf(cell.date)})
      </TableCell>
      <TableCell>{mealWindowLabel(cell.mealWindow)}</TableCell>
      <TableCell className="text-right">
        <Portions value={cell.predicted} />
      </TableCell>
      <TableCell className="tabular text-right">{formatCount(cell.actual)}</TableCell>
      <TableCell className={cn("tabular text-right", diff !== null && diff !== 0 && "text-muted-foreground")}>
        {formatDelta(diff)}
      </TableCell>
      <TableCell>
        <Badge variant={cell.method === "NO_DATA" ? "outline" : "secondary"}>
          {forecastMethodLabel(cell.method)}
        </Badge>
      </TableCell>
    </TableRow>
  );
}
