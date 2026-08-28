/**
 * 개요 화면의 여러 구획이 함께 쓰는 순수 계산.
 *
 * 여기 있는 함수는 전부 **화면 밖(서버)에서 이미 정해진 값을 다시 계산하지 않는다** — 날짜 버킷·집계 창은
 * 서버가 매장 타임존으로 잘라 내려준 것을 그대로 쓰고, 이 파일은 그것을 사람이 읽는 단위(하루·끼니)로
 * 접기만 한다. 재계산하는 순간 화면과 서버가 다른 숫자를 말하게 된다.
 */

import { MEAL_WINDOWS, forecastMethodLabel, formatCount } from "../../_lib/format";
import type { MerchantForecastCell, MerchantTransaction } from "../../_lib/types";

export interface DayRow<T extends MerchantForecastCell> {
  date: string;
  byWindow: Map<string, T>;
}

/** 서버는 (날짜 × 끼니) 평면 배열을 준다. 매장이 읽는 단위는 "하루"라 날짜로 접어서 보여준다. */
export function groupByDate<T extends MerchantForecastCell>(cells: T[]): DayRow<T>[] {
  const rows: DayRow<T>[] = [];
  const index = new Map<string, DayRow<T>>();
  for (const cell of cells) {
    let row = index.get(cell.date);
    if (!row) {
      row = { date: cell.date, byWindow: new Map() };
      index.set(cell.date, row);
      rows.push(row);
    }
    row.byWindow.set(cell.mealWindow, cell);
  }
  return rows;
}

/** 특정 날짜의 끼니별 셀을 MEAL_WINDOWS 순서로. 없는 끼니는 undefined(=조회 구간 밖)로 남긴다. */
export function cellsOfDate<T extends MerchantForecastCell>(
  cells: T[] | undefined,
  date: string | null,
): (T | undefined)[] {
  if (!cells || !date) return MEAL_WINDOWS.map(() => undefined);
  return MEAL_WINDOWS.map((window) => cells.find((cell) => cell.date === date && cell.mealWindow === window));
}

export interface DayTotal {
  /** 아는 끼니만 더한 합계. 하나도 모르면 null(0 이 아니다). */
  total: number | null;
  /** 데이터 없음(NO_DATA) 또는 조회 구간 밖이라 합계에서 빠진 끼니 수. */
  missing: number;
}

/** 하루 합계 — 데이터 없는 끼니는 **0 으로 더하지 않고** 빠진 개수로 드러낸다. */
export function sumPredicted(cells: (MerchantForecastCell | undefined)[]): DayTotal {
  let total = 0;
  let known = 0;
  let missing = 0;
  for (const cell of cells) {
    if (!cell || cell.predicted === null) missing += 1;
    else {
      total += cell.predicted;
      known += 1;
    }
  }
  return { total: known === 0 ? null : total, missing };
}

export function sumPredictedOfRow(row: DayRow<MerchantForecastCell>): DayTotal {
  return sumPredicted(MEAL_WINDOWS.map((window) => row.byWindow.get(window)));
}

/** 예측을 믿을지 판단하려면 "무엇을 근거로 뽑았는지"가 숫자 옆에 있어야 한다. */
export function basisText(cell: MerchantForecastCell): string {
  if (cell.method === "SEASONAL_NAIVE") {
    return `전주 같은 요일 실적 ${formatCount(cell.basis.lastWeekActual)}인분을 그대로 사용`;
  }
  if (cell.method === "FOUR_WEEK_AVG") {
    return `전주 실적이 없어 최근 4주 같은 요일 평균 (표본 ${cell.basis.sampleWeeks}주)`;
  }
  return forecastMethodLabel(cell.method);
}

/** 표 안의 좁은 자리에 들어갈 짧은 근거. */
export function methodShort(cell: MerchantForecastCell): string {
  if (cell.method === "SEASONAL_NAIVE") return "전주 동요일";
  if (cell.method === "FOUR_WEEK_AVG") return `4주 평균 (${cell.basis.sampleWeeks}주)`;
  return forecastMethodLabel(cell.method);
}

/* ------------------------------------------------------------------ 장부(거래) 집계 */

export interface LedgerTotals {
  approvedCount: number;
  voidedCount: number;
  amount: number;
  orgPaid: number;
  selfPaid: number;
  /** 끼니별 승인 건수(취소 제외). */
  approvedByWindow: Map<string, number>;
}

/**
 * 거래 합계 — **불러온 행 기준**이다. 서버가 행 수 상한(`rowsTruncated`)을 알려주면 호출부가 그 사실을
 * 반드시 함께 표시해야 한다. 취소(VOIDED)된 건은 금액·건수에서 빼되 개수는 따로 보여준다(매장이 취소가
 * 있었다는 사실 자체를 알아야 한다).
 */
export function summarizeLedger(rows: MerchantTransaction[]): LedgerTotals {
  const totals: LedgerTotals = {
    approvedCount: 0,
    voidedCount: 0,
    amount: 0,
    orgPaid: 0,
    selfPaid: 0,
    approvedByWindow: new Map(),
  };
  for (const row of rows) {
    if (row.status === "VOIDED") {
      totals.voidedCount += 1;
      continue;
    }
    totals.approvedCount += 1;
    totals.amount += row.amountMinor;
    totals.orgPaid += row.orgPaidMinor;
    totals.selfPaid += row.selfPaidMinor;
    totals.approvedByWindow.set(row.mealWindow, (totals.approvedByWindow.get(row.mealWindow) ?? 0) + 1);
  }
  return totals;
}

/** 하루치 신호 요약 — 조직 분해 조각에서 "왜 이 숫자인가"를 접어 올린다(순수 함수 — 회귀 테스트 대상). */
export interface DaySignals {
  /** "휴일명 (조직명)" — 같은 항목은 한 번만. */
  holidays: string[];
  events: string[];
  /** 그 날 전 조직 부재 가중 합(연차·반차). */
  absentWeight: number;
  /** 일부 조직 근거 없음 — 합계가 하한이라는 뜻. */
  partial: boolean;
}

export function daySignalsOf<T extends MerchantForecastCell>(row: DayRow<T>): DaySignals {
  const holidays = new Set<string>();
  const events = new Set<string>();
  const absentByOrg = new Map<string, number>();
  let partial = false;
  for (const cell of row.byWindow.values()) {
    if (cell.partial) partial = true;
    for (const slice of cell.orgs ?? []) {
      if (slice.holiday) holidays.add(`${slice.holidayName ?? "휴일"} (${slice.orgName})`);
      if (slice.event) events.add(`${slice.eventName ?? "행사"} (${slice.orgName})`);
      // 부재는 날짜 속성이라 끼니마다 같은 값이 반복된다 — 조직당 한 번만 센다(끼니 수만큼 부풀리지 않는다).
      if (slice.absentWeight > 0) absentByOrg.set(slice.orgId, slice.absentWeight);
    }
  }
  let absentWeight = 0;
  for (const weight of absentByOrg.values()) absentWeight += weight;
  return { holidays: [...holidays], events: [...events], absentWeight, partial };
}
