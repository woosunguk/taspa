"use client";

import Link from "next/link";

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState, ErrorNotice, RowsSkeleton } from "@/components/feedback";
import type { Query } from "@/lib/useApi";
import { cn } from "@/lib/utils";
import { useMerchant } from "../../_lib/merchant-context";
import { DonutMeter } from "@/components/charts";
import { Notice, Portions, Section, Stat, TableScroll } from "../../_components/kit";
import {
  MEAL_WINDOWS,
  addDays,
  formatCount,
  formatDate,
  formatWon,
  mealWindowLabel,
  weekdayOf,
} from "../../_lib/format";
import type {
  MerchantForecastCell,
  MerchantForecastResponse,
  MerchantTransactionsResponse,
} from "../../_lib/types";
import { basisText, cellsOfDate, summarizeLedger, sumPredicted } from "./shared";

/**
 * 오늘 준비량 — 매장 사장이 아침에 이 화면을 여는 이유 하나에 답하는 자리다.
 *
 * ★날짜는 전부 **매장 타임존**이다. 브라우저 로컬로 계산한 "오늘"은 매장의 "오늘"과 다를 수 있어,
 * 타임존을 알기 전에는 이 구획이 숫자를 만들어내지 않고 "확인 중"으로 남는다 — 틀린 날의 수량을
 * 자신 있게 보여주는 것보다 비어 있는 편이 낫다.
 *
 * ★`predicted === null`(NO_DATA)은 **0 인분이 아니라 "데이터 없음"** 이다. 0 으로 보이면 매장은 재료를
 * 사지 않는다.
 */
export function TodayForecastSection({
  forecast,
  today,
  timezone,
}: {
  forecast: Query<MerchantForecastResponse>;
  today: string | null;
  timezone: string | null;
}) {
  const data = forecast.data;
  // 서버가 실제로 오늘을 포함해 응답했는지 — 타임존을 몰라 파라미터 없이 물었으면 내일부터 온다.
  const covered = today !== null && !!data && data.from <= today && data.to >= today;
  const cells = covered ? cellsOfDate(data?.cells, today) : [];
  const day = sumPredicted(cells);

  const tomorrow = today ? addDays(today, 1) : null;
  const tomorrowDay = sumPredicted(cellsOfDate(data?.cells, tomorrow));

  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-base font-semibold text-foreground">오늘 몇 인분 준비할까요?</h2>
        {today && (
          <p className="text-sm text-muted-foreground">
            {formatDate(today)} ({weekdayOf(today)}){timezone ? <> · 매장 시간 {timezone} 기준</> : null}
          </p>
        )}
      </div>

      {forecast.error && (
        <div className="mt-4">
          <ErrorNotice message={forecast.error} onRetry={forecast.reload} />
        </div>
      )}

      {!forecast.error && !covered && (
        <div className="mt-4 flex flex-col gap-2">
          <RowsSkeleton rows={3} />
          {/* 로딩이 끝났는데도 오늘 셀이 없다면 기준 날짜를 아직 못 정한 것이다 — 조용히 비우지 않고 말한다. */}
          {!forecast.loading && (
            <p className="text-sm text-muted-foreground">
              매장 타임존을 확인하는 중입니다 — 기준 날짜를 정하기 전에는 수량을 표시하지 않습니다. (매장
              목록에 없는 계정으로 열람 중이면 잠시 후 자동으로 채워집니다.)
            </p>
          )}
        </div>
      )}

      {covered && !forecast.error && (
        <>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            {MEAL_WINDOWS.map((window, index) => (
              <WindowCard key={window} window={window} cell={cells[index]} today={today} />
            ))}
          </div>

          <div className="mt-4 flex flex-wrap items-baseline justify-between gap-2 text-sm text-muted-foreground">
            <p>
              {day.total === null ? (
                <>
                  오늘 예측할 근거가 없습니다 — 최근 4주 실적이 쌓이면 여기에 수량이 표시됩니다.{" "}
                  <b className="font-medium text-foreground">0 인분이라는 뜻이 아닙니다.</b>
                </>
              ) : (
                <>
                  오늘 합계 <b className="tabular font-semibold text-foreground">{formatCount(day.total)}</b>{" "}
                  인분
                  {day.missing > 0 && <> · 데이터 없는 {day.missing}개 끼니는 합계에서 제외</>}
                </>
              )}
            </p>
            {tomorrow && (
              <p>
                내일({formatDate(tomorrow)} {weekdayOf(tomorrow)}) 예상{" "}
                {tomorrowDay.total === null ? (
                  <b className="font-medium text-foreground">데이터 없음</b>
                ) : (
                  <>
                    <b className="tabular font-semibold text-foreground">{formatCount(tomorrowDay.total)}</b>{" "}
                    인분
                  </>
                )}
              </p>
            )}
          </div>
        </>
      )}
    </section>
  );
}

/**
 * 끼니 하나의 예측 카드 — 이 화면에서 가장 큰 글자가 오는 자리다.
 * ★카드 전체가 **근거 상세로 가는 링크**다. "13인분"은 결론이고, 클릭하면 어떤 메뉴가 몇 인분인지 ·
 * 어느 조직 몫이 얼마인지 · 어느 날짜 실적이 근거인지가 나온다.
 */
function WindowCard({
  window,
  cell,
  today,
}: {
  window: string;
  cell: MerchantForecastCell | undefined;
  today: string | null;
}) {
  const noData = !cell || cell.predicted === null;
  const { merchantId } = useMerchant();
  const href = today ? `/merchant/${merchantId}/cell/${today}/${window}` : null;

  const card = (
    <div
      className={cn(
        "rounded-lg border px-4 py-4",
        noData ? "border-dashed border-border bg-muted/30" : "border-border bg-background",
        href && "transition-colors group-hover:border-brand/50",
      )}
    >
      <p className="text-sm font-medium text-muted-foreground">{mealWindowLabel(window)}</p>

      {noData ? (
        <>
          <p className="mt-2 text-2xl font-semibold text-muted-foreground">데이터 없음</p>
          <p className="mt-1 text-xs text-muted-foreground">
            최근 4주 실적이 없어 예측할 수 없습니다. <b className="font-medium">0 인분이 아닙니다.</b>
          </p>
          {/* 예측은 못 해도 **이미 나간 인분**은 사실이다 — 예측 없음이 "아무 일 없음"으로 읽히지 않게. */}
          {cell?.soFar != null && (
            <p className="mt-1 text-xs font-medium text-brand">지금까지 {formatCount(cell.soFar)}인분 나감</p>
          )}
        </>
      ) : (
        <>
          <p className="mt-1 flex items-baseline gap-1">
            <span className="tabular text-4xl font-semibold text-foreground">
              {formatCount(cell.predicted)}
            </span>
            <span className="text-sm text-muted-foreground">인분</span>
          </p>
          <p className="mt-1 text-xs text-muted-foreground">{basisText(cell)}</p>
          {cell.soFar != null && (
            <p className="mt-0.5 text-xs font-medium text-brand">
              지금까지 {formatCount(cell.soFar)}인분 나감
            </p>
          )}
          <p className="mt-1 text-xs text-muted-foreground underline-offset-2 group-hover:underline">
            근거 보기 →
          </p>
        </>
      )}
    </div>
  );

  if (!href) return card;
  return (
    <Link href={href} className="group block" aria-label={`${mealWindowLabel(window)} 예측 근거 보기`}>
      {card}
    </Link>
  );
}

/* ------------------------------------------------------------------ 오늘 진행분 */

/**
 * 오늘 지금까지의 실적 — **아직 진행 중인 부분값**이다.
 *
 * 예측(위 구획)의 근거는 매장-로컬 **어제까지의 완결 실적**만 쓴다(서버 설계). 그래서 여기 숫자는 예측을
 * 만드는 입력이 아니라 "예측 대비 어디쯤 왔나"를 눈으로 보는 용도이며, 화면도 그렇게만 말한다.
 *
 * 원천이 예측과 다르다는 점도 감춰선 안 된다: 예측은 확정 소비 이벤트(인분), 이 구획은 **장부인 식권 결제
 * 내역**(건수·금액)이다. 식권 외 경로로 적재된 식수는 여기 건수에 잡히지 않는다.
 */
export function TodayProgressSection({
  transactions,
  forecast,
  today,
  timezone,
}: {
  transactions: Query<MerchantTransactionsResponse>;
  forecast: Query<MerchantForecastResponse>;
  today: string | null;
  timezone: string | null;
}) {
  const data = transactions.data;
  const rows = data?.rows ?? [];
  const totals = summarizeLedger(rows);
  const forecastCovered =
    today !== null && !!forecast.data && forecast.data.from <= today && forecast.data.to >= today;
  const predictedCells = forecastCovered ? cellsOfDate(forecast.data?.cells, today) : [];
  /**
   * 오늘 예측 대비 진행률 — 도넛으로 보여줄 비율.
   *
   * ★분모가 없거나 0 이면 **그리지 않는다**(null). 예측이 NO_DATA 인 날에 0 으로 나누거나 임의 분모를
   *   쓰면 "0%" 또는 "∞%" 가 되는데, 둘 다 "아직 준비량을 모른다"와는 완전히 다른 말이다.
   */
  const predictedTotal = sumPredicted(predictedCells).total;
  const progressRatio =
    predictedTotal !== null && predictedTotal > 0 ? totals.approvedCount / predictedTotal : null;

  return (
    <Section
      title="오늘 진행 상황"
      description={
        timezone
          ? `매장 시간(${timezone}) 오늘 하루의 식권 결제입니다. 하루가 끝나지 않았으므로 진행 중인 값이며, 식권 외 경로로 적재된 식수는 포함되지 않습니다.`
          : "매장 시간 기준 오늘 하루의 식권 결제입니다. 하루가 끝나지 않았으므로 진행 중인 값입니다."
      }
    >
      {today === null && (
        <Notice>
          매장 타임존을 확인하기 전에는 &lsquo;오늘&rsquo;을 정할 수 없어 조회하지 않았습니다. 브라우저
          시간대로 대신 계산하면 자정 근처에서 하루가 밀립니다.
        </Notice>
      )}

      {data?.rowsTruncated && (
        <Notice>
          표시 건수 상한({formatCount(data.limit)}건)에 도달했습니다 — 아래 합계는{" "}
          <b className="font-medium">불러온 {formatCount(data.limit)}건 기준</b>
          이라 오늘 실제 실적보다 작을 수 있습니다.
        </Notice>
      )}
      {data?.windowTruncated && (
        <Notice>
          요청한 날짜가 서버 조회 창에 걸려 {formatDate(data.from)} ~ {formatDate(data.to)} 로 좁혀졌습니다.
        </Notice>
      )}

      {transactions.error && <ErrorNotice message={transactions.error} onRetry={transactions.reload} />}
      {transactions.loading && !data && <RowsSkeleton rows={3} />}

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="오늘 승인 (건)"
            value={formatCount(totals.approvedCount)}
            hint={
              progressRatio === null
                ? totals.voidedCount > 0
                  ? `취소 ${totals.voidedCount}건 별도`
                  : "진행 중 · 예측이 없어 진행률을 낼 수 없습니다"
                : `예측 ${formatCount(predictedTotal)}인분 대비${
                    totals.voidedCount > 0 ? ` · 취소 ${totals.voidedCount}건 별도` : ""
                  }`
            }
            visual={
              progressRatio === null ? undefined : (
                <DonutMeter
                  ratio={progressRatio}
                  ariaLabel={`오늘 예측 ${predictedTotal}인분 대비 진행률 ${Math.round(progressRatio * 100)}퍼센트`}
                />
              )
            }
          />
          <Stat label="오늘 결제액" value={formatWon(totals.amount)} hint="취소 건 제외 · 진행 중" />
          <Stat label="조직 부담" value={formatWon(totals.orgPaid)} hint="청구 대상" />
          <Stat label="개인 부담" value={formatWon(totals.selfPaid)} hint="한도 초과분" />
        </div>
      )}

      {data && rows.length === 0 && (
        <EmptyState
          title="오늘 아직 결제가 없습니다"
          description="결제는 POS 단말에서 손님의 QR 을 읽을 때 기록됩니다. 위 예측은 그와 무관하게 최근 실적으로 산출한 값입니다."
        />
      )}

      {data && rows.length > 0 && (
        <TableScroll>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>끼니</TableHead>
                <TableHead className="text-right">오늘 예측 (인분)</TableHead>
                <TableHead className="text-right">현재까지 결제 (건)</TableHead>
                <TableHead className="text-right">남은 예상 (인분)</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {MEAL_WINDOWS.map((window, index) => {
                const predicted = predictedCells[index]?.predicted ?? null;
                const done = totals.approvedByWindow.get(window) ?? 0;
                const remaining = predicted === null ? null : Math.max(0, predicted - done);
                return (
                  <TableRow key={window}>
                    <TableCell className="whitespace-nowrap">{mealWindowLabel(window)}</TableCell>
                    <TableCell className="text-right">
                      <Portions value={predicted} emptyLabel={forecastCovered ? "데이터 없음" : "—"} />
                    </TableCell>
                    <TableCell className="tabular text-right font-medium">{formatCount(done)}</TableCell>
                    <TableCell className="tabular text-right">
                      {remaining === null ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        formatCount(remaining)
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
