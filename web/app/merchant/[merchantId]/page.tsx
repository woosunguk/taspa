"use client";

import { useState } from "react";
import { useApi } from "@/lib/useApi";
import { merchantPath, useMerchant } from "../_lib/merchant-context";
import { addDays, rangeQuery, todayIn } from "../_lib/format";
import type { MerchantBacktestResponse, MerchantForecastResponse } from "../_lib/types";
import { ClientOrgsSection, SignalSettings } from "./_sections/signals";
import { TodayForecastSection } from "./_sections/today";
import { OutlookSection, TrendSection } from "./_sections/outlook";

/** 14일 — 다음 주의 행사·휴일 신호가 첫 화면 전망에 들어오는 최소 지평(7일이면 딱 한 주 뒤 행사가 잘린다). */
const DEFAULT_HORIZON_DAYS = 14;
const DEFAULT_BACKTEST_DAYS = 28;

/**
 * 매장 개요 대시보드 — 사장이 아침에 열어 **"오늘 몇 인분 준비하나"** 에 답하는 화면.
 *
 * 답의 순서가 곧 화면의 순서다: 오늘 준비량 → 오늘 지금까지의 진행분 → 어제 마감(그 예측이 맞았나) →
 * 며칠치 전망 → 최근 실적 → 정확도 지표. 뒤로 갈수록 "앞의 숫자를 믿을지" 판단하는 재료다.
 *
 * ★모든 날짜는 **매장 타임존**이다(`merchant.timezone`, 각 응답의 `timezone`). 서버가 모든 날짜 버킷을
 * 매장 달력으로 앵커하므로, 브라우저 로컬로 계산한 "오늘"을 보내면 자정 근처에서 하루가 밀려 예측과
 * 실적이 서로 다른 날의 것이 된다. 그래서 타임존을 알기 전에는 **날짜 파라미터를 아예 보내지 않고**
 * (서버가 매장-로컬로 앵커) 오늘/어제 전용 조회는 아예 하지 않는다.
 *
 * ★이 화면에는 승인·취소 액션이 없다. 결제 승인/취소는 기계 신원(POS = M2M + merchant_id 클레임) 전용이며,
 * 사람 계정이 탈취돼도 그것이 곧 무단 결제가 되지 않는다는 분리가 이 기능의 안전 근거다.
 *
 * ★손님이 누구인지는 어떤 응답에도 없다(서버 DTO 에 자리가 없다). 화면도 그 경계를 넘는 값을 만들지 않는다.
 */
export default function MerchantOverviewPage() {
  const { merchantId, merchant } = useMerchant();
  const [horizon, setHorizon] = useState(DEFAULT_HORIZON_DAYS);

  /*
   * 타임존 앵커. 보통은 매장 목록(`/mine`)이 알려준다. 플랫폼 권한으로 열람 중이면 그 목록에 매장이
   * 없어 타임존을 모르는데, 이때만 파라미터 없는 예측 1회(=서버가 매장-로컬로 앵커한 응답)로 값을
   * 얻는다. 상태로 복사하지 않는다 — 매장마다 고정값이라 첫 응답 이후 저절로 안정된다.
   */
  const probe = useApi<MerchantForecastResponse>(merchant ? null : merchantPath(merchantId, "/forecast"));
  const timezone = merchant?.timezone ?? probe.data?.timezone ?? null;
  const today = todayIn(timezone);
  const yesterday = today ? addDays(today, -1) : null;

  /*
   * 예측은 **오늘부터** 본다(서버 기본값은 내일부터라 오늘 셀이 없다). 타임존을 모르는 동안에는
   * 파라미터 없이 물어 서버 앵커에 맡기고, 알게 되면 매장-로컬 오늘로 다시 조회한다.
   */
  // 신호 조합은 서버에 저장돼 있어 쿼리에 싣지 않는다 — 설정 패널이 저장하면 reload 로 반영된다.
  const forecastQuery = today ? rangeQuery({ from: today, to: addDays(today, horizon - 1) }) : "";
  const forecast = useApi<MerchantForecastResponse>(merchantPath(merchantId, `/forecast${forecastQuery}`));

  // 백테스트 타깃은 어제 이하다(서버 기본값도 동일). 타임존을 모르면 그 기본값을 그대로 쓴다.
  const backtestQuery = today
    ? rangeQuery({ from: addDays(today, -DEFAULT_BACKTEST_DAYS), to: yesterday })
    : "";
  const backtest = useApi<MerchantBacktestResponse>(merchantPath(merchantId, `/backtest${backtestQuery}`));

  /*
   * 오늘·어제의 금액과 건수는 **장부**(식권 결제 내역)에서 온다 — 예측/실적(확정 소비 이벤트)과 원천이
   * 다르므로 화면에서도 서로 파생시키지 않는다. 날짜를 특정해야 하는 조회라 타임존을 모르면 하지 않는다
   * (브라우저 로컬로 "오늘"을 지어내면 자정 근처에서 남의 날 데이터를 매장 실적이라고 보여주게 된다).
   */

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-end">
        <SignalSettings
          merchantId={merchantId}
          onSaved={() => {
            forecast.reload();
            backtest.reload();
          }}
        />
      </div>
      <TodayForecastSection forecast={forecast} today={today} timezone={timezone} />
      {/* "오늘 몇 인분" 바로 뒤 — 그 숫자를 믿을지 판단하는 첫 재료가 추이다(표는 그 뒤에 온다). */}
      <TrendSection forecast={forecast} backtest={backtest} today={today} />
      {/* 신호 원천(조직 캘린더·연차) — 바로 아래 전망 표의 신호 열이 어디서 오는지 설명한다. */}
      <ClientOrgsSection orgs={forecast.data?.orgs ?? []} />
      <OutlookSection forecast={forecast} horizon={horizon} onHorizon={setHorizon} today={today} />
      {/*
        여기서 끝난다 — 이 페이지는 **발주 질문**("오늘/이번 주 몇 인분 준비하나")만 답한다.
        운영 진행(오늘 현황)과 성과(잔반 리포트)는 사이드바의 별도 메뉴다: 아홉 섹션이 한 페이지에
        있으면 히어로 질문이 스크롤 속에 묻힌다(사용자 지적으로 분리).
      */}
    </div>
  );
}
