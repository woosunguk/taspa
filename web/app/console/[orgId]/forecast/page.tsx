"use client";

import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import { useApi } from "@/lib/useApi";
import { Choice, Field, Section, Stat, TableScroll, type Option } from "../../_components/console-ui";
import { orgPath, useOrg } from "../../_lib/org-context";
import {
  MEAL_WINDOW_OPTIONS,
  forecastMethodLabel,
  formatCount,
  formatDate,
  formatRatio,
  isoDateOffset,
  mealWindowLabel,
  weekdayOf,
} from "../../_lib/labels";
import type { BacktestResponse, ForecastCell, ForecastResponse, Site } from "../../_lib/types";

/** 서버 상한(ForecastService) — 넘겨 보내면 400 이므로 화면에서 먼저 안내한다. */
const MAX_FORECAST_DAYS = 31;
const MAX_BACKTEST_DAYS = 92;

/**
 * 예측 탭 — 끼니×사업장×일 그레인의 식수 예측과, 같은 방법을 과거 구간에 적용한 백테스트.
 *
 * **`predicted`가 null 인 셀은 "0명"이 아니라 "데이터 없음"이다.** 0 으로 표시하면 발주 담당자가
 * "오늘은 아무도 안 먹는다"로 읽고 식재료를 준비하지 않는다 — 표에서 시각적으로 확실히 구분한다.
 */
export default function ForecastPage() {
  const { orgId } = useOrg();
  const sites = useApi<Site[]>(orgPath(orgId, "/sites"), [orgId]);
  const siteOptions: Option[] = (sites.data ?? []).map((site) => ({
    value: site.id,
    label: site.name,
  }));
  const siteName = useMemo(() => {
    const map = new Map<string, string>();
    for (const site of sites.data ?? []) map.set(site.id, site.name);
    return map;
  }, [sites.data]);

  return (
    <div className="flex flex-col gap-5">
      <ForecastSection orgId={orgId} siteOptions={siteOptions} siteName={siteName} />
      <BacktestSection orgId={orgId} siteOptions={siteOptions} />
    </div>
  );
}

function buildQuery(params: Record<string, string | null>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) query.set(key, value);
  }
  const encoded = query.toString();
  return encoded ? `?${encoded}` : "";
}

/** 두 날짜 사이 일수(포함). 잘못된 입력이면 null. */
function daysBetween(from: string, to: string): number | null {
  if (!from || !to) return null;
  const start = Date.parse(`${from}T00:00:00`);
  const end = Date.parse(`${to}T00:00:00`);
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return Math.floor((end - start) / 86_400_000) + 1;
}

function ForecastSection({
  orgId,
  siteOptions,
  siteName,
}: {
  orgId: string;
  siteOptions: Option[];
  siteName: Map<string, string>;
}) {
  const [from, setFrom] = useState(isoDateOffset(1));
  const [to, setTo] = useState(isoDateOffset(7));
  const [siteId, setSiteId] = useState<string | null>(null);
  const [mealWindow, setMealWindow] = useState<string | null>(null);

  const span = daysBetween(from, to);
  const invalidRange = span !== null && (span <= 0 || span > MAX_FORECAST_DAYS);

  const path = invalidRange
    ? null
    : orgPath(orgId, `/forecast${buildQuery({ from, to, siteId, mealWindow })}`);
  const forecast = useApi<ForecastResponse>(path);
  // 셀은 있는데 전부 산출 불가 = "실적이 아직 없다". 조건 탓(cells 0)과 구분해야 안내 문구가 맞는다.
  const forecastCells = forecast.data?.cells ?? [];
  const allMissing = forecastCells.length > 0 && forecastCells.every((cell) => cell.predicted === null);
  // ★전부 NO_DATA 의 원인이 하나가 아니다. **휴일 근거 제외**로도 이렇게 되는데, 그 사유를 설명하는
  //   유일한 표시(휴일 배지·basis 열)가 표 안에 있어서 표를 숨기면 반증할 방법이 사라진다.
  const holidayBlocked = forecastCells.some((cell) => cell.basis.excludedHolidayBasis > 0);

  return (
    <Section
      title="식수 예측"
      description="전주 같은 요일 실적에 재실 인원 변화를 반영해 산출합니다. 사업장을 고르지 않으면 조직 전체와 사업장별 분해를 함께 보여줍니다."
    >
      <Filters
        from={from}
        to={to}
        onFrom={setFrom}
        onTo={setTo}
        siteId={siteId}
        onSite={setSiteId}
        mealWindow={mealWindow}
        onMealWindow={setMealWindow}
        siteOptions={siteOptions}
        idPrefix="forecast"
      />

      {invalidRange && (
        <p className="text-sm text-destructive">
          조회 기간이 잘못됐습니다. 시작일이 종료일보다 앞서야 하고, 최대 {MAX_FORECAST_DAYS}일까지 조회할 수
          있습니다.
        </p>
      )}

      {forecast.error && <ErrorNotice message={forecast.error} onRetry={forecast.reload} />}
      {forecast.loading && <RowsSkeleton rows={5} />}

      {!forecast.loading && forecast.data?.cells.length === 0 && (
        <EmptyState title="예측할 셀이 없습니다" description="기간·사업장·끼니 조건을 바꿔 보세요." />
      )}

      {/*
        ★"조건에 맞는 셀이 없다"와 "셀은 있는데 근거가 될 실적이 하나도 없다"는 **다른 상태**다.
        서버는 조건에 맞으면 셀을 항상 만들므로, 신규 조직은 위 분기에 걸리지 않고 'NO_DATA' 로
        도배된 표를 본다 — 처음 여는 화면이 고장난 것처럼 보이고 무엇을 하면 채워지는지도 없다.
      */}
      {!forecast.loading && allMissing && (
        <EmptyState
          title={holidayBlocked ? "휴일 근거가 없어 예측할 수 없습니다" : "아직 예측할 실적이 없습니다"}
          description={
            holidayBlocked
              ? "휴일 여부가 같은 과거 실적이 없어 근거로 쓸 날을 찾지 못했습니다. 같은 휴일의 실적이 한 번 쌓이면 채워집니다. 아래 표의 '근거' 열에서 제외된 후보 수를 확인할 수 있습니다."
              : "식권 결제가 쌓이면 전주 같은 요일 실적을 근거로 예측이 나타납니다. 보통 결제가 시작되고 한 주가 지나면 채워집니다."
          }
        />
      )}

      {/*
        빈 상태는 표를 **대체하지 않는다** — 사유(휴일 배지·근거 열)가 표 안에만 있다.
        ★단 **보여 줄 사유 자체가 없을 때**는 예외다: 실적이 통째로 없는 신규 조직에서는 근거 열이
        전부 비어 있어 'NO_DATA' 105행이 순수한 소음이고, 바로 위의 빈 상태 안내를 밀어낸다
        (화면이 "아직 없습니다"라고 말한 직후 고장난 것처럼 보이는 표를 그린다).
        휴일 때문에 막힌 경우는 근거 열에 제외 수가 있으므로 그대로 보여준다.
      */}
      {(forecast.data?.cells.length ?? 0) > 0 && (!allMissing || holidayBlocked) && (
        <CellTable cells={forecast.data!.cells} siteName={siteName} />
      )}
    </Section>
  );
}

/**
 * 조직 캘린더가 휴일로 표시한 날의 배지. **예측값을 대신하지 않는다** — 휴일이라는 사실만 알리고
 * 숫자는 그대로 둔다(휴일에도 당직 식사가 있으므로 0 으로 읽히면 안 된다).
 * 휴일 basis 가 제외돼 방법이 강등된 경우도 함께 알려 "왜 근거가 약한지"를 화면에서 설명한다.
 */
function HolidayTag({ cell }: { cell: ForecastCell }) {
  if (!cell.holiday && cell.basis.excludedHolidayBasis === 0) return null;
  return (
    <span className="ml-2 inline-flex align-middle">
      <Badge variant="outline" title={holidayHint(cell)}>
        {cell.holiday ? (cell.holidayName ?? "휴일") : "휴일 근거 제외"}
      </Badge>
    </span>
  );
}

function holidayHint(cell: ForecastCell): string {
  const excluded = cell.basis.excludedHolidayBasis;
  const base = cell.holiday
    ? "조직 캘린더의 휴일입니다. 예측값은 휴일 실적을 근거로 산출되며 0으로 단정하지 않습니다."
    : "";
  const note = excluded > 0 ? `휴일 여부가 달라 과거 ${excluded}개 주를 산출 근거에서 제외했습니다.` : "";
  return [base, note].filter(Boolean).join(" ");
}

function CellTable({ cells, siteName }: { cells: ForecastCell[]; siteName: Map<string, string> }) {
  return (
    <TableScroll>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>날짜</TableHead>
            <TableHead>사업장</TableHead>
            <TableHead>끼니</TableHead>
            <TableHead className="text-right">예측 식수</TableHead>
            <TableHead>산출 방법</TableHead>
            <TableHead className="text-right">전주 실적</TableHead>
            <TableHead className="text-right">재실(현재/전주)</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {cells.map((cell, index) => (
            <TableRow key={`${cell.date}-${cell.siteId ?? "all"}-${cell.mealWindow}-${index}`}>
              <TableCell className="tabular whitespace-nowrap">
                {formatDate(cell.date)} ({weekdayOf(cell.date)})
                <HolidayTag cell={cell} />
              </TableCell>
              <TableCell>
                {cell.siteId ? (
                  (siteName.get(cell.siteId) ?? "(삭제된 사업장)")
                ) : (
                  <span className="text-muted-foreground">조직 전체</span>
                )}
              </TableCell>
              <TableCell>{mealWindowLabel(cell.mealWindow)}</TableCell>
              <TableCell className="tabular text-right">
                {cell.predicted === null ? (
                  <span className="text-muted-foreground">데이터 없음</span>
                ) : (
                  <span className="font-medium">{formatCount(cell.predicted)}</span>
                )}
              </TableCell>
              <TableCell>
                <Badge variant={cell.method === "NO_DATA" ? "outline" : "secondary"}>
                  {forecastMethodLabel(cell.method)}
                </Badge>
              </TableCell>
              <TableCell className="tabular text-right">{formatCount(cell.basis.lastWeekActual)}</TableCell>
              <TableCell className="tabular text-right whitespace-nowrap">
                {formatCount(cell.basis.headcountNow)} / {formatCount(cell.basis.headcountLastWeek)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableScroll>
  );
}

/**
 * 백테스트 — 같은 예측 방법을 과거에 적용해 실적과 비교한다. 사업장을 고르지 않으면 서버가 조직 전체
 * 축만 평가한다(사업장 합과 조직 총합을 한 지표에 섞으면 이중집계가 된다).
 */
function BacktestSection({ orgId, siteOptions }: { orgId: string; siteOptions: Option[] }) {
  const [from, setFrom] = useState(isoDateOffset(-28));
  const [to, setTo] = useState(isoDateOffset(-1));
  const [siteId, setSiteId] = useState<string | null>(null);
  const [mealWindow, setMealWindow] = useState<string | null>(null);

  const span = daysBetween(from, to);
  const invalidRange = span !== null && (span <= 0 || span > MAX_BACKTEST_DAYS);

  const path = invalidRange
    ? null
    : orgPath(orgId, `/forecast/backtest${buildQuery({ from, to, siteId, mealWindow })}`);
  const backtest = useApi<BacktestResponse>(path);
  const summary = backtest.data?.summary;
  // 예측과 같은 구분: 셀은 있는데 채점된 것이 하나도 없으면 "평가할 실적이 없다"이다.
  const backtestCells = backtest.data?.cells ?? [];
  // ★`scoredCells` 는 **예측을 산출한 셀 수**이지 실적 유무가 아니다(ForecastService 는 predicted 가
  //   null 이면 채점을 건너뛴다). 실적이 있어도 근거가 없으면 0 이 되므로 두 원인을 갈라 말해야 한다.
  const hasActual = backtestCells.some((cell) => cell.actual > 0);
  const backtestAllMissing = backtestCells.length > 0 && (summary?.scoredCells ?? 0) === 0;

  return (
    <Section
      title="예측 정확도(백테스트)"
      description="어제까지의 과거 구간만 평가합니다. 실적이 0인 셀은 MAPE 분모에서 제외되며 그 개수를 함께 보여줍니다."
    >
      <Filters
        from={from}
        to={to}
        onFrom={setFrom}
        onTo={setTo}
        siteId={siteId}
        onSite={setSiteId}
        mealWindow={mealWindow}
        onMealWindow={setMealWindow}
        siteOptions={siteOptions}
        idPrefix="backtest"
      />

      {invalidRange && (
        <p className="text-sm text-destructive">
          조회 기간이 잘못됐습니다. 시작일이 종료일보다 앞서야 하고, 최대 {MAX_BACKTEST_DAYS}일까지 평가할 수
          있습니다.
        </p>
      )}

      {backtest.error && <ErrorNotice message={backtest.error} onRetry={backtest.reload} />}
      {backtest.loading && <RowsSkeleton rows={4} />}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="MAPE(평균 절대 백분율 오차)"
            value={formatRatio(summary.mape)}
            hint={
              summary.mape === null
                ? "채점된 셀이 없어 산출할 수 없습니다(예측 근거 부재 또는 실적 0 제외)"
                : `채점 ${summary.scoredCells}셀 · 실적 0으로 제외 ${summary.mapeExcludedZeroActual}셀`
            }
          />
          <Stat
            label="WAPE(가중 절대 오차)"
            value={formatRatio(summary.wape)}
            hint={summary.wape === null ? "실적이 없어 산출할 수 없습니다" : undefined}
          />
          <Stat
            label="편향(bias)"
            value={formatRatio(summary.bias)}
            hint={
              summary.bias === null
                ? "실적이 없어 산출할 수 없습니다"
                : // 0 은 치우침 없음이다 — 과소예측으로 묶으면 편향 없는 예측에 품절 경고가 붙는다.
                  summary.bias > 0
                  ? "과대예측 경향(잔반 위험)"
                  : summary.bias < 0
                    ? "과소예측 경향(품절 위험)"
                    : "치우침 없음"
            }
          />
          <Stat
            label="평가 대상 셀"
            value={formatCount(summary.cells)}
            hint={`채점 ${formatCount(summary.scoredCells)}셀`}
          />
        </div>
      )}

      {!backtest.loading && backtest.data?.cells.length === 0 && (
        <EmptyState title="평가할 셀이 없습니다" description="조회 기간을 넓혀 보세요." />
      )}

      {!backtest.loading && backtestAllMissing && (
        <EmptyState
          title={hasActual ? "정확도를 계산할 수 없습니다" : "평가할 실적이 없습니다"}
          description={
            hasActual
              ? "이 기간의 실적은 있지만 예측 근거(전주·최근 4주 같은 요일)가 없어 정확도를 계산할 수 없습니다. 아래 표에서 실적을 그대로 확인할 수 있습니다."
              : "이 기간에 승인된 식권 거래가 없어 예측 정확도를 계산할 수 없습니다. 결제 실적이 쌓인 뒤 다시 확인해 주세요."
          }
        />
      )}

      {/* 실적 열은 계속 보여준다 — 위 안내의 반증(실적이 있는데 채점이 0)을 화면에서 확인할 수 있어야 한다. */}
      {(backtest.data?.cells.length ?? 0) > 0 && (
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
              {backtest.data?.cells.map((cell, index) => {
                const diff = cell.predicted === null ? null : cell.predicted - cell.actual;
                return (
                  <TableRow key={`${cell.date}-${cell.mealWindow}-${index}`}>
                    <TableCell className="tabular whitespace-nowrap">
                      {formatDate(cell.date)} ({weekdayOf(cell.date)})
                      <HolidayTag cell={cell} />
                    </TableCell>
                    <TableCell>{mealWindowLabel(cell.mealWindow)}</TableCell>
                    <TableCell className="tabular text-right">
                      {cell.predicted === null ? (
                        <span className="text-muted-foreground">데이터 없음</span>
                      ) : (
                        formatCount(cell.predicted)
                      )}
                    </TableCell>
                    <TableCell className="tabular text-right">{formatCount(cell.actual)}</TableCell>
                    <TableCell className="tabular text-right">
                      {diff === null ? "—" : diff > 0 ? `+${formatCount(diff)}` : formatCount(diff)}
                    </TableCell>
                    <TableCell>
                      <Badge variant={cell.method === "NO_DATA" ? "outline" : "secondary"}>
                        {forecastMethodLabel(cell.method)}
                      </Badge>
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

function Filters({
  from,
  to,
  onFrom,
  onTo,
  siteId,
  onSite,
  mealWindow,
  onMealWindow,
  siteOptions,
  idPrefix,
}: {
  from: string;
  to: string;
  onFrom: (value: string) => void;
  onTo: (value: string) => void;
  siteId: string | null;
  onSite: (value: string | null) => void;
  mealWindow: string | null;
  onMealWindow: (value: string | null) => void;
  siteOptions: Option[];
  idPrefix: string;
}) {
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <Field label="시작일" htmlFor={`${idPrefix}-from`}>
        <Input
          id={`${idPrefix}-from`}
          type="date"
          value={from}
          onChange={(event) => onFrom(event.target.value)}
        />
      </Field>
      <Field label="종료일" htmlFor={`${idPrefix}-to`}>
        <Input id={`${idPrefix}-to`} type="date" value={to} onChange={(event) => onTo(event.target.value)} />
      </Field>
      <Field label="사업장" htmlFor={`${idPrefix}-site`}>
        <Choice
          id={`${idPrefix}-site`}
          value={siteId}
          onChange={onSite}
          options={siteOptions}
          emptyLabel="조직 전체"
        />
      </Field>
      <Field label="끼니" htmlFor={`${idPrefix}-window`}>
        <Choice
          id={`${idPrefix}-window`}
          value={mealWindow}
          onChange={onMealWindow}
          options={MEAL_WINDOW_OPTIONS}
          emptyLabel="전체"
        />
      </Field>
    </div>
  );
}
