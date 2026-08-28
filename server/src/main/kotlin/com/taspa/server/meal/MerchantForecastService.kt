package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.consumption.MealWindow
import com.taspa.server.domain.meal.MealMenuRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantForecastSettingsRepository
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.MemberAbsenceRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.forecast.ForecastSignals
import com.taspa.server.forecast.HolidayCalendar
import com.taspa.server.forecast.HolidayIndex
import com.taspa.server.forecast.SignalOverrides
import com.taspa.server.forecast.dto.BacktestSummary
import com.taspa.server.forecast.dto.ForecastMethod
import com.taspa.server.meal.dto.MerchantBacktestCell
import com.taspa.server.meal.dto.MerchantBacktestResponse
import com.taspa.server.meal.dto.MerchantBasisPoint
import com.taspa.server.meal.dto.MerchantCellDetail
import com.taspa.server.meal.dto.MerchantForecastBasis
import com.taspa.server.meal.dto.MerchantForecastCell
import com.taspa.server.meal.dto.MerchantForecastResponse
import com.taspa.server.meal.dto.MerchantMenuShare
import com.taspa.server.meal.dto.MerchantOrgInfo
import com.taspa.server.meal.dto.MerchantOrgSlice
import com.taspa.server.meal.dto.MerchantOrgSliceDetail
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
    private val organizationRepository: OrganizationRepository,
    private val holidayCalendar: HolidayCalendar,
    private val absenceRepository: MemberAbsenceRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val settingsRepository: MerchantForecastSettingsRepository,
    private val menuRepository: MealMenuRepository,
    private val siteRepository: com.taspa.server.domain.org.SiteRepository,
    private val menuService: MealMenuService,
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
        overrides: SignalOverrides = SignalOverrides(),
    ): MerchantForecastResponse {
        val merchant = requireMerchant(merchantId)
        val signals = effectiveSignals(merchantId, overrides)
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
        val orgCtx = loadOrgContext(merchantId, zone, fromDate.minusDays(LOOKBACK_DAYS), toDate, signals)
        val cells = ArrayList<MerchantForecastCell>()
        var date = fromDate
        while (!date.isAfter(toDate)) {
            for (window in windows) {
                cells.add(compositeCell(actuals, orgCtx, date, window))
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
            orgs = orgInfos(merchantId, zone, orgCtx),
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
        overrides: SignalOverrides = SignalOverrides(),
    ): MerchantBacktestResponse {
        val merchant = requireMerchant(merchantId)
        val signals = effectiveSignals(merchantId, overrides)
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
        val orgCtx = loadOrgContext(merchantId, zone, fromDate.minusDays(LOOKBACK_DAYS), toDate, signals)
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
                val p = compositeCell(actuals, orgCtx, date, window)
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

    /**
     * (날짜 × 끼니) 셀 하나의 **근거 상세** — 화면의 숫자를 클릭했을 때 "왜 이 숫자인가"에 답한다.
     *
     * ★셀 값은 목록과 **같은 계산**(loadOrgContext + compositeCell)이다. 상세가 자체 계산을 가지면
     * 목록 13인분·상세 15인분처럼 두 화면이 갈리고, 그 순간 상세는 근거가 아니라 두 번째 의견이 된다.
     *
     * 메뉴 분해는 **사업장 조직 몫에만** 적용한다(식단은 그 조직의 것이다). 비율은 이 매장의
     * menu_ref 실적에서 배우고, 근거가 없으면 null — 균등 분배를 지어내지 않는다.
     */
    @Transactional(readOnly = true)
    fun cellDetail(
        merchantId: UUID,
        date: LocalDate,
        mealWindow: String,
        overrides: SignalOverrides = SignalOverrides(),
    ): MerchantCellDetail {
        val merchant = requireMerchant(merchantId)
        val zone = zoneOf(merchant)
        val window = resolveWindows(mealWindow).single()
        val signals = effectiveSignals(merchantId, overrides)

        val actuals =
            loadActuals(
                merchantId,
                zone,
                date.minusDays(LOOKBACK_DAYS),
                minOf(date, LocalDate.now(zone).minusDays(1)),
            )
        // 메뉴 분해가 상세의 존재 이유라, 목록의 menuAware 스위치와 무관하게 컨텍스트에 식단을 싣는다
        // (스위치는 "예측 계산에 쓰는가"를 정하고, 상세는 "무엇이 배식되는가"라는 사실을 보인다).
        val ctx =
            loadOrgContext(
                merchantId,
                zone,
                date.minusDays(LOOKBACK_DAYS),
                date,
                signals.copy(menuAware = true),
            )
        // 셀은 요청된 신호 그대로 계산한다 — 위 copy 는 컨텍스트에 식단을 싣기 위한 것일 뿐,
        // 계산 신호가 바뀌면 목록과 숫자가 갈린다.
        val cellCtx = if (signals.menuAware) ctx else ctx.copy(signals = signals)
        val cell = compositeCell(actuals, cellCtx, date, window)

        val orgDetails =
            cellCtx.orgIds.map { org ->
                val (slice, basis) = orgSliceWithBasis(cellCtx, org, date, window)
                MerchantOrgSliceDetail(
                    slice = slice,
                    basis = basis.map { MerchantBasisPoint(it.first, it.second) },
                    headcount = cellCtx.headcountByOrg[org],
                )
            }

        // ---- 메뉴별 분해 ----
        val site = merchantSite(merchantId)
        var learnFrom: LocalDate? = null
        var learnTo: LocalDate? = null
        val menus =
            if (site == null) {
                emptyList()
            } else {
                val slotMenus = menuService.forSlot(site.orgId, date, window, site.id)
                if (slotMenus.isEmpty()) {
                    emptyList()
                } else {
                    learnTo = LocalDate.now(zone)
                    learnFrom = learnTo.minusDays(MENU_MIX_LEARN_DAYS)
                    val rows =
                        eventRepository.aggregateMenuMixByMerchant(
                            merchantId,
                            learnFrom.atStartOfDay(zone).toInstant(),
                            learnTo.plusDays(1).atStartOfDay(zone).toInstant(),
                            zone.id,
                            date.dayOfWeek.value,
                            MAX_AGGREGATE_ROWS,
                        )
                    val quantityByMenu = HashMap<String, Long>()
                    rows.forEach { row ->
                        if (row[0] as String == window) {
                            quantityByMenu.merge(row[1] as String, (row[2] as Number).toLong(), Long::plus)
                        }
                    }
                    // ★분모는 **오늘 식단에 있는 메뉴들의 기록 합**이다. 두 겹의 이유:
                    //   ① 미기록을 넣으면 초기 기록률이 낮을 때 전 비율이 0 근처로 눌린다.
                    //   ② 과거에만 있던 메뉴(지난주 특식)를 넣으면 오늘 코너들 사이의 상대 비율 —
                    //      발주 질문의 실제 답 — 이 아니라 역사적 점유율이 나온다.
                    //   그래서 오늘 슬롯 메뉴들의 비율 합은 (표본이 있으면) 1 이다 — 화면이 설명할 수 있다.
                    val slotNames = slotMenus.map { it.name }.toSet()
                    val recorded = quantityByMenu.filterKeys { it in slotNames }.values.sum()
                    val siteOrgPredicted = orgDetails.firstOrNull { it.slice.orgId == site.orgId }?.slice?.predicted
                    slotMenus.map { menu ->
                        val quantity = quantityByMenu[menu.name] ?: 0L
                        val share = if (recorded > 0 && quantity > 0) quantity.toDouble() / recorded else null
                        MerchantMenuShare(
                            name = menu.name,
                            corner = menu.corner,
                            category = menu.categoryEnum().name,
                            plannedPortions = menu.plannedPortions,
                            share = share,
                            predicted =
                                if (share != null && siteOrgPredicted != null) {
                                    Math.round(siteOrgPredicted * share)
                                } else {
                                    null
                                },
                            sampleQuantity = quantity,
                        )
                    }
                }
            }

        return MerchantCellDetail(
            date = date,
            mealWindow = window,
            timezone = merchant.timezone,
            cell = cell,
            orgs = orgDetails,
            menus = menus,
            menuLearnFrom = learnFrom,
            menuLearnTo = learnTo,
        )
    }

    /** 유효 신호 = 저장 설정(없으면 기본값) 위에 요청 파라미터를 **한 번만** 덮는다. */
    private fun effectiveSignals(
        merchantId: UUID,
        overrides: SignalOverrides,
    ): ForecastSignals {
        val stored = settingsRepository.findById(merchantId).map { it.toSignals() }.orElse(ForecastSignals())
        return if (overrides.isEmpty()) stored else overrides.applyTo(stored)
    }

    @Transactional(readOnly = true)
    fun readSettings(merchantId: UUID): ForecastSignals {
        requireMerchant(merchantId)
        return settingsRepository.findById(merchantId).map { it.toSignals() }.orElse(ForecastSignals())
    }

    /** 설정 저장(upsert). 호출부(컨트롤러)가 감사 이벤트를 남긴다 — 저장형 전환의 전제 조건이다. */
    @Transactional
    fun saveSettings(
        merchantId: UUID,
        signals: ForecastSignals,
    ): ForecastSignals {
        requireMerchant(merchantId)
        val row =
            settingsRepository.findById(merchantId).orElseGet {
                com.taspa.server.domain.meal
                    .MerchantForecastSettings(merchantId = merchantId)
            }
        row.headcountAdjust = signals.headcountAdjust
        row.absenceAware = signals.absenceAware
        row.holidayAware = signals.holidayAware
        row.eventAware = signals.eventAware
        row.menuAware = signals.menuAware
        row.nowcast = signals.nowcast
        row.methodSelection = signals.methodSelection ?: false
        return settingsRepository.save(row).toSignals()
    }

    // ---- 조직 분해 예측 코어 ----

    /**
     * 조직별 신호 컨텍스트 — **이 매장을 이용한 조직**(구간 내 CONFIRMED 실적) 각각의 캘린더·부재.
     *
     * 이것이 이 서비스의 방향 전환이다. 이전 버전은 "어느 조직의 휴일인가가 결정 불가"라는 이유로
     * 조직 신호를 전부 배제했는데, 답은 배제가 아니라 **분해**였다: 실적을 조직별로 갈라 각 조각에
     * 그 조직의 신호를 적용하고 합산하면, A 조직 휴일이 B 조직 손님을 깎는 왜곡 없이 신호가 살아난다.
     * org_id 가 NOT NULL 이라 분해의 합 = 매장 총합이 항상 성립한다.
     */
    private data class OrgContext(
        val orgIds: List<UUID>,
        val names: Map<UUID, String>,
        /** org → (date, window) → 인분. */
        val actualsByOrg: Map<UUID, ActualsIndex>,
        /** org → 휴일·행사 인덱스(신호 OFF 면 EMPTY). */
        val holidaysByOrg: Map<UUID, HolidayIndex>,
        /** org → 날짜 → 부재 가중 합(신호 OFF 면 빈 맵). */
        val absencesByOrg: Map<UUID, Map<LocalDate, Double>>,
        /** org → 현재 재직 인원(ACTIVE ∧ EMPLOYED). 부재 비율의 분모. */
        val headcountByOrg: Map<UUID, Long>,
        /**
         * 매장 연결 사업장의 조직에 한한 (날짜, 끼니) → 메뉴 카테고리. 메뉴는 org 자원이라 **그 조직
         * 몫에만** 적용된다 — 다른 조직 손님의 몫에 남의 식단을 대면 그게 바로 교차 오염이다.
         * 한 끼니에 카테고리가 여럿이면(특식+분식 코너) 대표를 정할 수 없어 **넣지 않는다**(신호 불가).
         */
        val menuOrgId: UUID?,
        val menuCategoryBySlot: Map<Pair<LocalDate, String>, String>,
        /** (org, window) → 매장-로컬 오늘 이미 나간 인분(nowcast 하한). */
        val todayByOrgWindow: Map<Pair<UUID, String>, Long>,
        val today: LocalDate,
        val signals: ForecastSignals,
    )

    private fun loadOrgContext(
        merchantId: UUID,
        zone: ZoneId,
        fromDate: LocalDate,
        toDate: LocalDate,
        signals: ForecastSignals,
    ): OrgContext {
        val historyFrom = fromDate.atStartOfDay(zone).toInstant()
        val historyTo = toDate.plusDays(1).atStartOfDay(zone).toInstant()
        // 실적순 정렬을 위해 뒤에서 다시 정렬한다 — 화면·분해 모두 "많이 이용하는 조직"이 먼저 와야
        // 계산원과 사장이 목록의 앞만 보고도 판단할 수 있다(감사·테스트 잔여 조직이 앞을 차지하면 안 된다).
        val orgIds = eventRepository.findOrgIdsByMerchant(merchantId, historyFrom, historyTo)
        val today = LocalDate.now(zone)
        if (orgIds.isEmpty()) {
            return OrgContext(
                emptyList(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                null,
                emptyMap(),
                emptyMap(),
                today,
                signals,
            )
        }
        if (orgIds.size > MAX_ORGS) {
            // 조직 수만큼 캘린더·부재 질의가 늘어난다 — 상한 초과는 조용히 자르지 않고 거절한다
            // (잘린 조직의 몫이 예측에서 사라지는데 그 사실이 응답에 드러나지 않는다).
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "이용 조직이 상한(${MAX_ORGS}개)을 넘습니다 — 조회 창(from/to)을 줄여 다시 시도하세요",
            )
        }
        val names =
            organizationRepository.findAllById(orgIds).associate { (it.id!!) to it.name }
        // 조직별 실적 분해 — 단일 그룹쿼리.
        val rows =
            eventRepository.aggregateByMerchantOrgDateWindow(merchantId, historyFrom, historyTo, zone.id, MAX_AGGREGATE_ROWS)
        if (rows.size >= MAX_AGGREGATE_ROWS) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "실적 집계 규모가 상한(${MAX_AGGREGATE_ROWS}행)에 도달했습니다 — 조회 창(from/to)을 줄여 다시 시도하세요",
            )
        }
        val actualsByOrg = HashMap<UUID, ActualsIndex>()
        val volumeByOrg = HashMap<UUID, Long>()
        rows.forEach { row ->
            val org = row[2] as UUID
            val quantity = (row[3] as Number).toLong()
            actualsByOrg
                .getOrPut(org) { ActualsIndex() }
                .add(toLocalDate(row[0]!!), row[1] as String, quantity)
            volumeByOrg.merge(org, quantity, Long::plus)
        }
        val sortedOrgIds = orgIds.sortedByDescending { volumeByOrg[it] ?: 0L }
        // 캘린더는 그 조직 달력의 날짜 선언이다 — 매장 타임존이 아니라 UTC 벽시계(HolidayCalendar 불변식).
        val holidaysByOrg =
            if (signals.holidayAware) {
                orgIds.associateWith { holidayCalendar.load(it, fromDate, toDate, signals.eventAware) }
            } else {
                emptyMap()
            }
        val absencesByOrg =
            if (signals.absenceAware) {
                orgIds.associateWith { org ->
                    absenceRepository.sumWeightByDate(org, fromDate, toDate).associate { row ->
                        (row[0] as Date).toLocalDate() to (row[1] as Number).toDouble()
                    }
                }
            } else {
                emptyMap()
            }
        val headcountByOrg =
            if (signals.absenceAware) {
                orgIds.associateWith { org ->
                    membershipRepository.countByOrgIdAndStatusAndEmploymentStatus(org, "ACTIVE", "EMPLOYED")
                }
            } else {
                emptyMap()
            }
        // 메뉴 신호 — 매장 연결 사업장의 조직 식단만. 사업장 우선(forSlot 과 같은 규칙)을 여기서 접는다.
        var menuOrgId: UUID? = null
        val menuCategoryBySlot = HashMap<Pair<LocalDate, String>, String>()
        if (signals.menuAware) {
            val site = merchantSite(merchantId)
            if (site != null) {
                menuOrgId = site.orgId
                val menus =
                    menuRepository.findByOrgIdAndMenuDateBetweenOrderByMenuDateAscMealWindowAscSortOrderAscNameAsc(
                        site.orgId,
                        fromDate,
                        toDate,
                    )
                menus
                    .groupBy { it.menuDate to it.mealWindow }
                    .forEach { (slot, rows) ->
                        val preferred = rows.filter { it.siteId == site.id }.ifEmpty { rows.filter { r -> r.siteId == null } }
                        val categories = preferred.map { it.categoryEnum().name }.distinct()
                        // 카테고리가 하나일 때만 그 끼니의 성격으로 본다 — 둘 이상이면 대표를 지어내지 않는다.
                        if (categories.size == 1) menuCategoryBySlot[slot] = categories.single()
                    }
            }
        }
        // nowcast — 오늘 이미 나간 인분(부분값). 예측 하한으로만 쓰고 예측으로 위장하지 않는다.
        val todayByOrgWindow = HashMap<Pair<UUID, String>, Long>()
        if (signals.nowcast) {
            val rowsToday =
                eventRepository.aggregateByMerchantOrgDateWindow(
                    merchantId,
                    today.atStartOfDay(zone).toInstant(),
                    today.plusDays(1).atStartOfDay(zone).toInstant(),
                    zone.id,
                    MAX_AGGREGATE_ROWS,
                )
            rowsToday.forEach { row ->
                todayByOrgWindow.merge((row[2] as UUID) to (row[1] as String), (row[3] as Number).toLong(), Long::plus)
            }
        }
        return OrgContext(
            sortedOrgIds,
            names,
            actualsByOrg,
            holidaysByOrg,
            absencesByOrg,
            headcountByOrg,
            menuOrgId,
            menuCategoryBySlot,
            todayByOrgWindow,
            today,
            signals,
        )
    }

    private fun merchantSite(merchantId: UUID): com.taspa.server.domain.org.Site? =
        merchantRepository
            .findById(merchantId)
            .orElse(null)
            ?.siteId
            ?.let { siteRepository.findById(it).orElse(null) }

    /**
     * 셀 1개 = 조직별 예측의 합. 조직마다:
     *  1. basis 는 **같은 성격의 날**만 쓴다(그 조직 캘린더의 휴일/행사 — DayClass 대칭, 조직 예측과 동일).
     *  2. 부재 비율 보정: 예측 = basis × (재직−부재(타깃)) ÷ (재직−부재(basis일)). 비율은 [MIN_RATIO,
     *     MAX_RATIO] 밖이면 생략한다(조직 예측의 재실 보정과 같은 가드).
     *     ★분모의 재직 인원은 **현재값**이다 — 조직 예측처럼 SCD 이력 복원을 하지 않는다. basis 가 최대
     *     4주 전이라 채용·퇴사 변동은 작고, 이 그레인의 지배 신호는 부재(연차)다. 그 단순화 때문에
     *     조직 예측과 이 분해의 조직 몫이 약간 다를 수 있다(정직한 트레이드오프 — 여기 기록).
     *  3. 조직이 하나도 산출 못 하면 셀은 NO_DATA. 일부만 산출하면 합은 **하한**이고 partial=true.
     */
    private fun compositeCell(
        totals: ActualsIndex,
        ctx: OrgContext,
        date: LocalDate,
        window: String,
    ): MerchantForecastCell {
        // 이용 조직이 하나도 식별되지 않으면(신규 매장 등) 기존 총합 체인으로 폴백한다 — 분해가
        // 불가능하다는 이유로 예측 자체를 잃지 않는다.
        if (ctx.orgIds.isEmpty()) {
            val p = predictCell(totals, date, window)
            return MerchantForecastCell(date, window, p.predicted, p.method, p.basis)
        }
        val slices = ctx.orgIds.map { org -> orgSlice(ctx, org, date, window) }
        val known = slices.filter { it.predicted != null }
        val predicted = if (known.isEmpty()) null else known.sumOf { it.predicted!! }
        val method =
            when {
                known.isEmpty() -> ForecastMethod.NO_DATA
                known.size == 1 && slices.size == 1 -> known.single().method
                known.map { it.method }.distinct().size == 1 && known.size == slices.size -> known.first().method
                else -> ForecastMethod.COMPOSITE
            }
        val lastWeekTotal = totals.at(date.minusDays(7), window)
        // nowcast — 오늘 셀은 **이미 나간 인분**이 예측의 하한이다(그보다 작은 예측은 이미 틀린 것이
        // 확정이다). 예측이 없는 셀(NO_DATA)에는 숫자를 만들지 않고 soFar 로만 사실을 노출한다.
        val soFar =
            if (ctx.signals.nowcast && date == ctx.today) {
                ctx.orgIds.sumOf { ctx.todayByOrgWindow[it to window] ?: 0L }.takeIf { it > 0L }
            } else {
                null
            }
        val floored = if (predicted != null && soFar != null && soFar > predicted) soFar else predicted
        return MerchantForecastCell(
            date = date,
            mealWindow = window,
            predicted = floored,
            method = method,
            basis = MerchantForecastBasis(lastWeekTotal, known.size),
            orgs = slices,
            partial = known.isNotEmpty() && known.size < slices.size,
            soFar = soFar,
        )
    }

    private fun orgSlice(
        ctx: OrgContext,
        org: UUID,
        date: LocalDate,
        window: String,
    ): MerchantOrgSlice = orgSliceWithBasis(ctx, org, date, window).first

    /**
     * 조각 + **채택된 basis**. 상세 화면(cellDetail)이 "어느 날짜의 어떤 실적을 근거로 썼는가"를
     * 말하기 위해 분리했다 — 같은 함수가 목록과 상세를 만들므로 두 화면의 숫자가 갈릴 수 없다.
     */
    private fun orgSliceWithBasis(
        ctx: OrgContext,
        org: UUID,
        date: LocalDate,
        window: String,
    ): Pair<MerchantOrgSlice, List<Pair<LocalDate, Long>>> {
        val actuals = ctx.actualsByOrg[org] ?: ActualsIndex()
        val holidays = ctx.holidaysByOrg[org] ?: HolidayIndex.EMPTY
        val absences = ctx.absencesByOrg[org] ?: emptyMap()
        val headcount = ctx.headcountByOrg[org] ?: 0L
        val targetClass = holidays.classOf(date)

        // basis 후보: D-7 부터 D-28 까지 같은 요일 중 **타깃과 같은 성격의 날**만.
        val samples = ArrayList<Pair<LocalDate, Long>>()
        for (week in 1..LOOKBACK_WEEKS) {
            val basisDate = date.minusDays(7L * week)
            if (holidays.classOf(basisDate) != targetClass) continue
            val value = actuals.at(basisDate, window) ?: continue
            samples.add(basisDate to value)
        }
        // 메뉴 대칭(선호) — 휴일과 달리 **배제가 아니다**: 같은 카테고리 표본이 있으면 그쪽만, 없으면
        // 전체를 쓴다(메뉴 효과는 휴일만큼 강하지 않아 표본을 다 버리면 잃는 것이 더 크다).
        // 배율(특식 +20% 같은 값)을 지어내지 않는다 — 같은 카테고리의 실측을 그대로 쓰는 것이 전부다.
        if (ctx.signals.menuAware && org == ctx.menuOrgId) {
            val targetCategory = ctx.menuCategoryBySlot[date to window]
            if (targetCategory != null) {
                val matched = samples.filter { ctx.menuCategoryBySlot[it.first to window] == targetCategory }
                if (matched.isNotEmpty() && matched.size < samples.size) {
                    samples.clear()
                    samples.addAll(matched)
                }
            }
        }
        val absentWeight = absences[date] ?: 0.0
        val (predicted, method) =
            when {
                samples.isEmpty() -> null to ForecastMethod.NO_DATA
                samples.first().first == date.minusDays(7) -> {
                    val (basisDate, value) = samples.first()
                    val adjusted = absenceAdjusted(value, headcount, absences, basisDate, date)
                    adjusted ?: value to ForecastMethod.SEASONAL_NAIVE
                }
                else -> samples.map { it.second }.average().roundToLong() to ForecastMethod.FOUR_WEEK_AVG
            }
        return MerchantOrgSlice(
            orgId = org,
            orgName = ctx.names[org] ?: "(알 수 없음)",
            predicted = predicted,
            method = method,
            holiday = holidays.isHoliday(date),
            holidayName = holidays.nameOf(date),
            event = holidays.isEvent(date),
            eventName = holidays.eventNameOf(date),
            absentWeight = absentWeight,
        ) to samples
    }

    /** 부재 비율 보정값 — 적용 불가(모수 없음·비율 범위 밖·신호 OFF)면 null. */
    private fun absenceAdjusted(
        basisValue: Long,
        headcount: Long,
        absences: Map<LocalDate, Double>,
        basisDate: LocalDate,
        target: LocalDate,
    ): Pair<Long, ForecastMethod>? {
        if (headcount <= 0L) return null
        val basisPresent = headcount - (absences[basisDate] ?: 0.0)
        val targetPresent = headcount - (absences[target] ?: 0.0)
        if (basisPresent <= 0.0 || targetPresent <= 0.0) return null
        val ratio = targetPresent / basisPresent
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return null
        if (ratio == 1.0) return null // 보정이 없으면 SEASONAL_NAIVE 로 정직하게 남긴다
        return Math.round(basisValue * ratio) to ForecastMethod.SEASONAL_NAIVE_ADJUSTED
    }

    /** "이용 조직" 목록 — 최근 실적과 **다가오는** 캘린더·부재 신호 요약(가맹 콘솔 섹션). */
    private fun orgInfos(
        merchantId: UUID,
        zone: ZoneId,
        ctx: OrgContext,
    ): List<MerchantOrgInfo> {
        if (ctx.orgIds.isEmpty()) return emptyList()
        val today = LocalDate.now(zone)
        val recentFrom = today.minusDays(28).atStartOfDay(zone).toInstant()
        val recentTo = today.plusDays(1).atStartOfDay(zone).toInstant()
        val recentRows =
            eventRepository.aggregateByMerchantOrgDateWindow(merchantId, recentFrom, recentTo, zone.id, MAX_AGGREGATE_ROWS)
        val recentByOrg = HashMap<UUID, Long>()
        recentRows.forEach { row -> recentByOrg.merge(row[2] as UUID, (row[3] as Number).toLong(), Long::plus) }
        val horizon = today.plusDays(14)
        return ctx.orgIds.map { org ->
            // 요약은 항상 신호를 켜고 계산한다 — 이 목록의 목적이 "신호가 있다"는 사실의 노출이라서다.
            val holidays = holidayCalendar.load(org, today, horizon, true)
            var holidayCount = 0
            var eventCount = 0
            var d = today
            while (!d.isAfter(horizon)) {
                if (holidays.isHoliday(d)) holidayCount++
                if (holidays.isEvent(d)) eventCount++
                d = d.plusDays(1)
            }
            val absent =
                absenceRepository.sumWeightByDate(org, today, horizon).sumOf { (it[1] as Number).toDouble() }
            MerchantOrgInfo(
                orgId = org,
                name = ctx.names[org] ?: "(알 수 없음)",
                recentPortions = recentByOrg[org] ?: 0L,
                upcomingHolidays = holidayCount,
                upcomingEvents = eventCount,
                upcomingAbsentWeight = absent,
            )
        }
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

        /** 이용 조직 수 상한 — 조직마다 캘린더·부재 질의가 붙는다(폭주 방어선). */
        const val MAX_ORGS = 50

        /** 메뉴 비율 학습 창(일) — 예측 프로파일 창(8주)과 같은 길이(별도 개념을 만들지 않는다). */
        const val MENU_MIX_LEARN_DAYS = 56L

        /** 부재 비율 보정의 신뢰 범위 — 조직 예측의 재실 보정 가드와 같은 값. */
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.0
    }
}
