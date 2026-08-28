import { describe, expect, it } from "vitest";
import { dailyPortions, trendSeries, wasteReduction } from "./insights";
import type { MerchantBacktestCell, MerchantForecastCell, MerchantTransaction } from "./types";

function tx(over: Partial<MerchantTransaction>): MerchantTransaction {
  return {
    authId: "a",
    posTxnId: "p",
    orgName: "조직",
    mealWindow: "LUNCH",
    amountMinor: 12000,
    orgPaidMinor: 12000,
    selfPaidMinor: 0,
    status: "APPROVED",
    approvedAt: "2026-08-20T03:30:00Z",
    ...over,
  } as MerchantTransaction;
}

function cell(predicted: number | null, actual: number, mealWindow = "LUNCH"): MerchantBacktestCell {
  return { predicted, actual, mealWindow } as MerchantBacktestCell;
}

describe("dailyPortions", () => {
  it("매장 타임존 기준으로 날짜를 자른다(로컬로 자르면 자정 근처가 옆 날짜로 넘어간다)", () => {
    // 2026-08-20T16:00Z = KST 2026-08-21 01:00. UTC 로 자르면 20일, 매장(KST)으로 자르면 21일이다.
    const rows = [tx({ authId: "1", approvedAt: "2026-08-20T16:00:00Z" })];

    expect(dailyPortions(rows, "Asia/Seoul")[0].label).toBe("08-21");
    expect(dailyPortions(rows, "UTC")[0].label).toBe("08-20");
  });

  it("취소 거래는 세지 않는다(금액축과 같은 기준)", () => {
    const rows = [tx({ authId: "1" }), tx({ authId: "2", status: "VOIDED" })];

    expect(dailyPortions(rows, "UTC")[0].segments.find((s) => s.name === "중식")!.value).toBe(1);
  });

  it("세그먼트 순서와 개수가 전 막대에서 같다(색이 밀리지 않게)", () => {
    const rows = [
      tx({ authId: "1", approvedAt: "2026-08-20T03:30:00Z", mealWindow: "LUNCH" }),
      tx({ authId: "2", approvedAt: "2026-08-21T09:30:00Z", mealWindow: "DINNER" }),
    ];

    const days = dailyPortions(rows, "UTC");
    expect(days.map((d) => d.label)).toEqual(["08-20", "08-21"]);
    // 기간에 조식이 없으므로 두 막대 모두 중식·석식 두 세그먼트만 갖는다.
    expect(days[0].segments.map((s) => s.name)).toEqual(["중식", "석식"]);
    expect(days[1].segments.map((s) => s.name)).toEqual(["중식", "석식"]);
    expect(days[0].segments.map((s) => s.value)).toEqual([1, 0]);
    expect(days[1].segments.map((s) => s.value)).toEqual([0, 1]);
  });

  it("날짜 오름차순으로 정렬한다", () => {
    const rows = [
      tx({ authId: "1", approvedAt: "2026-08-22T03:30:00Z" }),
      tx({ authId: "2", approvedAt: "2026-08-20T03:30:00Z" }),
    ];

    expect(dailyPortions(rows, "UTC").map((d) => d.label)).toEqual(["08-20", "08-22"]);
  });
});

describe("wasteReduction", () => {
  it("무예측(기간 최대치 준비) 대비 절감량과 절감률을 계산한다", () => {
    // 최대 실적 100 → 무예측이면 매일 100 준비. 과잉 = 0 + 20 + 40 = 60.
    // 예측 과잉 = (100-100) + (82-80) + (63-60) = 0 + 2 + 3 = 5.
    const result = wasteReduction([cell(100, 100), cell(82, 80), cell(63, 60)])!;

    expect(result.peaks).toEqual([{ name: "중식", value: 100 }]);
    expect(result.baselineOver).toBe(60);
    expect(result.forecastOver).toBe(5);
    expect(result.saved).toBe(55);
    expect(result.rate).toBeCloseTo(55 / 60);
  });

  it("과소예측은 절감이 아니라 품절 위험으로 따로 센다(좋은 쪽만 보여주지 않는다)", () => {
    const result = wasteReduction([cell(90, 100), cell(100, 100)])!;

    expect(result.shortfall).toBe(10);
    expect(result.forecastOver).toBe(0); // 모자란 것은 과잉이 아니다
  });

  it("예측이 없는 셀(NO_DATA)은 계산에서 제외한다", () => {
    const result = wasteReduction([cell(null, 500), cell(100, 100), cell(80, 80), cell(60, 60)])!;

    expect(result.days).toBe(3);
    // 제외된 셀의 실적 500 이 기준선을 오염시키지 않는다
    expect(result.peaks).toEqual([{ name: "중식", value: 100 }]);
  });

  it("실적 0 인 날은 제외하고 그 수를 노출한다(미배식일을 '전부 버렸다'로 세면 절감이 부풀려진다)", () => {
    // 토·일 미배식(실적 0)이 섞인 구간. 포함하면 기준선 100 만큼을 매일 버린 것으로 계산된다.
    const result = wasteReduction([cell(100, 100), cell(90, 90), cell(80, 80), cell(22, 0), cell(22, 0)])!;

    expect(result.days).toBe(3);
    expect(result.excludedZeroActual).toBe(2);
    expect(result.baselineOver).toBe(30); // (100-100)+(100-90)+(100-80) — 0 실적 셀은 안 들어간다
    expect(result.forecastOver).toBe(0);
  });

  it("기준선은 끼니별로 잡는다(점심 최대치를 저녁에 적용하면 절감이 부풀려진다)", () => {
    const result = wasteReduction([
      cell(20, 20, "LUNCH"),
      cell(20, 10, "LUNCH"),
      cell(5, 5, "DINNER"),
      cell(5, 3, "DINNER"),
    ])!;

    // 중식 기준선 20, 석식 기준선 5 → (20-20)+(20-10)+(5-5)+(5-3) = 12.
    // 전체 최대치 20 을 석식에도 쓰면 (20-5)+(20-3)=32 가 더해져 42 가 된다 — 그게 부풀림이다.
    expect(result.baselineOver).toBe(12);
    expect(result.peaks).toEqual([
      { name: "중식", value: 20 },
      { name: "석식", value: 5 },
    ]);
  });

  it("근거가 없으면 null 이다(0 을 만들어 내지 않는다)", () => {
    expect(wasteReduction([])).toBeNull();
    expect(wasteReduction([cell(null, 10)])).toBeNull();
    expect(wasteReduction([cell(0, 0)])).toBeNull(); // 실적이 전부 0 이면 비교 기준이 없다
  });
});

describe("trendSeries", () => {
  function bt(
    date: string,
    predicted: number | null,
    actual: number,
    mealWindow = "LUNCH",
  ): MerchantBacktestCell {
    return { date, mealWindow, predicted, actual } as MerchantBacktestCell;
  }
  function fc(date: string, predicted: number | null, mealWindow = "LUNCH"): MerchantForecastCell {
    return { date, mealWindow, predicted } as MerchantForecastCell;
  }

  it("과거는 실적+그때 예측, 미래는 예측만 담고 날짜순으로 잇는다", () => {
    const points = trendSeries(
      [bt("2026-08-20", 22, 20), bt("2026-08-21", 19, 21)],
      [fc("2026-08-24", 20), fc("2026-08-25", 21)],
      "2026-08-24",
    );

    expect(points.map((p) => p.label)).toEqual(["08-20", "08-21", "08-24", "08-25"]);
    expect(points[0]).toMatchObject({ actual: 20, predicted: 22 });
    expect(points[2]).toMatchObject({ actual: null, predicted: 20, today: true });
  });

  it("끼니를 합쳐 하루 한 점으로 만든다", () => {
    const points = trendSeries(
      [bt("2026-08-20", 20, 18, "LUNCH"), bt("2026-08-20", 5, 4, "DINNER")],
      [],
      null,
    );

    expect(points).toHaveLength(1);
    expect(points[0]).toMatchObject({ actual: 22, predicted: 25 });
  });

  it("배식하지 않은 날은 계열에서 뺀다(0 으로 이으면 매주 바닥까지 떨어진다)", () => {
    const points = trendSeries(
      [bt("2026-08-21", 19, 21), bt("2026-08-22", null, 0), bt("2026-08-23", null, 0)],
      [],
      null,
    );

    expect(points.map((p) => p.label)).toEqual(["08-21"]);
  });

  it("주말에 예측이 나와도 실적 0 이면 뺀다(예측 유무로 판정하면 실적선이 바닥까지 떨어진다)", () => {
    // 전주 실적이 없어 4주 평균으로 강등되면 주말에도 예측값이 나온다 — 실측으로 확인한 형태다.
    const points = trendSeries(
      [bt("2026-08-21", 19, 21), bt("2026-08-22", 10, 0), bt("2026-08-24", 20, 18)],
      [],
      null,
    );

    expect(points.map((p) => p.label)).toEqual(["08-21", "08-24"]);
  });

  it("같은 날짜가 두 원천에 있으면 실적을 가진 백테스트를 택한다", () => {
    const points = trendSeries([bt("2026-08-24", 22, 25)], [fc("2026-08-24", 99)], null);

    expect(points).toHaveLength(1);
    expect(points[0]).toMatchObject({ actual: 25, predicted: 22 });
  });

  it("예측이 NO_DATA 인 날은 predicted 가 null 이다(0 이 아니다)", () => {
    const points = trendSeries([bt("2026-08-21", null, 21)], [], null);

    expect(points[0].predicted).toBeNull();
    expect(points[0].actual).toBe(21);
  });
});
