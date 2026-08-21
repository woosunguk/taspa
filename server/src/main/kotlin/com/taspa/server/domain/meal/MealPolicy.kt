package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * 조직 식대 정책(한도·끼니창). org 당 1행(PK=org_id) — 행이 없으면 코드 기본값으로 평가한다
 * (MealRedeemService.defaultPolicy). 끼니창 시각은 org 타임존의 로컬 시각으로 판정한다.
 * 금액 단위는 minor(KRW 원). daily_meal_count 는 하루 승인 거래수 상한이다.
 */
@Entity
@Table(name = "meal_policies")
class MealPolicy(
    @Id
    @Column(name = "org_id")
    val orgId: UUID,
    @Column(name = "per_meal_limit_minor", nullable = false)
    override var perMealLimitMinor: Long = 12000,
    @Column(name = "daily_meal_count", nullable = false)
    override var dailyMealCount: Int = 1,
    @Column(name = "monthly_cap_minor", nullable = false)
    override var monthlyCapMinor: Long = 200000,
    @Column(name = "breakfast_start", nullable = false)
    override var breakfastStart: LocalTime = LocalTime.of(6, 0),
    @Column(name = "breakfast_end", nullable = false)
    override var breakfastEnd: LocalTime = LocalTime.of(10, 30),
    @Column(name = "lunch_start", nullable = false)
    override var lunchStart: LocalTime = LocalTime.of(10, 30),
    @Column(name = "lunch_end", nullable = false)
    override var lunchEnd: LocalTime = LocalTime.of(15, 0),
    @Column(name = "dinner_start", nullable = false)
    override var dinnerStart: LocalTime = LocalTime.of(15, 0),
    @Column(name = "dinner_end", nullable = false)
    override var dinnerEnd: LocalTime = LocalTime.of(22, 0),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) : MealPolicyValues {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}
