package com.taspa.server.forecast

import com.taspa.server.forecast.dto.CandidateScore
import com.taspa.server.forecast.dto.ForecastBasis
import com.taspa.server.forecast.dto.ForecastMethod
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** (date, site, window) → 실적 수량. 없으면 null(0 이 아니다 — "배식 안 함"과 "0인분"은 다르다). */
fun interface ActualsAt {
    fun at(
        date: LocalDate,
        site: UUID?,
        window: String,
    ): Long?
}

/** 재직 인원 복원. `asOf(d)` = d 의 끝(org-로컬) 기준. 0/복원불가는 null 이다. */
fun interface HeadcountsAsOf {
    fun asOf(date: LocalDate): Long?
}

/**
 * 하루의 **성격**. basis 대칭 판정의 단위다 — 같은 성격의 과거만 근거로 쓴다.
 *
 * 휴일과 행사를 나누는 이유: 둘의 수요 왜곡 방향이 다르다. 휴일은 당직만 남아 급감하고, 전사 행사는
 * 사람은 있지만 외부 식사·도시락으로 빠져 **부분 감소**한다. 하나로 묶으면 행사일 예측에 휴일 실적
 * (거의 0)이 들어와 크게 과소예측하고, 그 반대도 성립한다.
 */
enum class DayClass {
    NORMAL,
    HOLIDAY,
    EVENT,
}

/**
 * 하루 성격 판정. `HolidayIndex` 가 그대로 구현한다.
 *
 * 행사 관련 두 메서드는 **기본 구현이 "행사 아님"** 이다 — 행사 신호를 끈 조회·기존 테스트 픽스처가
 * 도입 전과 정확히 같게 동작한다(캘린더 없는 조직 불변식과 같은 사상).
 */
interface HolidayLookup {
    fun isHoliday(date: LocalDate): Boolean

    fun nameOf(date: LocalDate): String?

    fun isEvent(date: LocalDate): Boolean = false

    fun eventNameOf(date: LocalDate): String? = null

    /** 휴일이 행사를 이긴다 — 쉬는 날에 행사가 겹쳐도 배식 여부를 정하는 것은 휴일 쪽이다. */
    fun classOf(date: LocalDate): DayClass =
        when {
            isHoliday(date) -> DayClass.HOLIDAY
            isEvent(date) -> DayClass.EVENT
            else -> DayClass.NORMAL
        }
}

/** 엔진 산출물. [p90] 은 준비량 권고(서비스 수준 분위수)이며 근거가 없으면 null 이다. */
data class ForecastPrediction(
    val predicted: Long?,
    val p90: Long?,
    val method: ForecastMethod,
    val basis: ForecastBasis,
    val holiday: Boolean,
    val holidayName: String?,
    /**
     * 그 날이 사내 행사로 선언됐다는 **사실**(캘린더 유래)이지 예측값이 아니다. 휴일 슬롯과 나눠 두는
     * 이유는 화면이 "휴무"와 "행사"를 다르게 말해야 하기 때문이다 — 같은 칸에 담으면 행사일이 휴무로 읽힌다.
     */
    val event: Boolean = false,
    val eventName: String? = null,
)

/**
 * 예측 코어 — **DB 없이 단위 테스트 가능한 순수 계산**이다(seam 3개만 받는다).
 *
 * ## 왜 분리했나
 *
 * 이 로직의 결함은 대부분 "표본이 이럴 때 무엇을 고르는가"에서 나오는데, 그걸 Testcontainers 통합
 * 테스트로 검증하면 픽스처 준비가 검증하려는 규칙보다 몇 배 길어지고 한 케이스에 수 초가 든다.
 * 그래서 조합 폭발을 순수 단위 테스트로 옮겼다(`ForecastEngineTest`).
 *
 * ## 계층 (아래로 갈수록 표본 요구가 낮다)
 *
 *  1. **방법 선택(holdout)** — 타깃과 같은 요일·휴일상태인 최근 [ForecastProperties.holdoutWeeks] 주를
 *     채점판으로 두고, 후보 방법을 **그 시점 데이터만으로** 재예측해 WAPE 가 가장 낮은 것을 고른다.
 *     선택 근거(후보별 점수)를 basis 에 실어 보낸다 — 어느 방법이 왜 뽑혔는지 화면에서 보여야
 *     "숫자를 믿을지"를 사람이 판단할 수 있다.
 *  2. **기존 폴백 체인** — 전주 동요일(×재실 비율) → 4주 같은 요일 평균. 표본이
 *     [ForecastProperties.minHoldoutPoints] 미만이면 **이 경로만 타므로 도입 전과 결과가 같다.**
 *  3. **참여율(cold start)** — 위가 전부 실패한 셀에서만 쓴다. 재직인원 × 최근 참여율 × 요일 계수.
 *
 * ## 지키는 불변식 (전부 회귀 테스트로 고정)
 *
 *  - **`NO_DATA` ≠ 0.** 근거가 없으면 숫자를 지어내지 않는다. 발주가 0 이 되는 것보다 "모른다"가 낫다.
 *  - **휴일에 평일 실적을 대입하지 않는다.** 모든 후보의 표본은 타깃과 휴일 상태가 같은 날뿐이다.
 *    과거 휴일이 없으면 휴일 셀은 `NO_DATA` 로 남는다(도입 전 동작 유지).
 *  - **배식한 적 없는 요일은 예측하지 않는다.** 참여율은 같은 요일 관측이 하나라도 있을 때만 성립한다 —
 *    없으면 토요일에 평일 수준을 예측하는 사고가 난다(주말은 `at()` 이 null 이라 표본에서 조용히 빠진다).
 *  - **누수 없음.** 후보는 평가 시점 `date` 이전 날짜만 읽고, 재실 모수도 그 시점 복원값을 쓴다.
 */
@Component
class ForecastEngine(
    private val props: ForecastProperties,
) {
    /**
     * @param referenceHeadcount 타깃 시점의 재실 모수. 예측은 현재값, 백테스트는 타깃 전일 복원값이다
     *   (호출부가 결정한다 — 엔진은 "그 시점에 알 수 있던 모수"가 무엇인지 판단하지 않는다).
     */
    fun predict(
        actuals: ActualsAt,
        headcounts: HeadcountsAsOf,
        holidays: HolidayLookup,
        date: LocalDate,
        site: UUID?,
        window: String,
        referenceHeadcount: Long?,
        /** null 이면 배포 설정(`taspa.forecast.method-selection-enabled`)을 따른다. 요청 단위 실험용. */
        selectionEnabled: Boolean? = null,
    ): ForecastPrediction {
        val ctx = Ctx(actuals, headcounts, holidays, site, window, holidays.classOf(date))
        val targetHoliday = holidays.isHoliday(date)
        val holidayName = holidays.nameOf(date)

        if (selectionEnabled ?: props.methodSelectionEnabled) {
            val selected = selectByHoldout(ctx, date, referenceHeadcount, targetHoliday)
            if (selected != null) return selected.withHoliday(targetHoliday, holidayName).withEvent(holidays, date)
        }

        // 방법은 기존 체인이 정한다. **산포 추정은 선택과 분리한다** — "어느 방법을 쓸지"는 채점판
        // 4점으로 정하기엔 근거가 약하지만(위 설정 KDoc 의 실측), "그 방법이 얼마나 틀리는지"는 같은
        // 채점판으로 정직하게 말할 수 있고 발주에 바로 쓰인다.
        val chain = legacyChain(ctx, date, referenceHeadcount, targetHoliday, holidayName)
        return chain.copy(p90 = dispersionP90(ctx, date, chain, targetHoliday)).withEvent(holidays, date)
    }

    // ---- 1) 홀드아웃 기반 방법 선택 ----

    private fun selectByHoldout(
        ctx: Ctx,
        date: LocalDate,
        hcRef: Long?,
        targetHoliday: Boolean,
    ): ForecastPrediction? {
        val holdout = holdoutDates(ctx, date, targetHoliday)
        if (holdout.size < props.minHoldoutPoints) return null
        // 점 개수뿐 아니라 실적 총량도 본다 — 작은 셀에서는 1인분 차이가 큰 상대오차라 승패가 잡음이다.
        val holdoutVolume = holdout.sumOf { ctx.actuals.at(it, ctx.site, ctx.window) ?: 0L }
        if (holdoutVolume < props.minHoldoutVolume) return null

        val scores = ArrayList<CandidateScore>(CANDIDATE_COUNT)
        var incumbent: Scored? = null
        var challenger: Scored? = null
        for (candidate in candidates()) {
            val scored = score(ctx, candidate, holdout, targetHoliday) ?: continue
            scores += CandidateScore(candidate.method, round4(scored.wape), scored.points)
            if (candidate === Seasonal) {
                incumbent = scored
            } else if (challenger == null || scored.wape < challenger.wape) {
                challenger = scored
            }
        }
        // 기존 방법(전주 동요일)이 기본값이고, 도전자는 **마진을 넘을 때만** 그 자리를 가져간다.
        // 기존 방법을 채점할 수 없었다면(전주 실적 부재) 도전자가 곧 순이득이므로 그대로 쓴다.
        val winner =
            when {
                incumbent == null -> challenger ?: return null
                challenger != null && challenger.wape < incumbent.wape * (1.0 - props.selectionMargin) -> challenger
                else -> incumbent
            }
        val value = winner.candidate.at(ctx, date, hcRef, targetHoliday) ?: return null
        return ForecastPrediction(
            predicted = value,
            p90 = p90Of(value, winner.ratios),
            method = winner.candidate.method,
            basis =
                ForecastBasis(
                    lastWeekActual = ctx.actuals.at(date.minusDays(7), ctx.site, ctx.window),
                    headcountNow = hcRef,
                    headcountLastWeek = ctx.headcounts.asOf(date.minusDays(7)),
                    excludedHolidayBasis = 0,
                    sampleCount = winner.points,
                    holdoutPoints = holdout.size,
                    holdoutWape = round4(winner.wape),
                    candidates = scores.sortedBy { it.wape },
                ),
            holiday = false,
            holidayName = null,
        )
    }

    /**
     * 채점판 = 타깃과 **같은 요일·같은 휴일상태**이고 실적이 있는 과거 날짜, 최근 것부터
     * [ForecastProperties.holdoutWeeks] 개. 요일을 섞으면 요일 효과가 오차로 잡혀 방법 선택이 왜곡된다.
     */
    private fun holdoutDates(
        ctx: Ctx,
        date: LocalDate,
        targetHoliday: Boolean,
    ): List<LocalDate> {
        val out = ArrayList<LocalDate>(props.holdoutWeeks)
        for (weeksBack in 1..props.holdoutWeeks) {
            val d = date.minusDays(7L * weeksBack)
            if (ctx.holidays.classOf(d) != ctx.targetClass) continue
            if (ctx.actuals.at(d, ctx.site, ctx.window) == null) continue
            out += d
        }
        return out
    }

    private fun score(
        ctx: Ctx,
        candidate: Candidate,
        holdout: List<LocalDate>,
        targetHoliday: Boolean,
    ): Scored? {
        var absErr = 0.0
        var actualSum = 0.0
        var points = 0
        val ratios = ArrayList<Double>(holdout.size)
        for (h in holdout) {
            // 그 시점에 알 수 있던 모수 = 타깃 전일 복원값(예측 수행 가능 최후 시점).
            val predicted = candidate.at(ctx, h, ctx.headcounts.asOf(h.minusDays(1)), targetHoliday) ?: continue
            val actual = ctx.actuals.at(h, ctx.site, ctx.window) ?: continue
            points++
            absErr += abs(predicted - actual).toDouble()
            actualSum += actual.toDouble()
            if (predicted > 0) ratios += actual.toDouble() / predicted.toDouble()
        }
        if (points < props.minHoldoutPoints || actualSum <= 0.0) return null
        return Scored(candidate, absErr / actualSum, points, ratios)
    }

    /**
     * 준비량 = 점예측 × 홀드아웃 실적/예측 비율의 서비스 수준 분위수. 점예측보다 작아질 수 없게 1.0 으로
     * 하한을 둔다 — 준비량이 예측보다 적으면 그 값의 존재 이유(품절 방지)가 사라진다.
     * 근거(홀드아웃)가 부족하면 **null** 이다: 산포를 모르는 채 만든 분위수는 발주 담당자를 오도한다.
     */
    private fun p90Of(
        point: Long,
        ratios: List<Double>,
    ): Long? {
        if (ratios.size < props.minHoldoutPoints) return null
        return (point * maxOf(1.0, quantile(ratios, props.serviceLevel))).roundToLong()
    }

    /**
     * 선택된(=기존 체인이 정한) 방법을 **그 방법 그대로** 채점판에서 재예측해 실적/예측 비율의 분위수를 만든다.
     * 방법이 바뀌지 않으므로 점예측에는 영향이 없고, 준비량만 새로 생긴다.
     */
    private fun dispersionP90(
        ctx: Ctx,
        date: LocalDate,
        chain: ForecastPrediction,
        targetHoliday: Boolean,
    ): Long? {
        val point = chain.predicted ?: return null
        val candidate = candidateFor(chain.method) ?: return null
        val holdout = holdoutDates(ctx, date, targetHoliday)
        if (holdout.size < props.minHoldoutPoints) return null
        val scored = score(ctx, candidate, holdout, targetHoliday) ?: return null
        return p90Of(point, scored.ratios)
    }

    private fun candidateFor(method: ForecastMethod): Candidate? =
        when (method) {
            ForecastMethod.SEASONAL_NAIVE_ADJUSTED, ForecastMethod.SEASONAL_NAIVE -> Seasonal
            ForecastMethod.FOUR_WEEK_AVG -> FourWeekAvg()
            ForecastMethod.TRIMMED_SEASONAL -> TrimmedSeasonal()
            ForecastMethod.PARTICIPATION_RATE -> Participation
            // COMPOSITE 는 가맹 합산 셀 전용이라 이 엔진(단일 시계열)에는 들어오지 않는다 — 방어적 null.
            ForecastMethod.NO_DATA, ForecastMethod.COMPOSITE -> null
        }

    // ---- 2) 기존 폴백 체인 (도입 전 동작을 그대로 보존한다) ----

    private fun legacyChain(
        ctx: Ctx,
        date: LocalDate,
        hcRef: Long?,
        targetHoliday: Boolean,
        holidayName: String?,
    ): ForecastPrediction {
        var lastWeek: Long? = null
        var excluded = 0
        val samples = ArrayList<Long>(props.lookbackWeeks)
        for (weeksBack in 1..props.lookbackWeeks) {
            val basisDate = date.minusDays(7L * weeksBack)
            val value = ctx.actuals.at(basisDate, ctx.site, ctx.window) ?: continue
            if (ctx.holidays.classOf(basisDate) != ctx.targetClass) {
                excluded++
                continue
            }
            if (weeksBack == 1) lastWeek = value
            samples += value
        }
        val hcLastWeek = ctx.headcounts.asOf(date.minusDays(7))

        if (lastWeek != null) {
            val ratio = ratioOrNull(hcRef, hcLastWeek)
            val method = if (ratio != null) ForecastMethod.SEASONAL_NAIVE_ADJUSTED else ForecastMethod.SEASONAL_NAIVE
            val value = if (ratio != null) (lastWeek * ratio).roundToLong() else lastWeek
            return prediction(value, method, lastWeek, hcRef, hcLastWeek, excluded, samples.size, targetHoliday, holidayName)
        }
        if (samples.isNotEmpty()) {
            return prediction(
                samples.average().roundToLong(),
                ForecastMethod.FOUR_WEEK_AVG,
                null,
                hcRef,
                hcLastWeek,
                excluded,
                samples.size,
                targetHoliday,
                holidayName,
            )
        }
        // 3) 마지막 수단 — 참여율. 여기까지 왔다는 것은 도입 전이라면 NO_DATA 였다는 뜻이다.
        val participation = Participation.at(ctx, date, hcRef, targetHoliday)
        if (participation != null) {
            return prediction(
                participation,
                ForecastMethod.PARTICIPATION_RATE,
                null,
                hcRef,
                hcLastWeek,
                excluded,
                null,
                targetHoliday,
                holidayName,
            )
        }
        return prediction(null, ForecastMethod.NO_DATA, null, hcRef, hcLastWeek, excluded, null, targetHoliday, holidayName)
    }

    // ---- 후보 방법 ----

    private fun candidates(): List<Candidate> = listOf(Seasonal, TrimmedSeasonal(), Participation)

    /** 기존 체인의 4주 같은 요일 평균. 산포 채점을 위해 후보 형태로도 노출한다(계산은 동일). */
    private inner class FourWeekAvg : Candidate {
        override val method = ForecastMethod.FOUR_WEEK_AVG

        override fun at(
            ctx: Ctx,
            date: LocalDate,
            hcRef: Long?,
            targetHoliday: Boolean,
        ): Long? {
            val samples = ArrayList<Long>(props.lookbackWeeks)
            for (weeksBack in 1..props.lookbackWeeks) {
                val d = date.minusDays(7L * weeksBack)
                if (ctx.holidays.classOf(d) != ctx.targetClass) continue
                samples += ctx.actuals.at(d, ctx.site, ctx.window) ?: continue
            }
            return if (samples.isEmpty()) null else samples.average().roundToLong()
        }
    }

    private interface Candidate {
        val method: ForecastMethod

        fun at(
            ctx: Ctx,
            date: LocalDate,
            hcRef: Long?,
            targetHoliday: Boolean,
        ): Long?
    }

    /** 전주 동요일 × 재실 비율. 단일 점이라 분산에 취약하지만, 급변에 가장 빠르게 반응한다. */
    private object Seasonal : Candidate {
        override val method = ForecastMethod.SEASONAL_NAIVE_ADJUSTED

        override fun at(
            ctx: Ctx,
            date: LocalDate,
            hcRef: Long?,
            targetHoliday: Boolean,
        ): Long? {
            val basisDate = date.minusDays(7)
            if (ctx.holidays.classOf(basisDate) != ctx.targetClass) return null
            val value = ctx.actuals.at(basisDate, ctx.site, ctx.window) ?: return null
            val ratio = ctx.ratio(hcRef, ctx.headcounts.asOf(basisDate)) ?: return value
            return (value * ratio).roundToLong()
        }
    }

    /**
     * 동요일 표본을 **참여율 공간에서 트림 평균** 한 뒤 타깃 모수로 환산한다.
     * 트림이 막는 것: 전사 행사·정전·시험기간 같은 단발 이상치가 전주 하나를 통해 다음 주 예측을 오염시키는 일.
     */
    private inner class TrimmedSeasonal : Candidate {
        override val method = ForecastMethod.TRIMMED_SEASONAL

        override fun at(
            ctx: Ctx,
            date: LocalDate,
            hcRef: Long?,
            targetHoliday: Boolean,
        ): Long? {
            val raw = ArrayList<Double>(props.profileWeeks)
            val rates = ArrayList<Double>(props.profileWeeks)
            for (weeksBack in 1..props.profileWeeks) {
                val d = date.minusDays(7L * weeksBack)
                if (ctx.holidays.classOf(d) != ctx.targetClass) continue
                val value = ctx.actuals.at(d, ctx.site, ctx.window) ?: continue
                raw += value.toDouble()
                val hc = ctx.headcounts.asOf(d)
                if (hc != null && hc > 0) rates += value.toDouble() / hc
            }
            if (raw.size < props.minSeasonalSamples) return null
            // 모든 표본의 모수를 복원할 수 있을 때만 참여율 공간을 쓴다 — 일부만 정규화하면 두 공간이 섞인다.
            if (hcRef != null && hcRef > 0 && rates.size == raw.size) {
                val level = trimmedMean(rates) ?: return null
                return (level * hcRef).roundToLong()
            }
            return trimmedMean(raw)?.roundToLong()
        }
    }

    /**
     * 재직인원 × 최근 참여율 × 요일 계수. 동요일 표본이 부족한 구간(신규 사업장·운영 초기·데이터 공백)의
     * **커버리지**를 담당한다 — 다른 요일에서 배운 참여 수준을 빌려오기 때문이다.
     *
     * ★같은 요일 관측이 하나도 없으면 **산출하지 않는다.** 주말·미배식 끼니는 `at()` 이 null 이라
     *   표본에서 조용히 빠지므로, 이 가드가 없으면 토요일에 평일 수준을 예측한다(없는 것보다 나쁘다).
     */
    private object Participation : Candidate {
        override val method = ForecastMethod.PARTICIPATION_RATE

        override fun at(
            ctx: Ctx,
            date: LocalDate,
            hcRef: Long?,
            targetHoliday: Boolean,
        ): Long? {
            if (hcRef == null || hcRef <= 0) return null
            val p = ctx.props
            val rates = ArrayList<Double>()
            val sameDow = ArrayList<Double>()
            var d = date.minusDays(7L * p.profileWeeks)
            while (d.isBefore(date)) {
                if (ctx.holidays.classOf(d) == ctx.targetClass) {
                    val value = ctx.actuals.at(d, ctx.site, ctx.window)
                    val hc = ctx.headcounts.asOf(d)
                    if (value != null && hc != null && hc > 0) {
                        val rate = value.toDouble() / hc
                        rates += rate
                        if (d.dayOfWeek == date.dayOfWeek) sameDow += rate
                    }
                }
                d = d.plusDays(1)
            }
            if (rates.size < p.minParticipationDays || sameDow.isEmpty()) return null
            val level = ctx.trimmed(rates) ?: return null
            if (level <= 0.0) return null
            // 요일 계수는 표본이 2개 이상일 때만 — 1개로 만든 계수는 그 날의 잡음이다.
            val factor = if (sameDow.size >= 2) (ctx.trimmed(sameDow) ?: level) / level else 1.0
            return (level * factor * hcRef).roundToLong()
        }
    }

    // ---- 공통 계산 ----

    private fun ratioOrNull(
        hcRef: Long?,
        hcThen: Long?,
    ): Double? {
        if (hcRef == null || hcThen == null || hcRef <= 0 || hcThen <= 0) return null
        val ratio = hcRef.toDouble() / hcThen
        return ratio.takeIf { it in props.headcountRatioMin..props.headcountRatioMax }
    }

    private fun trimmedMean(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val drop = floor(sorted.size * props.trimRatio).toInt()
        val kept = if (sorted.size - 2 * drop >= 1) sorted.subList(drop, sorted.size - drop) else sorted
        return kept.average()
    }

    private fun quantile(
        values: List<Double>,
        p: Double,
    ): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val pos = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private fun prediction(
        value: Long?,
        method: ForecastMethod,
        lastWeek: Long?,
        hcRef: Long?,
        hcLastWeek: Long?,
        excluded: Int,
        sampleCount: Int?,
        holiday: Boolean,
        holidayName: String?,
    ) = ForecastPrediction(
        predicted = value,
        p90 = null,
        method = method,
        basis =
            ForecastBasis(
                lastWeekActual = lastWeek,
                headcountNow = hcRef,
                headcountLastWeek = hcLastWeek,
                excludedHolidayBasis = excluded,
                sampleCount = sampleCount,
            ),
        holiday = holiday,
        holidayName = holidayName,
    )

    private fun ForecastPrediction.withHoliday(
        holiday: Boolean,
        name: String?,
    ) = copy(holiday = holiday, holidayName = name)

    private fun ForecastPrediction.withEvent(
        lookup: HolidayLookup,
        date: LocalDate,
    ) = if (lookup.isEvent(date)) copy(event = true, eventName = lookup.eventNameOf(date)) else this

    private inner class Ctx(
        val actuals: ActualsAt,
        val headcounts: HeadcountsAsOf,
        val holidays: HolidayLookup,
        val site: UUID?,
        val window: String,
        /** 타깃일의 성격. basis 후보는 이것과 같은 성격일 때만 채택한다(대칭). */
        val targetClass: DayClass,
    ) {
        val props: ForecastProperties get() = this@ForecastEngine.props

        fun ratio(
            hcRef: Long?,
            hcThen: Long?,
        ): Double? = ratioOrNull(hcRef, hcThen)

        fun trimmed(values: List<Double>): Double? = trimmedMean(values)
    }

    private class Scored(
        val candidate: Candidate,
        val wape: Double,
        val points: Int,
        val ratios: List<Double>,
    )

    private companion object {
        const val CANDIDATE_COUNT = 3

        fun round4(value: Double): Double = (value * 10_000).roundToLong() / 10_000.0
    }
}
