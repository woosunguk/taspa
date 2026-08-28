"use client";

import Link from "next/link";

import { useMemo, useState } from "react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { ForecastLineChart } from "@/components/charts";
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
import { trendSeries, wasteReduction } from "../../_lib/insights";
import { useMerchant } from "../../_lib/merchant-context";
import { daySignalsOf, groupByDate, methodShort, sumPredictedOfRow } from "./shared";

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
  const { merchantId } = useMerchant();

  return (
    <Section
      title="기간별 예상 식수"
      description="이용 조직별로 나눠 예측한 뒤 합산합니다 — 각 조직의 휴일·행사·연차가 그 조직 몫에만 반영됩니다. 신호 열은 그 날 숫자가 평소와 다른 이유입니다."
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
                <TableHead>신호</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => {
                const day = sumPredictedOfRow(row);
                const isToday = row.date === today;
                const signals = daySignalsOf(row);
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
                          {/* 숫자는 근거 상세로 가는 링크다 — "왜 이 숫자인가"를 클릭 한 번에. */}
                          <Link
                            href={`/merchant/${merchantId}/cell/${row.date}/${window}`}
                            className="group inline-block rounded px-1 py-0.5 hover:bg-line"
                          >
                            <Portions value={cell?.predicted ?? null} />
                            {cell && cell.predicted !== null && (
                              <span className="block text-xs text-muted-foreground group-hover:underline">
                                {methodShort(cell)}
                              </span>
                            )}
                            {cell?.soFar != null && (
                              <span className="block text-xs text-brand">지금까지 {cell.soFar}</span>
                            )}
                          </Link>
                        </TableCell>
                      );
                    })}
                    <TableCell className="text-right">
                      <Portions value={day.total} emptyLabel="—" />
                      {day.missing > 0 && day.total !== null && (
                        <span className="block text-xs text-muted-foreground">{day.missing}개 끼니 제외</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex max-w-64 flex-wrap gap-1">
                        {signals.holidays.map((name) => (
                          <Badge
                            key={name}
                            variant="outline"
                            title="이 조직 캘린더의 휴일 — 그 조직 몫은 과거 휴일 실적만 근거로 씁니다"
                          >
                            🏮 {name}
                          </Badge>
                        ))}
                        {signals.events.map((name) => (
                          <Badge
                            key={name}
                            variant="secondary"
                            title="종일 사내 행사 — 그 조직 몫은 과거 행사일 실적만 근거로 씁니다"
                          >
                            🎪 {name}
                          </Badge>
                        ))}
                        {signals.absentWeight > 0 && (
                          <Badge
                            variant="outline"
                            title="이용 조직들이 등록한 연차·반차·출장의 가중 합 — 그만큼 재실이 줄어 예측이 낮아집니다"
                          >
                            🌴 연차{" "}
                            {signals.absentWeight % 1 === 0
                              ? signals.absentWeight
                              : signals.absentWeight.toFixed(1)}
                            명
                          </Badge>
                        )}
                        {signals.partial && (
                          <Badge
                            variant="outline"
                            title="일부 조직은 비교할 과거 실적이 없어 합계에서 빠져 있습니다 — 이 숫자는 하한입니다"
                          >
                            일부 조직 근거 없음
                          </Badge>
                        )}
                      </div>
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

/**
 * 잔반(과잉 준비) 절감 — **예측을 쓴 덕에 덜 버린 인분 수**를 백테스트 실측으로 계산한다.
 *
 * 계산과 그 가정은 `_lib/insights.ts` 의 [wasteReduction] 에 있다(순수 함수 + 테스트). 여기서 지키는
 * 것은 표현 규칙 하나다: **가정과 품절 위험을 절감량과 같은 화면에 둔다.** 좋은 쪽만 크게 보여주면
 * 이 패널은 근거를 물었을 때 답할 수 없는 마케팅 문구가 된다.
 */
export function WasteReductionSection({ backtest }: { backtest: Query<MerchantBacktestResponse> }) {
  const result = useMemo(() => wasteReduction(backtest.data?.cells ?? []), [backtest.data]);

  return (
    <Section
      title="잔반 절감 효과"
      description="예측을 쓴 덕분에 덜 준비한 만큼이 곧 덜 버린 양입니다. 아래 가정 위에서 계산한 값입니다."
    >
      {backtest.loading && !backtest.data && <RowsSkeleton rows={2} />}

      {!backtest.loading && !result && (
        <EmptyState
          illustration="/brand/happy.png"
          title="아직 계산할 실적이 없습니다"
          description="예측과 실적이 함께 있는 날이 하나도 없습니다. 며칠 운영하면 이 값이 채워집니다."
        />
      )}

      {result && (
        <div className="flex flex-col gap-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat
              label="절감한 준비량"
              value={`${formatCount(result.saved)}인분`}
              hint={result.rate === null ? undefined : `과잉 준비 ${formatRatio(result.rate)} 감소`}
              tone="success"
              emphasis
            />
            <Stat
              label="예측 기준 과잉"
              value={`${formatCount(result.forecastOver)}인분`}
              hint={
                result.excludedZeroActual > 0
                  ? `배식일 ${result.days}일 합계 · 실적 0인 ${result.excludedZeroActual}셀 제외`
                  : `${result.days}일 합계`
              }
            />
            <Stat
              label="예측 없이 준비했다면"
              value={`${formatCount(result.baselineOver)}인분`}
              hint={`끼니별 최대치 준비 가정 (${result.peaks
                .map((peak) => `${peak.name} ${formatCount(peak.value)}`)
                .join(" · ")})`}
              tone="muted"
            />
            <Stat
              label="모자랐던 양"
              value={`${formatCount(result.shortfall)}인분`}
              hint="예측이 실적보다 적었던 합계 — 품절 위험"
              tone={result.shortfall > result.saved ? "warning" : "default"}
            />
          </div>

          <Notice>
            <strong>계산 가정</strong> — <strong>배식한 날만</strong> 셉니다(실적 0인 날은 준비 자체가
            없었으므로 제외). 예측이 없는 주방은 품절을 피하려고 <strong>끼니별로</strong> 그 기간 최대치만큼
            매일 준비한다고 보았습니다. 실제 관행이 그보다 적으면 절감 효과도 그만큼 작아집니다. 금액·탄소
            환산은 매장마다 원가가 달라 이 화면에서 하지 않습니다.
          </Notice>
        </div>
      )}
    </Section>
  );
}

/**
 * 예측 vs 실적 추이 — 이 화면에서 **가장 먼저 읽히는 그림**이다.
 *
 * 같은 데이터가 아래 표들에도 있지만, 표는 "어제 몇 인분이었나"를 답하고 이 그림은 "우리 예측이 실제를
 * 따라가고 있나"를 답한다. 뒤쪽 질문은 숫자를 나열해서는 답이 안 나온다.
 *
 * 원천은 둘이다 — 과거는 백테스트(그 시점 예측 + 확정 실적), 미래는 예측. 계열 계산은
 * `_lib/insights.ts` 의 [trendSeries] 에 있고(순수 함수 + 테스트), 여기서는 그리기만 한다.
 */
export function TrendSection({
  forecast,
  backtest,
  today,
}: {
  forecast: Query<MerchantForecastResponse>;
  backtest: Query<MerchantBacktestResponse>;
  today: string | null;
}) {
  const points = useMemo(
    () => trendSeries(backtest.data?.cells ?? [], forecast.data?.cells ?? [], today),
    [backtest.data, forecast.data, today],
  );
  const loading = (backtest.loading && !backtest.data) || (forecast.loading && !forecast.data);

  return (
    <Section
      title="예측 vs 실제 식수 추이"
      description="실선은 확정 실적, 점선은 예측입니다. 배식하지 않은 날은 축에서 빠집니다."
    >
      {backtest.error && <ErrorNotice message={backtest.error} onRetry={backtest.reload} />}
      {forecast.error && <ErrorNotice message={forecast.error} onRetry={forecast.reload} />}
      {loading && <RowsSkeleton rows={4} />}

      {!loading && points.length === 0 && (
        <EmptyState
          illustration="/brand/cook.png"
          title="그릴 실적이 아직 없습니다"
          description="며칠 운영하면 실적선이 생기고, 그 위에 예측선이 얼마나 붙는지 보입니다."
        />
      )}

      {points.length > 0 && (
        <ForecastLineChart
          points={points}
          unit="인분"
          ariaLabel={`예측 vs 실제 식수 추이 (${points.length}일)`}
          actualName="실제 식수"
          predictedName="예측 식수"
        />
      )}
    </Section>
  );
}
