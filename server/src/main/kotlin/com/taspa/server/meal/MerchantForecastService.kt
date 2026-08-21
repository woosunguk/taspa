package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.consumption.MealWindow
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.forecast.dto.BacktestSummary
import com.taspa.server.forecast.dto.ForecastMethod
import com.taspa.server.meal.dto.MerchantBacktestCell
import com.taspa.server.meal.dto.MerchantBacktestResponse
import com.taspa.server.meal.dto.MerchantForecastBasis
import com.taspa.server.meal.dto.MerchantForecastCell
import com.taspa.server.meal.dto.MerchantForecastResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * **가맹 그레인 식수예측** — "우리 매장에서 몇 인분 나갈까". 온디맨드 계산, 저장 없음(스키마 무변경).
 * 조직 예측(forecast/ForecastService)과 같은 사상이되 **그레인이 다르다**:
 *
 *  - 조직 예측 = "우리 회사 직원 몇 명이 먹을까" — (끼니 × 사업장 × 일), 재실 인원(headcount) 보정 있음.
 *  - 가맹 예측 = "우리 매장에서 몇 인분 나갈까" — (끼니 × 일). 한 매장은 **여러 조직 손님**을 받고,
 *    매장에는 "재실 인원"이라는 모수가 존재하지 않는다(불특정 다수). 그래서 **인원 보정을 하지 않으며**
 *    `SEASONAL_NAIVE_ADJUSTED` 는 이 서비스에서 절대 산출되지 않는다.
 *
 * 폴백 체인: 전주 동요일 실적(SEASONAL_NAIVE) → 최근 4주 같은 요일 평균(FOUR_WEEK_AVG) →
 * predicted=null(NO_DATA — **0 인분이 아니라 데이터 없음**).
 *
 * 실적 원천은 `consumption_events`(status='CONFIRMED', merchant_id 앵커)다 — 선택 근거:
 *  1. **취소가 자동으로 빠진다.** redeem void 는 같은 external_id 를 VOIDED 로 재적재(full-replace)하므로
 *     CONFIRMED 필터만으로 취소분이 집계에서 사라진다.
 *  2. **수량(quantity) 축이 있다.** 가맹 그레인의 질문은 "몇 인분"이고 meal_transactions 에는 금액만 있다
 *     (1 승인 = 1 인분이라는 암묵 가정을 하지 않아도 된다).
 *  3. **결제 외 생산자도 담긴다.** 소비 로그는 결제(payment)뿐 아니라 POS·임포트 생산자도 적재하는
 *     정답 로그 seam 이라, 식권 결제가 아닌 식수까지 예측에 반영된다.
 *  4. **조직 예측과 같은 원천**이라 같은 한 끼가 두 화면에서 다른 숫자로 보이지 않는다.
 * (meal_transactions 는 장부 — 금액·정산·대사의 진실이고, 거래 로그 조회는 그쪽을 읽는다.)
 *
 * 모든 날짜·요일 경계는 **merchant.timezone 앵커**다(조직 타임존을 빌릴 수 없다 — V29 주석).
 *
 * **★조직 캘린더 휴일은 이 그레인에 적용하지 않는다(의도적 결정).** 조직 예측(`forecast/ForecastService`)은
 * 휴일을 인지해 basis 를 거르지만 여기서는 쓰지 않는다. 근거:
 *  1. **"어느 조직의 휴일인가"가 결정 불가다.** 한 매장은 여러 조직 손님을 받는다. A 조직만 창립기념일이면
 *     매장 수요는 일부만 줄어드는데, 그 날을 "휴일"로 표시하면 매장은 문을 닫아도 된다고 읽는다 —
 *     타임존을 조직에서 빌릴 수 없는 것과 정확히 같은 이유(V29)로 휴일도 빌릴 수 없다.
 *  2. **그 영향은 이미 실적에 들어 있다.** 이 예측의 basis 는 그 매장의 과거 실적이고, 고객사 휴일로 인한
 *     감소는 그 숫자에 반영돼 있다. 위에 조직 축 휴일 플래그를 겹치면 이중 보정이 된다.
 * **닫히지 않은 것(정직하게)**: 설 연휴처럼 **모든** 고객 조직이 쉬는 날은 매장 수요도 급감하는데, 전주가
 * 그런 날이었으면 그 실적이 그대로 basis 가 되어 과소예측된다. 이를 고치려면 "이 매장의 고객 조직 구성"이
 * 필요한데(소비 이벤트의 org 분포 → 가중 휴일 비율), 지배적 조직 하나가 비율을 왜곡하는 문제가 남아
 * P0 에서는 근거 부족으로 보류한다. 매장 자체 휴무일 캘린더가 생기면 그것이 더 정확한 신호다.
 *
 * **완결일 경계(예측 경로 한정)**: 예측의 basis 로는 **매장-로컬 어제까지의 실적만** 쓴다. 오늘 실적은
 * 아직 적재되는 중인 부분값이라, 같은 예측을 아침과 저녁에 조회하면 값이 달라진다 — 매장은 그 숫자를
 * 믿고 발주하는데 UI 는 "전주 같은 요일 실적 N인분"이라고 확신 있게 표시하므로 흔들림 자체가 오정보다.
 * 기본 7일 지평의 T+7 셀이 정확히 이 경우이며(basis = 오늘), 경계를 넣으면 D-14 기반 [ForecastMethod.
 * FOUR_WEEK_AVG] 등으로 강등된다. 부분값을 SEASONAL_NAIVE 로 위장하는 것보다 강등이 정직하다.
 * **백테스트는 이 경계를 재현하지 못한다** — 백테스트 타깃은 어제 이하라 basis(타깃-7일 이전)가 항상
 * 완결 구간이고, 따라서 운영 예측이 겪는 이 강등이 백테스트 지표(MAPE·WAPE·bias)에 나타나지 않는다.
 */
@Service
class MerchantForecastService(
    private val merchantRepository: MerchantRepository,
    private val eventRepository: ConsumptionEventRepository,
) {
    /**
     * D+1~D+7 배치성 예측. from/to 미지정 시 매장-로컬 내일부터 7일. 창은 [MAX_FORECAST_WINDOW_DAYS]일로
     * 상한하되 **조용히 자르지 않는다** — to 를 당긴 실효 구간과 windowTruncated 를 응답에 담는다.
     *
     * basis 는 매장-로컬 어제까지의 완결 실적만 쓴다(클래스 KDoc "완결일 경계" 참조).
     */
    @Transactional(readOnly = true)
    fun forecast(
        merchantId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        mealWindow: String?,
    ): MerchantForecastResponse {
        val merchant = requireMerchant(merchantId)
        val zone = zoneOf(merchant)
        val fromDate = from ?: LocalDate.now(zone).plusDays(1)
        val requestedTo = to ?: fromDate.plusDays(DEFAULT_FORECAST_DAYS - 1)
        requireOrdered(fromDate, requestedTo)
        // 예측은 가까운 미래가 관심사라 상한 초과 시 **to 를 앞으로** 당긴다(먼 날짜를 버린다).
        val toDate = minOf(requestedTo, fromDate.plusDays(MAX_FORECAST_WINDOW_DAYS - 1))
        val windows = resolveWindows(mealWindow)

        // 실적 상단 경계 = 매장-로컬 어제. 오늘은 적재 중인 부분값이라 basis 로 쓰면 같은 예측이
        // 조회 시각마다 달라진다 — 강등(FOUR_WEEK_AVG 등)을 감수하고 완결일까지만 읽는다.
        val actuals =
            loadActuals(
                merchantId,
                zone,
                fromDate.minusDays(LOOKBACK_DAYS),
                minOf(toDate, LocalDate.now(zone).minusDays(1)),
            )
        val cells = ArrayList<MerchantForecastCell>()
        var date = fromDate
        while (!date.isAfter(toDate)) {
            for (window in windows) {
                val p = predictCell(actuals, date, window)
                cells.add(MerchantForecastCell(date, window, p.predicted, p.method, p.basis))
            }
            date = date.plusDays(1)
        }
        return MerchantForecastResponse(
            merchantId = merchantId,
            timezone = merchant.timezone,
            from = fromDate,
            to = toDate,
            requestedFrom = fromDate,
            requestedTo = requestedTo,
            windowTruncated = toDate != requestedTo,
            mealWindow = mealWindow?.let { MealWindow.parse(it).name },
            cells = cells,
        )
    }

    /**
     * 백테스트 — 과거 각 셀에 "그 시점에 예측했을 값"을 같은 폴백 체인으로 계산해 실적과 비교한다.
     *
     * **미래정보 누수 없음(구조적 보장)**: 이 모델의 입력은 타깃 날짜보다 최소 7일 앞선 실적(D-7·D-14·
     * D-21·D-28)뿐이고, 조직 예측과 달리 시점 재현이 필요한 모수(재실 인원)가 아예 없다. 따라서 타깃
     * 당일·전일 정보가 예측에 개입할 경로가 존재하지 않는다.
     *
     * 창은 [MAX_BACKTEST_WINDOW_DAYS]일 상한이며, 초과 시 **from 을 뒤로** 당겨 최근 구간을 남기고
     * 그 사실을 응답에 표시한다. 실적이 없는 셀은 actual=0 으로 보되 MAPE 분모에서는 제외한다.
     *
     * **완결일 경계는 여기 적용되지 않는다(구조적으로 무의미)** — 타깃이 어제 이하라 basis(타깃-7일
     * 이전)가 언제나 완결 구간이다. 뒤집어 말하면 백테스트는 운영 예측의 T+7 강등을 재현하지 못하므로,
     * 이 지표는 "완결 실적이 늘 있었다면"의 상한이다(클래스 KDoc 참조).
     */
    @Transactional(readOnly = true)
    fun backtest(
        merchantId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        mealWindow: String?,
    ): MerchantBacktestResponse {
        val merchant = requireMerchant(merchantId)
        val zone = zoneOf(merchant)
        val yesterday = LocalDate.now(zone).minusDays(1)
        val toDate = to ?: yesterday
        val requestedFrom = from ?: toDate.minusDays(DEFAULT_BACKTEST_DAYS - 1)
        requireOrdered(requestedFrom, toDate)
        if (toDate.isAfter(yesterday)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "백테스트는 어제까지의 과거 구간만 가능합니다")
        }
        // 평가는 최근 성능이 관심사라 상한 초과 시 **from 을 뒤로** 당긴다(오래된 날짜를 버린다).
        val fromDate = maxOf(requestedFrom, toDate.minusDays(MAX_BACKTEST_WINDOW_DAYS - 1))
        val windows = resolveWindows(mealWindow)

        val actuals = loadActuals(merchantId, zone, fromDate.minusDays(LOOKBACK_DAYS), toDate)
        val cells = ArrayList<MerchantBacktestCell>()
        var scored = 0
        var mapeSum = 0.0
        var mapeN = 0
        var mapeExcluded = 0
        var absErrSum = 0.0
        var actualSum = 0.0
        var predictedSum = 0.0
        var date = fromDate
        while (!date.isAfter(toDate)) {
            for (window in windows) {
                val p = predictCell(actuals, date, window)
                val actual = actuals.at(date, window) ?: 0L
                cells.add(MerchantBacktestCell(date, window, p.predicted, p.method, actual, p.basis))
                val predicted = p.predicted ?: continue // NO_DATA 셀은 채점 제외
                scored++
                absErrSum += abs(predicted - actual).toDouble()
                actualSum += actual
                predictedSum += predicted
                if (actual > 0) {
                    mapeSum += abs(predicted - actual).toDouble() / actual
                    mapeN++
                } else {
                    mapeExcluded++
                }
            }
            date = date.plusDays(1)
        }
        return MerchantBacktestResponse(
            merchantId = merchantId,
            timezone = merchant.timezone,
            from = fromDate,
            to = toDate,
            requestedFrom = requestedFrom,
            requestedTo = toDate,
            windowTruncated = fromDate != requestedFrom,
            mealWindow = mealWindow?.let { MealWindow.parse(it).name },
            cells = cells,
            summary =
                BacktestSummary(
                    cells = cells.size,
                    scoredCells = scored,
                    mape = if (mapeN > 0) mapeSum / mapeN else null,
                    mapeExcludedZeroActual = mapeExcluded,
                    wape = if (actualSum > 0) absErrSum / actualSum else null,
                    bias = if (actualSum > 0) (predictedSum - actualSum) / actualSum else null,
                ),
        )
    }

    // ---- 예측 코어 ----

    private data class Prediction(
        val predicted: Long?,
        val method: ForecastMethod,
        val basis: MerchantForecastBasis,
    )

    /**
     * 셀 1개 예측(폴백 체인). 인원 보정 단계가 없으므로 조직 예측보다 한 단계 짧다 —
     * 전주 동요일이 있으면 그대로 쓰고, 없으면 최근 4주 같은 요일의 **존재하는 주**만 평균한다.
     */
    private fun predictCell(
        actuals: ActualsIndex,
        date: LocalDate,
        window: String,
    ): Prediction {
        val lastWeek = actuals.at(date.minusDays(7), window)
        if (lastWeek != null) {
            return Prediction(lastWeek, ForecastMethod.SEASONAL_NAIVE, MerchantForecastBasis(lastWeek, 1))
        }
        // 전주는 위에서 없음이 확정이라 실질 -14·-21·-28.
        val samples = (1..LOOKBACK_WEEKS).mapNotNull { actuals.at(date.minusDays(7L * it), window) }
        if (samples.isNotEmpty()) {
            return Prediction(
                samples.average().roundToLong(),
                ForecastMethod.FOUR_WEEK_AVG,
                MerchantForecastBasis(null, samples.size),
            )
        }
        return Prediction(null, ForecastMethod.NO_DATA, MerchantForecastBasis(null, 0))
    }

    // ---- 실적 인덱스 ----

    /** (date, window) → 인분 수량 합. 가맹 그레인이라 site 축이 없다(매장 자체가 축). */
    private class ActualsIndex {
        private val totals = HashMap<Pair<LocalDate, String>, Long>()

        fun add(
            date: LocalDate,
            window: String,
            quantity: Long,
        ) {
            totals.merge(date to window, quantity, Long::plus)
        }

        fun at(
            date: LocalDate,
            window: String,
        ): Long? = totals[date to window]
    }

    /**
     * [fromDate, toDate](매장-로컬 달력)의 CONFIRMED 실적을 단일 그룹쿼리로 읽어 인덱싱한다.
     * 호출부가 완결일로 상단을 당겨 구간이 비면(먼 미래 조회) 질의 없이 빈 인덱스 → 전 셀 NO_DATA.
     */
    private fun loadActuals(
        merchantId: UUID,
        zone: ZoneId,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): ActualsIndex {
        if (toDate.isBefore(fromDate)) return ActualsIndex()
        val rows =
            eventRepository.aggregateByMerchantDateWindow(
                merchantId,
                fromDate.atStartOfDay(zone).toInstant(),
                toDate.plusDays(1).atStartOfDay(zone).toInstant(),
                zone.id,
                MAX_AGGREGATE_ROWS,
            )
        if (rows.size >= MAX_AGGREGATE_ROWS) {
            // LIMIT 도달 = 최신 날짜 그룹부터 조용히 잘린 상태. 절단이 응답에 드러나지 않고 파생 계산만
            // 왜곡되므로 fail-loud 한다(조직 예측과 동일 사상) — 무신호 왜곡 대신 창 축소를 요구한다.
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "실적 집계 규모가 상한(${MAX_AGGREGATE_ROWS}행)에 도달했습니다 — 조회 창(from/to)을 줄여 다시 시도하세요",
            )
        }
        val index = ActualsIndex()
        rows.forEach { row ->
            index.add(toLocalDate(row[0]!!), row[1] as String, (row[2] as Number).toLong())
        }
        return index
    }

    // ---- 검증·헬퍼 ----

    private fun requireOrdered(
        from: LocalDate,
        to: LocalDate,
    ) {
        if (to.isBefore(from)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "to 는 from 보다 뒤여야 합니다")
        }
        // 상한 자체는 절단으로 처리하지만, 비현실적으로 긴 요청은 계산 전에 거절한다(자원 상한).
        if (ChronoUnit.DAYS.between(from, to) + 1 > ABSURD_WINDOW_DAYS) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "조회 창이 너무 넓습니다 (최대 ${ABSURD_WINDOW_DAYS}일)")
        }
    }

    private fun resolveWindows(mealWindow: String?): List<String> =
        if (mealWindow == null) {
            MealWindow.entries.map { it.name }
        } else {
            try {
                listOf(MealWindow.parse(mealWindow).name)
            } catch (ex: IllegalArgumentException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, ex.message ?: "잘못된 mealWindow 입니다")
            }
        }

    private fun requireMerchant(merchantId: UUID): Merchant =
        merchantRepository.findById(merchantId).orElse(null)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "가맹점을 찾을 수 없습니다")

    /** 저장 시 검증되므로 도달 불가 방어선 — 깨진 값이어도 예측이 500 으로 죽지 않게 UTC 로 낙하한다. */
    private fun zoneOf(merchant: Merchant): ZoneId =
        try {
            ZoneId.of(merchant.timezone)
        } catch (ex: Exception) {
            ZoneId.of("UTC")
        }

    private fun toLocalDate(value: Any): LocalDate =
        when (value) {
            is Date -> value.toLocalDate()
            is LocalDate -> value
            else -> LocalDate.parse(value.toString())
        }

    private companion object {
        const val DEFAULT_FORECAST_DAYS = 7L
        const val DEFAULT_BACKTEST_DAYS = 28L

        /** 예측 조회 창 상한(일) — D+1~D+7 배치성 조회가 목적이라 한 달이면 충분하다. */
        const val MAX_FORECAST_WINDOW_DAYS = 31L

        /** 백테스트 창 상한(일) — 분기 단위 평가까지 허용. */
        const val MAX_BACKTEST_WINDOW_DAYS = 92L

        /** 절단 이전에 거절할 비현실적 창(일) — 절단 계산 자체가 무의미한 입력 방어. */
        const val ABSURD_WINDOW_DAYS = 1_000L

        /** 폴백 평균 주 수(같은 요일). */
        const val LOOKBACK_WEEKS = 4

        /** 실적 로딩 lookback(일) = 4주. */
        const val LOOKBACK_DAYS = 7L * LOOKBACK_WEEKS

        /** 실적 집계 행 상한 — (최대 120일) × 3끼 ≈ 360 이라 넉넉하다(그룹 폭주 방어선). */
        const val MAX_AGGREGATE_ROWS = 5_000
    }
}
