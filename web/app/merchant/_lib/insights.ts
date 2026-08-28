import { MEAL_WINDOWS, isWeekend, mealWindowLabel, weekdayOf } from "./format";
import type { MerchantBacktestCell, MerchantForecastCell, MerchantTransaction } from "./types";

/**
 * 밀로그·잔반 패널의 계산을 **JSX 밖 순수 함수로** 둔다.
 *
 * 이 저장소가 이미 같은 대가를 치렀다: 정산 요약(`summarize.ts`)과 사용내역 금액(`amounts.ts`)이
 * 컴포넌트 안에 있던 동안 "취소 거래를 어떻게 세는가" 같은 규칙이 화면마다 갈렸고, 테스트를 쓸 수
 * 없어 아무도 그 사실을 몰랐다. 그래서 규칙은 여기, 표현은 컴포넌트에 둔다.
 */

export interface DailyPortions {
  /** x축 라벨(MM-DD). */
  label: string;
  segments: { name: string; value: number }[];
}

/** ISO 인스턴트 → 매장 타임존의 `YYYY-MM-DD`. 타임존이 없으면 브라우저 로컬로 떨어진다. */
export function bucketDate(iso: string, timezone?: string | null): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone ?? undefined,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

/**
 * 일자 × 끼니 식수 집계.
 *
 * ★날짜 버킷은 **매장 타임존**이다. 브라우저 로컬로 자르면 자정 근처 거래가 옆 날짜로 넘어가, 같은
 *   화면의 표(서버가 매장 달력으로 필터한 것)와 그래프가 서로 다른 말을 한다.
 * ★취소(VOIDED)는 세지 않는다 — "몇 인분 나갔나"를 보는 그래프이므로 금액축과 같은 기준이다.
 * ★세그먼트 순서는 전 막대에서 동일해야 색이 흔들리지 않으므로 MEAL_WINDOWS 순서를 그대로 쓰고,
 *   기간 전체에서 한 번도 안 나온 끼니만 제거한다(막대별로 제거하면 색이 밀린다).
 */
export function dailyPortions(rows: MerchantTransaction[], timezone?: string | null): DailyPortions[] {
  const buckets = new Map<string, Map<string, number>>();
  for (const row of rows) {
    if (row.status !== "APPROVED") continue;
    const key = bucketDate(row.approvedAt, timezone);
    const perWindow = buckets.get(key) ?? new Map<string, number>();
    perWindow.set(row.mealWindow, (perWindow.get(row.mealWindow) ?? 0) + 1);
    buckets.set(key, perWindow);
  }
  const used = MEAL_WINDOWS.filter((w) => [...buckets.values()].some((per) => (per.get(w) ?? 0) > 0));
  return [...buckets.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, perWindow]) => ({
      label: date.slice(5),
      segments: used.map((w) => ({ name: mealWindowLabel(w), value: perWindow.get(w) ?? 0 })),
    }));
}

export interface WasteReduction {
  /** 채점에 쓴 셀 수(끼니 × 날짜). */
  days: number;
  /** 끼니별 무예측 기준선 = 그 끼니의 기간 최대 실적. */
  peaks: { name: string; value: number }[];
  forecastOver: number;
  baselineOver: number;
  shortfall: number;
  saved: number;
  rate: number | null;
  /** 실적 0(미배식·데이터 공백)으로 제외한 셀 수. 숨기면 이 패널의 분모가 무엇인지 알 수 없다. */
  excludedZeroActual: number;
}

/**
 * 잔반(과잉 준비) 절감 — 예측을 쓴 덕에 덜 준비한 인분 수.
 *
 * 비교 대상이 없으면 "절감"을 말할 수 없다. 그래서 **예측이 없는 주방의 관행**을 하나 가정한다:
 * 품절을 피하려고 그 기간 **최대 실적만큼** 매일 준비한다. 이 가정은 화면에 그대로 적어야 한다 —
 * 숨기면 이 숫자는 마케팅 문구가 되고, 근거를 물으면 답할 수 없다.
 *
 * ★품절 위험(과소예측)도 함께 낸다. 예측은 잔반을 줄이는 대신 모자랄 위험을 만드는데, 좋은 쪽만
 *   보여주면 패널 전체가 신뢰를 잃는다.
 */
export function wasteReduction(cells: MerchantBacktestCell[]): WasteReduction | null {
  const predictedCells = cells.filter((cell) => cell.predicted !== null);
  /*
   * ★실적 0 인 셀은 제외한다. **이걸 빼먹으면 숫자가 방어 불가능해진다** — 배식하지 않은 날(주말·휴무)과
   * 데이터가 끊긴 날에는 주방이 애초에 준비를 하지 않았는데, 포함하면 그 날도 "기준선만큼 준비해서 전부
   * 버렸다"로 계산되어 절감량이 통째로 부풀려진다(실측: 43셀 중 0 실적이 섞여 절감률이 87.5% 로 나왔다).
   *
   * 같은 이유로 이 저장소의 MAPE 도 실적 0 셀을 분모에서 빼고 **제외 수를 함께 노출한다**. 그 관례를
   * 그대로 따른다 — 분모를 줄였다는 사실이 화면에 없으면 비율의 뜻이 달라진 것을 아무도 모른다.
   */
  const scored = predictedCells.filter((cell) => cell.actual > 0);
  if (scored.length === 0) return null;

  /*
   * ★기준선은 **끼니별로** 잡는다. 하나의 전체 최대치를 모든 셀에 쓰면 "점심 최대 22인분"을 저녁에도
   *   준비한다고 계산해 절감량이 다시 부풀려진다(실측: 그 방식에서 절감률이 94% 로 나왔다 — 어떤 주방도
   *   저녁에 점심 최대치를 준비하지 않는다). 끼니는 준비 단위가 다르므로 비교 기준도 끼니 단위다.
   */
  const peakByWindow = new Map<string, number>();
  for (const cell of scored) {
    peakByWindow.set(cell.mealWindow, Math.max(peakByWindow.get(cell.mealWindow) ?? 0, cell.actual));
  }

  let forecastOver = 0;
  let baselineOver = 0;
  let shortfall = 0;
  for (const cell of scored) {
    const predicted = cell.predicted as number;
    const peak = peakByWindow.get(cell.mealWindow) ?? cell.actual;
    forecastOver += Math.max(predicted - cell.actual, 0);
    baselineOver += Math.max(peak - cell.actual, 0);
    shortfall += Math.max(cell.actual - predicted, 0);
  }
  return {
    days: scored.length,
    peaks: [...peakByWindow.entries()]
      .map(([window, value]) => ({ name: mealWindowLabel(window), value }))
      .sort((a, b) => b.value - a.value),
    forecastOver,
    baselineOver,
    shortfall,
    saved: baselineOver - forecastOver,
    rate: baselineOver > 0 ? (baselineOver - forecastOver) / baselineOver : null,
    excludedZeroActual: predictedCells.length - scored.length,
  };
}

/* ------------------------------------------------------------- 예측 vs 실제 추이 */

export interface TrendPoint {
  date: string;
  /** x축 라벨(MM-DD). */
  label: string;
  weekday: string;
  weekend: boolean;
  /** 확정 실적 합계(끼니 전부). 미래 날짜는 null. */
  actual: number | null;
  /** 그 날짜에 대한 예측 합계. 근거가 없으면 null(0 이 아니다). */
  predicted: number | null;
  today: boolean;
}

/**
 * 하루 단위 "예측 vs 실적" 계열 — 과거는 백테스트(그 시점 예측 + 확정 실적), 미래는 예측에서 온다.
 *
 * ★**배식하지 않은 날은 계열에서 뺀다.** 주말·휴무를 0 으로 이으면 선이 매주 바닥까지 떨어져 추세가
 *   보이지 않고, 그 0 은 "손님이 없었다"가 아니라 "운영을 안 했다"라 의미도 다르다. 제품 소개 페이지의
 *   추이 차트도 같은 이유로 영업일만 x축에 올린다(주말 날짜가 축에 없다).
 * ★두 원천이 같은 날짜를 주면 **백테스트를 택한다** — 그쪽이 실적을 함께 갖고 있어 비교가 가능하다.
 */
export function trendSeries(
  backtest: MerchantBacktestCell[],
  forecast: MerchantForecastCell[],
  today: string | null,
): TrendPoint[] {
  const byDate = new Map<string, { actual: number | null; predicted: number | null }>();

  const fold = (cells: MerchantForecastCell[], withActual: boolean) => {
    for (const cell of cells) {
      const entry = byDate.get(cell.date) ?? { actual: null, predicted: null };
      if (cell.predicted !== null) entry.predicted = (entry.predicted ?? 0) + cell.predicted;
      if (withActual) {
        const actual = (cell as MerchantBacktestCell).actual;
        if (typeof actual === "number") entry.actual = (entry.actual ?? 0) + actual;
      }
      byDate.set(cell.date, entry);
    }
  };
  // 미래(예측)를 먼저 접고 과거(백테스트)를 나중에 접어, 겹치는 날짜는 백테스트 값이 남게 한다.
  fold(forecast, false);
  const backtestDates = new Set(backtest.map((c) => c.date));
  for (const date of backtestDates) byDate.set(date, { actual: null, predicted: null });
  fold(backtest, true);

  return (
    [...byDate.entries()]
      /*
       * ★판정 기준은 **실적**이다. "예측이 있으면 남긴다" 로 두면 주말에도 예측이 나오는 경우
       *   (전주 실적이 없어 4주 평균으로 강등된 셀)에 실적 0 인 날이 살아남아 실적선이 바닥까지
       *   떨어진다 — 이 함수가 막으려던 바로 그 그림이다(실측으로 확인).
       *   - 과거(실적을 아는 날): 0 이면 미배식이므로 **버린다**.
       *   - 미래(실적이 null): 예측이 있으면 남긴다.
       */
      .filter(([, v]) => (v.actual === null ? v.predicted !== null : v.actual > 0))
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, v]) => ({
        date,
        label: date.slice(5),
        weekday: weekdayOf(date),
        weekend: isWeekend(date),
        actual: v.actual,
        predicted: v.predicted,
        today: date === today,
      }))
  );
}
