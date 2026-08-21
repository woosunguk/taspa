package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * 부서·사업장 단위 정책 재정의. 재정의하지 않는 필드는 **null = 상위값 물려받음**이다.
 *
 * 전체 정책을 복제하지 않는 이유는 유지보수가 아니라 정확성이다: 조직이 1식 한도를 올렸을 때
 * "개발팀은 점심시간만 다르다"는 재정의가 그 인상을 자동으로 물려받아야 한다. 복제였다면 부서마다
 * 손으로 따라 고쳐야 하고, 빠뜨린 부서는 옛 한도로 남는데 아무도 눈치채지 못한다.
 *
 * 정확히 한 축(부서 또는 사업장)에만 붙는다 — DB CHECK 제약이 함께 강제한다.
 */
@Entity
@Table(name = "meal_policy_overrides")
class MealPolicyOverride(
    @Id
    @GeneratedValue
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "department_id")
    val departmentId: UUID? = null,
    @Column(name = "site_id")
    val siteId: UUID? = null,
    @Column(name = "per_meal_limit_minor")
    var perMealLimitMinor: Long? = null,
    @Column(name = "daily_meal_count")
    var dailyMealCount: Int? = null,
    @Column(name = "monthly_cap_minor")
    var monthlyCapMinor: Long? = null,
    @Column(name = "breakfast_start") var breakfastStart: LocalTime? = null,
    @Column(name = "breakfast_end") var breakfastEnd: LocalTime? = null,
    @Column(name = "lunch_start") var lunchStart: LocalTime? = null,
    @Column(name = "lunch_end") var lunchEnd: LocalTime? = null,
    @Column(name = "dinner_start") var dinnerStart: LocalTime? = null,
    @Column(name = "dinner_end") var dinnerEnd: LocalTime? = null,
    /** null = 상시. 둘 다 null 이어야 노드당 1행 부분 유니크에 걸린다. */
    @Column(name = "effective_from") var effectiveFrom: LocalDate? = null,
    @Column(name = "effective_to") var effectiveTo: LocalDate? = null,
    @Column(name = "reason", length = 200)
    var reason: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "updated_by")
    var updatedBy: UUID? = null,
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    /** 이 재정의가 [date] 에 유효한지. 경계는 양끝 포함(사람이 "12월 1일부터 31일까지"로 말하는 대로). */
    fun appliesOn(date: LocalDate): Boolean =
        (effectiveFrom == null || !date.isBefore(effectiveFrom)) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

    /** 기간 한정 재정의인지 — 같은 노드에서 상시보다 우선한다. */
    val temporary: Boolean
        get() = effectiveFrom != null || effectiveTo != null
}
