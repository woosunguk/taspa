package com.taspa.server.forecast.dto

import java.time.LocalDate
import java.util.UUID

/**
 * 예측 방법 라벨(P0 폴백 체인 순서). 셀마다 어떤 방법으로 산출됐는지 명시한다 —
 * NO_DATA 는 "0명 예측"이 아니라 "데이터 없음"이다(predicted=null).
 */
enum class ForecastMethod {
    /** 전주 동요일 실적 × 재실 인원 비율(headcountNow ÷ headcountLastWeek) 보정. */
    SEASONAL_NAIVE_ADJUSTED,

    /**
     * 전주 동요일 실적 그대로(비율 보정 생략 — ratio 1.0). 재실 인원을 이력에서 복원할 수 없거나,
     * 복원값이 신뢰 범위 밖 비율을 만들 때(부분 SCD 이력 커버리지 — ForecastService 가드) 강등된다.
     */
    SEASONAL_NAIVE,

    /**
     * 전주 동요일을 basis 로 쓸 수 없어(실적 없음 **또는** 휴일 여부가 타깃과 다름) 최근 4주 같은 요일 중
     * 타깃과 휴일 상태가 같은 주만 평균한 값. 캘린더 때문에 강등됐는지는 basis.excludedHolidayBasis 로 구분한다.
     */
    FOUR_WEEK_AVG,

    /**
     * 폴백 근거 데이터도 없음 — predicted=null(0 이 아님). 타깃이 휴일인데 최근 4주에 비교 가능한 휴일
     * 실적이 없을 때도 여기로 온다(평일 실적으로 휴일을 예측하는 대신 모름을 밝힌다).
     */
    NO_DATA,
}

/**
 * 예측 산출 근거(설명가능성). lastWeekActual = 전주 동요일 실적(식수 수량 합),
 * headcount* = 재실 모수(활성 멤버십 ∧ EMPLOYED). 복원 불가하면 null.
 *
 * excludedHolidayBasis = **휴일 여부가 타깃과 달라 basis 후보에서 제외된 과거 주 수**. 0 이면 캘린더가
 * 예측에 관여하지 않았다는 뜻이라, 방법이 강등됐을 때 원인이 캘린더인지 데이터 부재인지 구분된다.
 */
data class ForecastBasis(
    val lastWeekActual: Long?,
    val headcountNow: Long?,
    val headcountLastWeek: Long?,
    val excludedHolidayBasis: Int = 0,
)

/**
 * 예측 셀 — (date × site × meal_window) 그레인. siteId=null 은 org 전체(총식수)다.
 *
 * holiday/holidayName 은 **조직 캘린더가 그 날을 휴일로 표시했다는 사실**이지 예측값이 아니다 —
 * 휴일에도 당직 식사가 있으므로 predicted 를 0 으로 단정하지 않는다(둘은 독립된 정보다).
 */
data class ForecastCell(
    val date: LocalDate,
    val siteId: UUID?,
    val mealWindow: String,
    val predicted: Long?,
    val method: ForecastMethod,
    val basis: ForecastBasis,
    val holiday: Boolean = false,
    val holidayName: String? = null,
)

/** 예측 응답 — 집계 파생값만 노출한다(개별 이벤트·user_sub 미노출, 소비 집계와 동일 규칙). */
data class ForecastResponse(
    val orgId: UUID,
    val from: LocalDate,
    val to: LocalDate,
    val siteId: UUID?,
    val mealWindow: String?,
    val cells: List<ForecastCell>,
)

/**
 * 백테스트 셀 — "그 시점에 예측했을 값"(predicted) vs 실적(actual). actual 은 데이터 없으면 0 으로 본다.
 * 재실 모수는 타깃 전일(D-1) 끝 기준 이력 복원값이다 — 운영 예측이 늦어도 D-1 에 수행되므로
 * 타깃 당일 멤버십 변경은 예측 재현에 쓰지 않는다(미래정보 누수 방지).
 */
data class BacktestCell(
    val date: LocalDate,
    val siteId: UUID?,
    val mealWindow: String,
    val predicted: Long?,
    val method: ForecastMethod,
    val actual: Long,
    val basis: ForecastBasis,
    val holiday: Boolean = false,
    val holidayName: String? = null,
)

/**
 * 백테스트 요약. 채점 대상은 predicted!=null 셀(NO_DATA 제외).
 *  - mape: mean(|pred-actual|/actual) — **actual=0 셀은 분모가 0 이라 제외**하고 제외 수를
 *    mapeExcludedZeroActual 로 정직하게 노출한다.
 *  - wape: Σ|pred-actual| ÷ Σactual (actual=0 셀 포함 — 분모는 총합이라 안전). Σactual=0 이면 null.
 *  - bias: (Σpred − Σactual) ÷ Σactual — 양수면 과대예측(잔반), 음수면 과소예측(품절) 경향.
 */
data class BacktestSummary(
    val cells: Int,
    val scoredCells: Int,
    val mape: Double?,
    val mapeExcludedZeroActual: Int,
    val wape: Double?,
    val bias: Double?,
)

/** 백테스트 응답 — siteId 미지정 시 org 전체(총식수) 축만 평가한다(요약 지표의 이중집계 방지). */
data class BacktestResponse(
    val orgId: UUID,
    val from: LocalDate,
    val to: LocalDate,
    val siteId: UUID?,
    val mealWindow: String?,
    val cells: List<BacktestCell>,
    val summary: BacktestSummary,
)
