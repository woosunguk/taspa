"use client";

import { useState } from "react";
import { useApi } from "@/lib/useApi";
import { merchantPath, useMerchant } from "../../_lib/merchant-context";
import { addDays, rangeQuery, todayIn } from "../../_lib/format";
import type { MerchantBacktestResponse, MerchantForecastResponse } from "../../_lib/types";
import { AccuracySection, RecentActualsSection, WasteReductionSection } from "../_sections/outlook";

const DEFAULT_BACKTEST_DAYS = 28;

/**
 * 잔반 리포트 — "예측이 얼마나 맞았고, 그래서 무엇이 좋아졌나".
 * 식수예측 페이지에서 분리했다: 발주 결정(미래)과 성과 확인(과거)은 다른 시점의 질문이고,
 * 한 페이지에 두면 히어로 질문("오늘 몇 인분")이 아홉 섹션 속에 묻힌다.
 */
export default function MerchantReportPage() {
  const { merchantId, merchant } = useMerchant();
  const [backtestDays, setBacktestDays] = useState(DEFAULT_BACKTEST_DAYS);

  const probe = useApi<MerchantForecastResponse>(merchant ? null : merchantPath(merchantId, "/forecast"));
  const timezone = merchant?.timezone ?? probe.data?.timezone ?? null;
  const today = todayIn(timezone);
  const yesterday = today ? addDays(today, -1) : null;

  const backtestQuery = today ? rangeQuery({ from: addDays(today, -backtestDays), to: yesterday }) : "";
  const backtest = useApi<MerchantBacktestResponse>(merchantPath(merchantId, `/backtest${backtestQuery}`));

  return (
    <div className="flex flex-col gap-5">
      <WasteReductionSection backtest={backtest} />
      <AccuracySection backtest={backtest} days={backtestDays} onDays={setBacktestDays} />
      <RecentActualsSection backtest={backtest} />
    </div>
  );
}
