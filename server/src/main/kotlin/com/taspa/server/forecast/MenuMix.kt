package com.taspa.server.forecast

import com.taspa.server.domain.consumption.ConsumptionEventRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 메뉴별 **실측 선택 비율**. 한 끼니에 A코너·B코너가 있으면 총 예측을 그 비율로 나눠야 발주가 된다.
 *
 * ★**균등 분배를 기본값으로 두지 않는다.** 근거가 없을 때 "메뉴가 둘이니 반반"을 내려보내면 화면은
 * 그것을 예측으로 표시하고, 아무도 그 숫자가 추측이라는 것을 모른 채 A코너에 절반을 준비한다.
 * 비율은 실적에서만 나오고 없으면 **null** 이다(예측 자체의 NO_DATA 규약과 같은 사상).
 *
 * 학습 원천은 `consumption_events.menu_ref` 다 — 승인 시 채워진다(`MealRedeemService.resolveMenuRef`).
 * 그래서 식단을 처음 등록한 조직은 한동안 null 이고, 실적이 쌓이면 저절로 채워진다.
 *
 * ★비율의 분모는 **메뉴가 기록된 이벤트만**이다. 미기록(menu_ref=null) 이벤트를 분모에 넣으면
 * 기록률이 낮은 초기에 모든 메뉴의 비율이 0 에 가깝게 눌려, 합이 1 이 안 되는데 화면은 그것을
 * 설명하지 못한다.
 */
class MenuMix private constructor(
    /** 끼니 → (메뉴 이름 → 비율). 비어 있으면 근거 없음. */
    private val byWindow: Map<String, Map<String, Double>>,
) {
    fun shareOf(
        mealWindow: String,
        menuName: String,
    ): Double? = byWindow[mealWindow]?.get(menuName)

    companion object {
        val EMPTY = MenuMix(emptyMap())

        /**
         * 타깃 구간 **앞의** 실적으로 비율을 학습한다(미래정보 누수 방지 — 백테스트에서도 같은 규약).
         * 학습 창은 예측 프로파일 창과 같은 주 수를 쓴다(별도 설정을 만들면 둘이 갈린다).
         */
        fun load(
            orgId: UUID,
            zone: ZoneId,
            fromDate: LocalDate,
            toDate: LocalDate,
            repository: ConsumptionEventRepository,
            props: ForecastProperties,
        ): MenuMix {
            val learnFrom = fromDate.minusDays(7L * props.profileWeeks)
            val learnTo = minOf(fromDate, LocalDate.now(zone))
            if (!learnFrom.isBefore(learnTo)) return EMPTY
            val rows =
                repository.aggregateByDateWindowMenu(
                    orgId,
                    learnFrom.atStartOfDay(zone).toInstant(),
                    learnTo.atStartOfDay(zone).toInstant(),
                    zone.id,
                    ROW_LIMIT,
                )
            if (rows.isEmpty()) return EMPTY
            val counts = HashMap<String, HashMap<String, Long>>()
            rows.forEach { row ->
                val window = row[1] as String
                val menu = (row[2] as String).takeIf { it.isNotEmpty() } ?: return@forEach
                val quantity = (row[4] as Number).toLong()
                counts.getOrPut(window) { HashMap() }.merge(menu, quantity, Long::plus)
            }
            val shares =
                counts.mapValues { (_, byMenu) ->
                    val total = byMenu.values.sum()
                    if (total <= 0L) emptyMap() else byMenu.mapValues { (_, n) -> n.toDouble() / total }
                }
            return MenuMix(shares.filterValues { it.isNotEmpty() })
        }

        /** 그룹 수 상한 — 고카디널리티 menu_ref 로 인한 응답 폭증 방어(집계 API 와 같은 사상). */
        private const val ROW_LIMIT = 5_000
    }
}
