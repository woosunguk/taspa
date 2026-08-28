import { describe, expect, it } from "vitest";
import { daySignalsOf, groupByDate } from "./shared";
import type { MerchantForecastCell, MerchantOrgSlice } from "../../_lib/types";

function slice(over: Partial<MerchantOrgSlice>): MerchantOrgSlice {
  return {
    orgId: "org-1",
    orgName: "데모",
    predicted: 10,
    method: "SEASONAL_NAIVE",
    holiday: false,
    holidayName: null,
    event: false,
    eventName: null,
    absentWeight: 0,
    ...over,
  };
}

function cell(over: Partial<MerchantForecastCell>): MerchantForecastCell {
  return {
    date: "2026-09-04",
    mealWindow: "LUNCH",
    predicted: 10,
    method: "SEASONAL_NAIVE",
    basis: { lastWeekActual: 10, sampleWeeks: 1 },
    ...over,
  };
}

describe("daySignalsOf", () => {
  it("휴일·행사·부재를 조직 조각에서 접어 올린다", () => {
    const rows = groupByDate([
      cell({
        orgs: [
          slice({ event: true, eventName: "체육대회", absentWeight: 10 }),
          slice({ orgId: "org-2", orgName: "B사", holiday: true, holidayName: "창립기념일" }),
        ],
      }),
    ]);
    const signals = daySignalsOf(rows[0]);
    expect(signals.events).toEqual(["체육대회 (데모)"]);
    expect(signals.holidays).toEqual(["창립기념일 (B사)"]);
    expect(signals.absentWeight).toBe(10);
  });

  it("★부재는 끼니 수만큼 부풀리지 않는다 — 날짜 속성이라 조직당 한 번만 센다", () => {
    const rows = groupByDate([
      cell({ mealWindow: "LUNCH", orgs: [slice({ absentWeight: 10 })] }),
      cell({ mealWindow: "DINNER", orgs: [slice({ absentWeight: 10 })] }),
    ]);
    expect(daySignalsOf(rows[0]).absentWeight).toBe(10); // 20 이 아니다
  });

  it("일부 조직 근거 없음(partial)이 하루로 접힌다", () => {
    const rows = groupByDate([cell({ partial: true }), cell({ mealWindow: "DINNER", partial: false })]);
    expect(daySignalsOf(rows[0]).partial).toBe(true);
  });

  it("신호 없는 날은 전부 비어 있다(대조군)", () => {
    const rows = groupByDate([cell({ orgs: [slice({})] })]);
    const signals = daySignalsOf(rows[0]);
    expect(signals.holidays).toEqual([]);
    expect(signals.events).toEqual([]);
    expect(signals.absentWeight).toBe(0);
    expect(signals.partial).toBe(false);
  });
});
