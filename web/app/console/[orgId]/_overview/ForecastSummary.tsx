"use client";

import { Badge } from "@/components/ui/badge";
import { ButtonLink } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { NoValue, ProgressMeter, Section, Stat, TableScroll } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import {
  MEAL_WINDOW_OPTIONS,
  formatCount,
  formatDate,
  formatRatio,
  mealWindowLabel,
  weekdayOf,
} from "../../_lib/labels";
import type { BacktestResponse, ForecastResponse } from "../../_lib/types";

/**
 * 다음 주 식수 예측 요약.
 *
 * ★`from`/`to` 를 **보내지 않는다**. 서버 기본값이 조직 로컬 내일부터 7일이라, 브라우저 시간대로 만든
 * "내일"을 보내면 자정 근처에서 하루가 밀린다(조직 타임존을 모르는 진입 경로도 있다). 기간은 응답의
 * `from`/`to` 를 그대로 표시한다.
 *
 * ★사업장을 지정하지 않으면 서버는 **조직 전체 축(`siteId=null`)과 사업장별 분해를 함께** 준다.
 * 두 축을 그냥 더하면 이중집계가 된다 — 요약은 조직 전체 축만 합산한다.
 */
export function ForecastSummary({ orgId, base }: { orgId: string; base: string }) {
  const forecast = useApi<ForecastResponse>(orgPath(orgId, "/forecast"), [orgId]);
  const backtest = useApi<BacktestResponse>(orgPath(orgId, "/forecast/backtest"), [orgId]);

  const orgCells = (forecast.data?.cells ?? []).filter((cell) => cell.siteId === null);
  const noData = orgCells.filter((cell) => cell.predicted === null).length;
  const covered = orgCells.length - noData;
  const total = orgCells.reduce((sum, cell) => sum + (cell.predicted ?? 0), 0);

  const byWindow = new Map<string, { predicted: number; noData: number }>();
  for (const cell of orgCells) {
    const bucket = byWindow.get(cell.mealWindow) ?? { predicted: 0, noData: 0 };
    if (cell.predicted === null) bucket.noData += 1;
    else bucket.predicted += cell.predicted;
    byWindow.set(cell.mealWindow, bucket);
  }
  const knownWindows = MEAL_WINDOW_OPTIONS.map((option) => option.value);
  const windows = [
    ...knownWindows.filter((value) => byWindow.has(value)),
    ...[...byWindow.keys()].filter((value) => !knownWindows.includes(value)),
  ];

  const summary = backtest.data?.summary;

  return (
    <Section
      title="다음 주 식수 예측"
      description="조직 타임존 기준 내일부터 7일, 조직 전체 축(사업장 합이 아닌 총식수)입니다. 단위는 인분입니다."
      action={
        <ButtonLink variant="outline" size="sm" href={`${base}/forecast`}>
          예측 탭
        </ButtonLink>
      }
    >
      {forecast.error && <ErrorNotice message={forecast.error} onRetry={forecast.reload} />}
      {forecast.loading && <RowsSkeleton rows={3} />}

      {forecast.data && orgCells.length === 0 && (
        <p className="text-sm text-muted-foreground">예측할 셀이 없습니다.</p>
      )}

      {orgCells.length > 0 && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat
              label="7일 합계 예측"
              /* 값 없음은 `NoValue` 로 — 리터럴 대시는 `text-metric`(28px 본문색)으로 커져
                 로딩 실패처럼 보인다(값 자리에서만 그렇다. 표 셀의 작은 회색 대시는 문제없다). */
              value={
                covered === 0 ? <NoValue reason="산출 가능한 셀이 없습니다" /> : `${formatCount(total)}인분`
              }
              hint={
                covered === 0
                  ? "산출 가능한 셀이 없습니다(데이터 없음)"
                  : `${formatDate(forecast.data?.from)} ~ ${formatDate(forecast.data?.to)} · 산출된 ${formatCount(covered)}개 셀만 합산`
              }
            />
            {/*
              ★"21 / 21" 같은 **숫자쌍은 비율이지 값이 아니다**. 두 수를 읽고 나눠야 상태를 알 수 있어서,
              한눈에 보라고 만든 대시보드가 오히려 계산을 요구했다. 값은 결손 수 하나로 두고 비율은
              막대가 말한다 — 여기서는 결손이 **많을수록 나쁘므로** 색도 그에 맞춘다.
            */}
            <Stat
              label="데이터 없는 셀"
              value={formatCount(noData)}
              tone={noData === 0 ? "success" : noData === orgCells.length ? "warning" : "default"}
              hint={
                <ProgressMeter
                  name="데이터 없는 셀 비율"
                  value={noData}
                  max={orgCells.length}
                  tone={noData === 0 ? "success" : noData === orgCells.length ? "warning" : "default"}
                  caption={
                    noData === 0
                      ? `전체 ${formatCount(orgCells.length)}셀 · 모든 끼니·날짜에 산출 근거가 있습니다`
                      : `전체 ${formatCount(orgCells.length)}셀 · 이 셀들은 0인분이 아니라 '알 수 없음'입니다`
                  }
                />
              }
            />
            <Stat
              label="예측 정확도 (MAPE)"
              value={
                backtest.loading ? (
                  "…"
                ) : summary?.mape === null || summary?.mape === undefined ? (
                  <NoValue reason="백테스트 결과가 없습니다" />
                ) : (
                  formatRatio(summary.mape)
                )
              }
              hint={
                summary
                  ? `최근 28일 백테스트 · 채점 ${formatCount(summary.scoredCells)}셀 · 실적 0으로 제외 ${formatCount(summary.mapeExcludedZeroActual)}셀`
                  : "백테스트 결과가 없으면 정확도를 알 수 없습니다"
              }
            />
            <Stat
              label="편향 (bias)"
              value={
                backtest.loading ? (
                  "…"
                ) : summary?.bias === null || summary?.bias === undefined ? (
                  <NoValue reason="채점 가능한 실적이 없습니다" />
                ) : (
                  formatRatio(summary.bias)
                )
              }
              hint={
                backtest.loading
                  ? "불러오는 중"
                  : summary?.bias === null || summary?.bias === undefined
                    ? "채점 가능한 실적이 없습니다"
                    : // 0 은 "치우침 없음"이다. 과소예측으로 뭉뚱그리면 편향이 없는 예측에
                      // 품절 경고가 붙어, 관리자가 근거 없이 식수를 올려 잡게 된다.
                      summary.bias > 0
                      ? "과대예측 경향 — 잔반 위험"
                      : summary.bias < 0
                        ? "과소예측 경향 — 품절 위험"
                        : "치우침 없음"
              }
            />
          </div>

          {backtest.error && (
            <ErrorNotice
              message={`정확도를 불러오지 못했습니다: ${backtest.error}`}
              onRetry={backtest.reload}
            />
          )}

          <TableScroll>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>끼니</TableHead>
                  <TableHead className="text-right">7일 합계</TableHead>
                  <TableHead className="text-right">데이터 없는 날</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {windows.map((value) => {
                  const bucket = byWindow.get(value)!;
                  return (
                    <TableRow key={value}>
                      <TableCell>{mealWindowLabel(value)}</TableCell>
                      <TableCell className="tabular text-right">
                        {bucket.predicted === 0 && bucket.noData > 0 ? (
                          <span className="text-muted-foreground">데이터 없음</span>
                        ) : (
                          `${formatCount(bucket.predicted)}인분`
                        )}
                      </TableCell>
                      <TableCell className="tabular text-right">
                        {bucket.noData === 0 ? (
                          <span className="text-muted-foreground">—</span>
                        ) : (
                          <Badge variant="outline">{formatCount(bucket.noData)}일</Badge>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableScroll>

          <DailyStrip cells={orgCells} />
        </>
      )}
    </Section>
  );
}

/** 날짜별 총합(끼니 합산) 한 줄 — 어느 날이 몰리는지 훑어보게 한다. */
function DailyStrip({ cells }: { cells: { date: string; predicted: number | null }[] }) {
  const byDate = new Map<string, { predicted: number; noData: number }>();
  for (const cell of cells) {
    const bucket = byDate.get(cell.date) ?? { predicted: 0, noData: 0 };
    if (cell.predicted === null) bucket.noData += 1;
    else bucket.predicted += cell.predicted;
    byDate.set(cell.date, bucket);
  }
  const days = [...byDate.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  const peak = Math.max(...days.map(([, value]) => value.predicted), 0);

  return (
    // 390px 에서 2열(카드 폭 ~155px)이면 '2026.07.29 (수)'가 한 줄에 들어간다. 1열이면 스트립의 의미가 사라진다.
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
      {days.map(([date, value]) => (
        <div key={date} className="rounded-lg border border-border bg-card px-3 py-2">
          <p className="text-xs text-muted-foreground">
            {formatDate(date)} ({weekdayOf(date)})
          </p>
          <p className="tabular mt-1 text-sm font-semibold text-foreground">
            {value.predicted === 0 && value.noData > 0 ? (
              <span className="font-normal text-muted-foreground">데이터 없음</span>
            ) : (
              <>
                {formatCount(value.predicted)}
                <span className="ml-0.5 text-xs font-normal text-muted-foreground">인분</span>
              </>
            )}
          </p>
          {peak > 0 && value.predicted === peak && value.noData === 0 && (
            <p className="mt-0.5 text-xs text-muted-foreground">주간 최대</p>
          )}
        </div>
      ))}
    </div>
  );
}
