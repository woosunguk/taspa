package com.taspa.server.forecast

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 식수 예측 튜닝 파라미터. `@ConfigurationPropertiesScan` 으로 자동 등록된다(TaspaServerApplication).
 *
 * 기본값은 **도입 전 동작을 보존하는 쪽**으로 잡혀 있다: 표본이 적으면 방법 선택(holdout)과 분위수가
 * 아예 발동하지 않고 기존 폴백 체인(전주 동요일 → 4주 평균)이 그대로 쓰인다. 즉 이 설정을 건드리지
 * 않으면 기존 조직의 예측값은 바뀌지 않고, **예측을 못 내던 셀**에서만 새 방법이 나타난다.
 */
@ConfigurationProperties(prefix = "taspa.forecast")
data class ForecastProperties(
    /**
     * 홀드아웃 채점으로 **방법을 자동 선택**할지. **기본 false** — 근거는 실측이다.
     *
     * 13주 합성 실적(2026-07-01~08-23, 162셀)으로 세 구성을 비교한 결과:
     * ```
     *   기존(전주 동요일 고정)        WAPE 7.18%   bias +0.12%
     *   선택 켜기(가드 없음)          WAPE 7.18%   bias +0.82%
     *   선택 켜기(마진 30%+볼륨 30)   WAPE 7.42%   bias +1.06%
     * ```
     * 방법별 WAPE 표를 보면 도전자가 좋아 보이지만(TRIMMED 5.44% vs SEASONAL 9.41%) 그것은
     * **선택 편향**이다 — 도전자가 이긴 셀만 모아 재면 당연히 낮고, 전체 오차 합은 그대로다.
     * 즉 채점판(최대 4점) 승패가 out-of-sample 로 일반화되지 않는다.
     *
     * 설계 문서 §9 의 원칙은 "각 단계는 이전 베이스라인을 **유의하게** 이겨야 다음으로 간다" 이므로
     * 이기지 못한 기계를 기본값으로 켜지 않는다. 기계는 남겨 둔다 — 실제 구내식당 실적이 쌓이면
     * `/forecast/backtest` 를 켜고/끄고 두 번 돌려 비교하고, 이겼을 때만 켠다(docs 절차 참조).
     */
    val methodSelectionEnabled: Boolean = false,
    /**
     * 기존 폴백 체인의 표본 창(주). 이 값은 `FOUR_WEEK_AVG` 라는 이름과 묶여 있으므로 바꾸지 말 것 —
     * 더 긴 창이 필요하면 [profileWeeks] 를 늘린다(새 방법 전용).
     */
    val lookbackWeeks: Int = 4,
    /** 트림 계절·참여율 추정의 표본 창(주). 트림이 의미를 가지려면 4주보다 길어야 한다. */
    val profileWeeks: Int = 8,
    /** 방법 선택 홀드아웃 창(주). 타깃과 같은 요일·휴일상태인 최근 N주를 채점에 쓴다. */
    val holdoutWeeks: Int = 4,
    /** 트림 계절이 성립하는 최소 동요일 표본 수. */
    val minSeasonalSamples: Int = 3,
    /** 참여율 추정이 성립하는 최소 관측 일수. 2주(평일 10일)면 충분히 넘는다. */
    val minParticipationDays: Int = 8,
    /**
     * 이 미만이면 방법 선택도 분위수도 하지 않는다. 홀드아웃 2점으로 방법을 고르는 것은 선택이 아니라
     * 잡음 적합이고, 2점으로 90 분위를 말하는 것은 근거 없는 숫자를 발주 담당자에게 주는 일이다.
     */
    val minHoldoutPoints: Int = 3,
    /**
     * 도전자가 **기존 방법(전주 동요일)을 대체하려면 넘어야 하는 상대 개선폭.** 0.10 = 채점판 WAPE 가
     * 10% 이상 낮아야 바꾼다.
     *
     * ★이 마진이 없으면 선택 자체가 과적합이다 — 채점판이 4점뿐이라 깨끗한 데이터에서는 세 방법의
     *   점수가 사실상 동률이고 승자가 잡음으로 갈린다(실측: 방법 분포가 흔들리는데 WAPE 는 그대로).
     *   설계 문서 §9 의 원칙이 "이전 베이스라인을 **유의하게** 이겨야"인 이유가 정확히 이것이다.
     *   덕분에 이 엔진은 **명백한 이득이 없으면 도입 전과 같은 값을 낸다.**
     */
    val selectionMargin: Double = 0.30,
    /**
     * 채점판의 **실적 총량** 하한. 점 개수만 보면 저녁(하루 4인분)처럼 작은 셀에서 선택이 무너진다 —
     * 1인분 차이가 25% 상대오차라, 4점 채점판의 승패가 사실상 잡음이다.
     *
     * ★실측으로 잡은 결함이다: 볼륨 가드 없이 돌렸을 때 34개 셀이 도전자로 갈렸는데 **전체 오차 합은
     *   그대로**였다(방법별 WAPE 표는 좋아 보였지만 그건 선택 편향이다 — 도전자가 이긴 셀만 모아 재면
     *   당연히 낮다). 즉 선택이 일반화되지 않았다.
     */
    val minHoldoutVolume: Long = 30,
    /** 준비량 분위수(서비스 수준). 0.9 = "10번 중 9번은 모자라지 않게". */
    val serviceLevel: Double = 0.9,
    /** 상·하 트림 비율. 0.25 = 양쪽 25% 를 버린다(행사·정전 같은 단발 이상치 방어). */
    val trimRatio: Double = 0.25,
    /** 재실 비율 보정을 신뢰하는 구간. 밖이면 보정을 생략한다(부분 SCD 이력이 만드는 비율 폭주 방어). */
    val headcountRatioMin: Double = 0.5,
    val headcountRatioMax: Double = 2.0,
)
