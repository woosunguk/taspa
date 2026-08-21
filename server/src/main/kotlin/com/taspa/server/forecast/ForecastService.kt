package com.taspa.server.forecast

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.consumption.MealWindow
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.MembershipChangeType
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.forecast.dto.BacktestCell
import com.taspa.server.forecast.dto.BacktestResponse
import com.taspa.server.forecast.dto.BacktestSummary
import com.taspa.server.forecast.dto.ForecastBasis
import com.taspa.server.forecast.dto.ForecastCell
import com.taspa.server.forecast.dto.ForecastMethod
import com.taspa.server.forecast.dto.ForecastResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 식수예측 P0 베이스라인(설계 문서 §2.1·§9) — **온디맨드 계산, 저장 없음**(스키마 무변경).
 * 그레인 = (끼니 × 식당(site) × 일), 방법 = 전주 동요일(seasonal-naive) × 재실모수 보정.
 *
 * 폴백 체인(문서 §6 베이스라인): 전주 동요일 실적 × 재실비율 → (이력 복원 불가 시) 전주 동요일 그대로 →
 * (전주 데이터 없음) 최근 4주 같은 요일 평균 → (그것도 없음) predicted=null(NO_DATA — 0 이 아니라 데이터 없음).
 *
 * 재실 비율(headcountRatio) = 현재 활성 인원 ÷ 전주 시점 활성 인원.
 *  - 현재: org_memberships (status=ACTIVE ∧ employment_status=EMPLOYED) 카운트.
 *  - 전주 시점: org_membership_history append-only SCD 에서 recorded_at 기반으로 복원
 *    (MembershipHistoryRepository.countActiveEmployedAsOf — 사용자별 마지막 스냅샷이 비제거·재직).
 *  - 어느 한쪽이 0(복원 불가·이력 없음)이거나 비율이 신뢰 범위([HEADCOUNT_RATIO_MIN]~[HEADCOUNT_RATIO_MAX])
 *    밖이면 비율 보정을 생략하고 method=SEASONAL_NAIVE 로 구분한다 — 이력은 V22 훅 도입 후 변경이 있었던
 *    멤버십만 커버하므로 부분 복원된 작은 양수(hcThen≪hcNow)가 비율을 수십 배로 부풀릴 수 있다.
 *
 * 모든 날짜·요일·주 경계는 **org 타임존 앵커**다 — 소비 집계의 기존 date 버킷 수식을 그대로 재사용한
 * aggregateByDateSiteWindow 로 실적을 읽고, LocalDate↔Instant 변환도 같은 존으로 한다.
 * 집계 파생값만 노출한다(개별 이벤트·user_sub 미노출 — 소비 집계와 동일 규칙).
 *
 * **완결일 경계(예측 경로 한정)**: 예측 basis 로는 **org-로컬 어제까지의 실적만** 쓴다. 오늘은 아직
 * 적재 중인 부분값이라 같은 예측을 아침과 저녁에 조회하면 값이 달라지는데, 응답은 lastWeekActual 을
 * 확정 실적처럼 제시하므로 그 흔들림이 곧 오정보다. 기본 7일 지평의 T+7 셀이 정확히 이 경우이며
 * (basis = 오늘), 경계를 넣으면 D-14 기반 [ForecastMethod.FOUR_WEEK_AVG] 등으로 강등된다 —
 * 부분값을 SEASONAL_NAIVE(_ADJUSTED) 로 위장하는 것보다 강등이 정직하다. 재실 모수(hcNow)는 실적이
 * 아니라 현재 인원 스냅샷이라 이 경계와 무관하다. **백테스트는 이 경계를 재현하지 못한다** — 타깃이
 * 어제 이하라 basis 가 항상 완결 구간이고, 따라서 운영 예측이 겪는 강등이 지표에 반영되지 않는다.
 *
 * **휴일 인지(조직 캘린더)**: [HolidayCalendar] 가 판정한 휴일을 두 방향으로 쓴다.
 *  1. **드러내기** — 타깃일이 휴일이면 셀에 holiday/holidayName 을 담는다. 다만 predicted 를 0 으로
 *     단정하지 않는다(휴일 당직 식사가 존재한다) — "휴일이라는 사실"과 "예측값"은 별개 정보다.
 *  2. **basis 필터** — 과거 주(D-7·14·21·28)를 basis 후보로 쓸 때 **그 날의 휴일 여부가 타깃과 같을 때만**
 *     채택한다. 방향은 대칭이다: 휴일 실적으로 평일을 예측하면 크게 과소예측되고(품절), 평일 실적으로
 *     휴일을 예측하면 크게 과대예측된다(잔반). 후보가 전부 걸러지면 [ForecastMethod.NO_DATA] 로 떨어지는데,
 *     이는 "휴일인데 비교 가능한 휴일 실적이 없다"는 정직한 응답이다 — 평일 숫자를 휴일에 붙이는 것보다 낫다.
 * 캘린더가 없는 조직은 인덱스가 비어 모든 날이 평일로 판정되므로 **동작이 캘린더 도입 전과 정확히 같다.**
 *
 * 백테스트에도 같은 휴일 판정을 쓴다. 공휴일·창립기념일은 구조적으로 선행 확정 정보라 D-1 시점에 알 수
 * 있으므로 미래정보 누수가 아니다. 다만 `calendar_events` 는 재동기화 시 sweep-recreate 되어 "그 시점에 이
 * 이벤트를 알고 있었는가"를 재현할 수 없다 — 즉 백테스트의 휴일 반영은 **"캘린더가 처음부터 있었다면"의
 * 상한**이다(완결일 경계와 같은 성격의 한계).
 */
@Service
class ForecastService(
    private val eventRepository: ConsumptionEventRepository,
    private val organizationRepository: OrganizationRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val historyRepository: MembershipHistoryRepository,
    private val siteRepository: SiteRepository,
    private val holidayCalendar: HolidayCalendar,
) {
    /**
     * D+1~D+7 배치성 예측 조회. from/to 미지정 시 org-로컬 내일부터 7일. 창 최대 [MAX_FORECAST_WINDOW_DAYS]일.
     * siteId 지정 시 그 site 소비만, 미지정 시 org 전체(총식수, siteId=null 셀) + 관측된 site 별 분해 목록.
     * mealWindow 미지정 시 3끼 전부.
     */
    @Transactional(readOnly = true)
    fun forecast(
        orgId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        siteId: UUID?,
        mealWindow: String?,
    ): ForecastResponse {
        val org = requireActiveOrg(orgId)
        val zone = zoneOf(org)
        val fromDate = from ?: LocalDate.now(zone).plusDays(1)
        val toDate = to ?: fromDate.plusDays(6)
        validateWindow(fromDate, toDate, MAX_FORECAST_WINDOW_DAYS)
        val windows = resolveWindows(mealWindow)
        if (siteId != null) requireOrgSite(orgId, siteId)

        // 실적 로딩 — 타깃 구간의 4주 전부터(폴백 체인 근거). 예측엔 타깃 날짜 실적이 필요 없지만
        // 단일 쿼리 유지가 단순하고 창 상한(31일+28일)이라 비용은 유계다. 상단은 org-로컬 어제로
        // 당긴다 — 오늘은 적재 중인 부분값이라 basis 로 쓰면 조회 시각마다 예측이 흔들린다.
        val actuals =
            loadActuals(
                orgId,
                zone,
                fromDate.minusDays(LOOKBACK_DAYS),
                minOf(toDate, LocalDate.now(zone).minusDays(1)),
            )
        val siteAxes: List<UUID?> = if (siteId != null) listOf(siteId) else listOf(null) + actuals.sites()
        val headcounts = HeadcountLookup(orgId, zone)
        headcounts.preload(fromDate.minusDays(7), toDate.minusDays(7)) // asOf(date-7) 수요 구간 일괄 계산
        // 휴일은 타깃 구간뿐 아니라 basis 후보 구간(타깃-28일까지)도 알아야 한다 — 실적과 달리 완결일
        // 경계를 적용하지 않는다(캘린더는 미래를 향한 선행 정보이고, 그것이 이 신호의 존재 이유다).
        val holidays = holidayCalendar.load(orgId, fromDate.minusDays(LOOKBACK_DAYS), toDate)

        val cells = ArrayList<ForecastCell>()
        var date = fromDate
        while (!date.isAfter(toDate)) {
            // 예측 시점의 재실 모수 = 현재(멤버십 테이블), 전주 시점 = 이력 복원(타깃-7일의 그 날 끝 기준).
            val hcNow = headcounts.current()
            val hcLastWeek = headcounts.asOf(date.minusDays(7))
            for (axis in siteAxes) {
                for (window in windows) {
                    val p = predictCell(actuals, holidays, date, axis, window, hcNow, hcLastWeek)
                    cells.add(
                        ForecastCell(date, axis, window, p.predicted, p.method, p.basis, p.holiday, p.holidayName),
                    )
                }
            }
            date = date.plusDays(1)
        }
        return ForecastResponse(orgId, fromDate, toDate, siteId, mealWindow?.let { MealWindow.parse(it).name }, cells)
    }

    /**
     * 백테스트 하네스(P0 산출물) — 과거 구간의 각 셀에 대해 "그 시점에 예측했을 값"(재실 모수도 예측 수행
     * 가능 최후 시점인 타깃 전일(D-1) 끝 이력으로 복원)을 같은 폴백 체인으로 계산해 실적과 비교한다.
     * 창 최대 [MAX_BACKTEST_WINDOW_DAYS]일.
     * siteId 미지정 시 org 전체(총식수) 축만 평가한다 — org 총합과 site 분해를 한 요약에 섞으면 지표가
     * 이중집계되기 때문. 실적이 없는 셀은 actual=0 으로 보되 MAPE 분모에서는 제외한다(DTO 주석 참조).
     *
     * **완결일 경계는 여기 적용되지 않는다(구조적으로 무의미)** — 타깃이 어제 이하라 basis(타깃-7일
     * 이전)와 채점용 실적이 모두 완결 구간이다. 대신 이 지표는 운영 예측의 T+7 강등을 재현하지 못하는
     * "완결 실적이 늘 있었다면"의 상한이라는 점을 감안해 읽어야 한다(클래스 KDoc 참조).
     */
    @Transactional(readOnly = true)
    fun backtest(
        orgId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        siteId: UUID?,
        mealWindow: String?,
    ): BacktestResponse {
        val org = requireActiveOrg(orgId)
        val zone = zoneOf(org)
        val toDate = to ?: LocalDate.now(zone).minusDays(1)
        val fromDate = from ?: toDate.minusDays(27)
        validateWindow(fromDate, toDate, MAX_BACKTEST_WINDOW_DAYS)
        if (toDate.isAfter(LocalDate.now(zone).minusDays(1))) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "백테스트는 어제까지의 과거 구간만 가능합니다")
        }
        val windows = resolveWindows(mealWindow)
        if (siteId != null) requireOrgSite(orgId, siteId)

        val actuals = loadActuals(orgId, zone, fromDate.minusDays(LOOKBACK_DAYS), toDate)
        val headcounts = HeadcountLookup(orgId, zone)
        headcounts.preload(fromDate.minusDays(7), toDate.minusDays(1)) // asOf 수요 구간(타깃-7 ~ 타깃-1) 일괄 계산
        val holidays = holidayCalendar.load(orgId, fromDate.minusDays(LOOKBACK_DAYS), toDate)

        val cells = ArrayList<BacktestCell>()
        var scored = 0
        var mapeSum = 0.0
        var mapeN = 0
        var mapeExcluded = 0
        var absErrSum = 0.0
        var actualSum = 0.0
        var predictedSum = 0.0
        var date = fromDate
        while (!date.isAfter(toDate)) {
            // "그 시점" 재현: 운영 예측은 늦어도 D-1 에 수행되므로, 현재 인원 자리에 예측 수행 가능 최후
            // 시점(타깃 전일 끝)의 이력 복원값을 쓴다 — 타깃 당일 recorded 스냅샷(당일 입·퇴사)은 실전
            // 예측이 알 수 없는 미래정보라 제외한다. 주의(모수 소스 비대칭, P0 근사): 운영 경로는
            // (멤버십 테이블 현재값 ÷ 이력 복원값) 쌍, 백테스트는 (이력 ÷ 이력) 쌍이다.
            val hcThen = headcounts.asOf(date.minusDays(1))
            val hcWeekBefore = headcounts.asOf(date.minusDays(7))
            for (window in windows) {
                val p = predictCell(actuals, holidays, date, siteId, window, hcThen, hcWeekBefore)
                val actual = actuals.at(date, siteId, window) ?: 0L
                cells.add(
                    BacktestCell(
                        date,
                        siteId,
                        window,
                        p.predicted,
                        p.method,
                        actual,
                        p.basis,
                        p.holiday,
                        p.holidayName,
                    ),
                )
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
        val summary =
            BacktestSummary(
                cells = cells.size,
                scoredCells = scored,
                mape = if (mapeN > 0) mapeSum / mapeN else null,
                mapeExcludedZeroActual = mapeExcluded,
                wape = if (actualSum > 0) absErrSum / actualSum else null,
                bias = if (actualSum > 0) (predictedSum - actualSum) / actualSum else null,
            )
        return BacktestResponse(
            orgId,
            fromDate,
            toDate,
            siteId,
            mealWindow?.let { MealWindow.parse(it).name },
            cells,
            summary,
        )
    }

    // ---- 예측 코어 ----

    private data class Prediction(
        val predicted: Long?,
        val method: ForecastMethod,
        val basis: ForecastBasis,
        val holiday: Boolean,
        val holidayName: String?,
    )

    /**
     * 셀 1개 예측(폴백 체인). hcNow/hcThen 은 호출부가 결정한 재실 모수 쌍 — 예측은 (현재, 타깃-7일),
     * 백테스트는 (타깃-1일, 타깃-7일). 둘 다 양수이고 비율이 신뢰 범위([HEADCOUNT_RATIO_MIN]~
     * [HEADCOUNT_RATIO_MAX]) 안일 때만 비율 보정한다 — 0/null(복원 불가)뿐 아니라 부분 SCD 복원이 만드는
     * 작은 양수 hcThen(hcThen≪hcNow)의 비율 폭주도 보정 생략(SEASONAL_NAIVE)으로 강등한다(클래스 KDoc 참조).
     *
     * basis 후보(D-7·14·21·28)는 **휴일 여부가 타깃과 일치할 때만** 채택한다(클래스 KDoc "휴일 인지").
     * 캘린더가 없으면 모든 날이 평일로 판정돼 필터가 아무것도 걸러내지 않으므로, 아래 루프는 캘린더 도입
     * 전의 "D-7 우선, 없으면 4주 평균"과 정확히 같은 결과를 낸다.
     */
    private fun predictCell(
        actuals: ActualsIndex,
        holidays: HolidayIndex,
        date: LocalDate,
        site: UUID?,
        window: String,
        hcNow: Long?,
        hcThen: Long?,
    ): Prediction {
        val targetHoliday = holidays.isHoliday(date)
        val holidayName = holidays.nameOf(date)
        var lastWeek: Long? = null
        var excluded = 0
        val samples = ArrayList<Long>(LOOKBACK_WEEKS)
        for (weeksBack in 1..LOOKBACK_WEEKS) {
            val basisDate = date.minusDays(7L * weeksBack)
            val value = actuals.at(basisDate, site, window) ?: continue
            if (holidays.isHoliday(basisDate) != targetHoliday) {
                excluded++
                continue
            }
            if (weeksBack == 1) lastWeek = value
            samples.add(value)
        }

        if (lastWeek != null) {
            if (hcNow != null && hcThen != null && hcNow > 0 && hcThen > 0) {
                val ratio = hcNow.toDouble() / hcThen
                if (ratio in HEADCOUNT_RATIO_MIN..HEADCOUNT_RATIO_MAX) {
                    return Prediction(
                        (lastWeek * ratio).roundToLong(),
                        ForecastMethod.SEASONAL_NAIVE_ADJUSTED,
                        ForecastBasis(lastWeek, hcNow, hcThen, excluded),
                        targetHoliday,
                        holidayName,
                    )
                }
            }
            return Prediction(
                lastWeek,
                ForecastMethod.SEASONAL_NAIVE,
                ForecastBasis(lastWeek, hcNow, hcThen, excluded),
                targetHoliday,
                holidayName,
            )
        }
        // 폴백: 최근 4주 같은 요일 평균(채택된 주만 — 전주는 위에서 부재·휴일불일치가 확정이다).
        if (samples.isNotEmpty()) {
            return Prediction(
                samples.average().roundToLong(),
                ForecastMethod.FOUR_WEEK_AVG,
                ForecastBasis(null, hcNow, hcThen, excluded),
                targetHoliday,
                holidayName,
            )
        }
        return Prediction(
            null,
            ForecastMethod.NO_DATA,
            ForecastBasis(null, hcNow, hcThen, excluded),
            targetHoliday,
            holidayName,
        )
    }

    // ---- 실적 인덱스 ----

    /** (date, site, window) → 수량 합. site=null 조회는 org 전체(모든 site + site 미지정 행의 합)다. */
    private class ActualsIndex {
        private val orgTotal = HashMap<Pair<LocalDate, String>, Long>()
        private val perSite = HashMap<Triple<LocalDate, UUID, String>, Long>()

        fun add(
            date: LocalDate,
            site: UUID?,
            window: String,
            quantity: Long,
        ) {
            orgTotal.merge(date to window, quantity, Long::plus)
            if (site != null) perSite.merge(Triple(date, site, window), quantity, Long::plus)
        }

        fun at(
            date: LocalDate,
            site: UUID?,
            window: String,
        ): Long? = if (site == null) orgTotal[date to window] else perSite[Triple(date, site, window)]

        /** 실적이 관측된 site 목록(결정적 순서). org 전체 축 뒤에 붙는 site 분해 목록의 축이 된다. */
        fun sites(): List<UUID> = perSite.keys.mapTo(HashSet()) { it.second }.sortedBy { it.toString() }
    }

    /**
     * [fromDate, toDate] 구간(org-로컬 달력)의 CONFIRMED 실적을 단일 그룹쿼리로 읽어 인덱싱한다.
     * 호출부가 완결일로 상단을 당겨 구간이 비면(먼 미래 조회) 질의 없이 빈 인덱스 → 전 셀 NO_DATA.
     */
    private fun loadActuals(
        orgId: UUID,
        zone: ZoneId,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): ActualsIndex {
        if (toDate.isBefore(fromDate)) return ActualsIndex()
        val from = fromDate.atStartOfDay(zone).toInstant()
        val to = toDate.plusDays(1).atStartOfDay(zone).toInstant()
        val rows = eventRepository.aggregateByDateSiteWindow(orgId, from, to, zone.id, MAX_AGGREGATE_ROWS)
        if (rows.size >= MAX_AGGREGATE_ROWS) {
            // LIMIT 도달 = ORDER BY bucket_date ASC 라 최신 날짜 그룹부터 조용히 잘린 상태. 소비 집계 API 와
            // 달리 여기서는 절단이 응답에 보이지 않고 파생 계산(예측 폴백 강등·백테스트 actual=0 채점)만
            // 왜곡되므로 fail-loud 한다 — 무신호 왜곡 대신 창 축소를 요구.
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "실적 집계 규모가 상한(${MAX_AGGREGATE_ROWS}행)에 도달했습니다 — 조회 창(from/to)을 줄여 다시 시도하세요",
            )
        }
        val index = ActualsIndex()
        rows.forEach { row ->
            index.add(toLocalDate(row[0]!!), toUuid(row[1]), row[2] as String, (row[3] as Number).toLong())
        }
        return index
    }

    // ---- 재실 모수 ----

    /**
     * 요청 스코프 재실 인원 조회(메모이즈). current = 멤버십 테이블의 현재 카운트,
     * asOf(date) = 이력에서 그 날짜의 끝(org-로컬 자정 직전) 기준 복원. 0 은 "복원 불가"로 null 을 돌려준다.
     * 다수 날짜 수요는 [preload] 로 일괄 계산한다 — asOf 를 날짜마다 호출하면 날짜당 이력 전체 정렬 스캔
     * (백테스트 92일 창 = 최대 99회 순차 쿼리)이라, 이력 1회 로드 + 인메모리 스위프로 수렴시킨다.
     */
    private inner class HeadcountLookup(
        private val orgId: UUID,
        private val zone: ZoneId,
    ) {
        private val cache = HashMap<LocalDate?, Long>()

        fun current(): Long? =
            cache
                .getOrPut(null) {
                    membershipRepository.countByOrgIdAndStatusAndEmploymentStatus(
                        orgId,
                        MembershipStatus.ACTIVE.name,
                        EmploymentStatus.EMPLOYED.name,
                    )
                }.takeIf { it > 0 }

        fun asOf(date: LocalDate): Long? =
            cache
                .getOrPut(date) {
                    historyRepository.countActiveEmployedAsOf(orgId, endOf(date))
                }.takeIf { it > 0 }

        /**
         * [from, to] 구간의 날짜별 복원값을 이력 단일 로드 후 시간순 스위프로 채운다. 판정은
         * countActiveEmployedAsOf 와 동일 — 사용자별 마지막 스냅샷(recorded_at ≤ 그 날짜 끝)이
         * "비제거(≠REMOVED) ∧ 재직(EMPLOYED)"인 사용자 수. 이후 asOf 는 캐시 적중으로 무쿼리.
         */
        fun preload(
            from: LocalDate,
            to: LocalDate,
        ) {
            if (to.isBefore(from)) return
            val snapshots =
                historyRepository
                    .findByOrgIdAndRecordedAtLessThanEqualOrderByRecordedAtAscIdAsc(orgId, endOf(to))
            val lastActive = HashMap<UUID, Boolean>() // userId → 마지막 스냅샷이 비제거·재직인지
            var activeCount = 0L
            var i = 0
            var date = from
            while (!date.isAfter(to)) {
                val boundary = endOf(date)
                while (i < snapshots.size && !snapshots[i].recordedAt.isAfter(boundary)) {
                    val s = snapshots[i]
                    val nowActive =
                        s.changeType != MembershipChangeType.REMOVED.name &&
                            s.employmentStatus == EmploymentStatus.EMPLOYED.name
                    val wasActive = lastActive.put(s.userId, nowActive) == true
                    if (nowActive && !wasActive) activeCount++
                    if (!nowActive && wasActive) activeCount--
                    i++
                }
                cache.putIfAbsent(date, activeCount)
                date = date.plusDays(1)
            }
        }

        private fun endOf(date: LocalDate): Instant = date.plusDays(1).atStartOfDay(zone).toInstant()
    }

    // ---- 검증·헬퍼 ----

    private fun validateWindow(
        from: LocalDate,
        to: LocalDate,
        maxDays: Long,
    ) {
        if (to.isBefore(from)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "to 는 from 보다 뒤여야 합니다")
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "조회 창은 최대 ${maxDays}일입니다")
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

    /** siteId 는 그 org 소속일 때만 허용한다(타 org site 탐침 차단 — 비소속은 404). */
    private fun requireOrgSite(
        orgId: UUID,
        siteId: UUID,
    ) {
        siteRepository.findByIdAndOrgId(siteId, orgId)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "사업장을 찾을 수 없습니다")
    }

    private fun requireActiveOrg(orgId: UUID): Organization {
        val org =
            organizationRepository.findById(orgId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            throw AuthException(ErrorCode.FORBIDDEN, "정지된 조직입니다")
        }
        return org
    }

    private fun zoneOf(org: Organization): ZoneId =
        try {
            ZoneId.of(org.timezone)
        } catch (ex: Exception) {
            ZoneId.of("UTC") // 저장 시 검증되므로 도달 불가 방어선 — 깨진 값이어도 예측이 500 으로 죽지 않게.
        }

    private fun toLocalDate(value: Any): LocalDate =
        when (value) {
            is Date -> value.toLocalDate()
            is LocalDate -> value
            else -> LocalDate.parse(value.toString())
        }

    private fun toUuid(value: Any?): UUID? =
        when (value) {
            null -> null
            is UUID -> value
            else -> UUID.fromString(value.toString())
        }

    private companion object {
        /** 예측 조회 창 상한(일) — D+1~D+7 배치성 조회가 목적이라 한 달이면 충분하다(자원 상한). */
        const val MAX_FORECAST_WINDOW_DAYS = 31L

        /** 백테스트 창 상한(일) — 분기 단위 평가까지 허용(자원 상한). */
        const val MAX_BACKTEST_WINDOW_DAYS = 92L

        /** 폴백 평균 주 수(같은 요일). */
        const val LOOKBACK_WEEKS = 4

        /** 실적 로딩 lookback(일) = 4주. */
        const val LOOKBACK_DAYS = 7L * LOOKBACK_WEEKS

        /** 실적 집계 행 상한 — (최대 124일) × site × 3끼 그룹 폭주 방어(소비 집계 상한과 동일 사상). */
        const val MAX_AGGREGATE_ROWS = 20000

        /**
         * 재실 비율(hcNow ÷ hcThen) 신뢰 범위 — SCD 이력이 V22 훅 도입 후 변경분만 커버해 hcThen 이
         * 심하게 과소 복원될 수 있으므로, 범위 밖 비율은 커버리지 불충분으로 보고 보정을 생략한다
         * (문서 §5 가드레일 '전주 대비 ±X%' 사상과 동일한 보수적 상·하한).
         */
        const val HEADCOUNT_RATIO_MIN = 0.5
        const val HEADCOUNT_RATIO_MAX = 2.0
    }
}
