package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 메뉴 성격. **예측 신호의 입력**이라 자유 텍스트가 아니라 열거다 — 텍스트로 두면 "특식"·"스페셜"·
 * "special" 이 각각 다른 값이 되어 과거 실적과 매칭되지 않고, 그 실패가 응답에 드러나지 않는다.
 */
enum class MenuCategory {
    /** 평시 메뉴. */
    REGULAR,

    /** 특식(스테이크·회식 메뉴 등) — 참여율이 오른다. */
    SPECIAL,

    /** 면류·분식 코너. */
    NOODLE,

    /** 채식·샐러드 — 소수 수요다. */
    VEGETARIAN,

    /** 간편식·도시락. */
    LIGHT,
    ;

    companion object {
        fun parse(raw: String?): MenuCategory? = entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
    }
}

/**
 * 식단 한 줄(V40). 한 끼니에 여러 행이 올 수 있다(A코너 한식 / B코너 분식).
 *
 * [siteId] 가 null 이면 조직 전체 공통 식단이다. 사업장별 식단이 있으면 그쪽이 우선한다 —
 * 판정은 [com.taspa.server.meal.MealMenuService] 한 곳에서만 한다(두 곳에 두면 화면과 승인이 갈린다).
 */
@Entity
@Table(name = "meal_menus")
class MealMenu(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "site_id")
    var siteId: UUID? = null,
    @Column(name = "menu_date", nullable = false)
    val menuDate: LocalDate,
    @Column(name = "meal_window", nullable = false, length = 16)
    val mealWindow: String,
    @Column(name = "name", nullable = false, length = 200)
    var name: String,
    @Column(name = "category", nullable = false, length = 32)
    var category: String = MenuCategory.REGULAR.name,
    @Column(name = "corner", length = 60)
    var corner: String? = null,
    @Column(name = "planned_portions")
    var plannedPortions: Int? = null,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    /** 손상된 값은 REGULAR 로 낙하한다 — 500 보다 "평시 메뉴로 본다"가 낫다(판정 근거가 아니라 분류다). */
    fun categoryEnum(): MenuCategory = MenuCategory.parse(category) ?: MenuCategory.REGULAR
}

interface MealMenuRepository : JpaRepository<MealMenu, UUID> {
    fun findByOrgIdAndMenuDateBetweenOrderByMenuDateAscMealWindowAscSortOrderAscNameAsc(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<MealMenu>

    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): MealMenu?

    /**
     * 승인 시 실적 귀속용 — 그 날 그 끼니의 메뉴. **사업장 일치 또는 조직 공통**만 본다.
     *
     * 정렬을 site 우선으로 두는 이유: 호출부가 "사업장 식단이 있으면 그것"을 첫 행으로 집으려면
     * 정렬이 결정적이어야 한다(무순서면 같은 요청이 날마다 다른 메뉴로 귀속된다).
     */
    @Query(
        """
        SELECT m FROM MealMenu m
        WHERE m.orgId = :orgId
          AND m.menuDate = :date
          AND m.mealWindow = :window
          AND (m.siteId = :siteId OR m.siteId IS NULL)
        ORDER BY CASE WHEN m.siteId IS NULL THEN 1 ELSE 0 END, m.sortOrder, m.name
        """,
    )
    fun findForSlot(
        @Param("orgId") orgId: UUID,
        @Param("date") date: LocalDate,
        @Param("window") window: String,
        @Param("siteId") siteId: UUID?,
    ): List<MealMenu>
}
