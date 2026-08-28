"use client";

import { useApi } from "@/lib/useApi";
import { merchantPath, useMerchant } from "../../_lib/merchant-context";
import { addDays, rangeQuery, todayIn } from "../../_lib/format";
import type {
  MerchantBacktestResponse,
  MerchantForecastResponse,
  MerchantTransactionsResponse,
} from "../../_lib/types";
import { TodayProgressSection } from "../_sections/today";
import { YesterdaySection } from "../_sections/yesterday";

const LEDGER_LIMIT = 500;

/**
 * 오늘 현황 — "지금 어떻게 되고 있나". 식수예측 페이지에서 분리했다:
 * 발주 질문(몇 인분 준비)과 운영 질문(지금까지 몇 건 나갔나)은 보는 시점도 리듬도 다른데
 * 한 페이지에 있으면 서로를 스크롤 밑으로 밀어낸다.
 */
export default function MerchantTodayPage() {
  const { merchantId, merchant } = useMerchant();

  const probe = useApi<MerchantForecastResponse>(merchant ? null : merchantPath(merchantId, "/forecast"));
  const timezone = merchant?.timezone ?? probe.data?.timezone ?? null;
  const today = todayIn(timezone);
  const yesterday = today ? addDays(today, -1) : null;

  // 진행률 분모(오늘 예측)와 어제 비교(그날 예측)를 위해 예측·백테스트를 좁게 조회한다.
  const forecast = useApi<MerchantForecastResponse>(
    merchantPath(merchantId, `/forecast${today ? rangeQuery({ from: today, to: today }) : ""}`),
  );
  const backtest = useApi<MerchantBacktestResponse>(
    merchantPath(merchantId, `/backtest${yesterday ? rangeQuery({ from: yesterday, to: yesterday }) : ""}`),
  );

  const todayLedger = useApi<MerchantTransactionsResponse>(
    today
      ? merchantPath(
          merchantId,
          `/transactions${rangeQuery({ from: today, to: today, limit: LEDGER_LIMIT })}`,
        )
      : null,
  );
  const yesterdayLedger = useApi<MerchantTransactionsResponse>(
    yesterday
      ? merchantPath(
          merchantId,
          `/transactions${rangeQuery({ from: yesterday, to: yesterday, limit: LEDGER_LIMIT })}`,
        )
      : null,
  );

  return (
    <div className="flex flex-col gap-5">
      <TodayProgressSection
        transactions={todayLedger}
        forecast={forecast}
        today={today}
        timezone={timezone}
      />
      <YesterdaySection
        backtest={backtest}
        transactions={yesterdayLedger}
        yesterday={yesterday}
        timezone={timezone}
      />
    </div>
  );
}
