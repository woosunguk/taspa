package com.taspa.server.meal.dto

import com.taspa.server.domain.meal.MenuCategory
import java.time.LocalDate
import java.util.UUID

data class MealMenuView(
    val id: UUID,
    val siteId: UUID?,
    val menuDate: LocalDate,
    val mealWindow: String,
    val name: String,
    val category: MenuCategory,
    val corner: String?,
    val plannedPortions: Int?,
    val sortOrder: Int,
)

/**
 * 식단 등록·수정 요청. **가중치·비율 같은 파생값은 받지 않는다** — 메뉴별 수요 분해는 실적에서 배우는
 * 것이고, 요청으로 받으면 조직이 넣은 추측이 예측처럼 보인다.
 */
data class MealMenuUpsertRequest(
    val menuDate: LocalDate,
    val mealWindow: String,
    val name: String,
    val category: MenuCategory = MenuCategory.REGULAR,
    /** null = 조직 전체 공통 식단. */
    val siteId: UUID? = null,
    val corner: String? = null,
    val plannedPortions: Int? = null,
    val sortOrder: Int = 0,
)

/** 일괄 등록 결과 한 줄. 부분 성공 모델(CSV 초대와 같은 사상) — 한 줄이 틀려도 나머지는 들어간다. */
data class MealMenuBulkRow(
    val index: Int,
    val status: String,
    val menuId: UUID?,
    val message: String?,
)

data class MealMenuBulkResponse(
    val created: Int,
    val updated: Int,
    val rejected: Int,
    val rows: List<MealMenuBulkRow>,
)

/**
 * 예측 셀에 함께 실리는 그 날의 식단.
 *
 * **예측값을 메뉴 수로 나누지 않는다** — 메뉴별 선택 비율은 실적에서 배워야 하고, 근거가 없을 때
 * 균등 분배를 내려보내면 화면은 그것을 예측으로 표시한다(A코너에 절반을 준비하는 근거가 된다).
 * [share] 는 과거 같은 카테고리 메뉴의 실측 선택 비율이며 **근거가 없으면 null** 이다.
 */
data class MenuOnDay(
    val menuId: UUID,
    val name: String,
    val category: MenuCategory,
    val corner: String?,
    val plannedPortions: Int?,
    val share: Double?,
    val predicted: Long?,
)
