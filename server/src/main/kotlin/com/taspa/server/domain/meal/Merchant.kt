package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 가맹(식당·편의점·카페). 플랫폼 전역 리소스(org 스코프 아님) — redeem 인가는 M2M 토큰의 merchant_id
 * 클레임으로 결속된다. siteId 는 구내식당 운영 사업장(선택) — redemption 소비 이벤트의 site 귀속 근원.
 */
@Entity
@Table(name = "merchants")
class Merchant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "name", nullable = false, length = 200)
    var name: String,
    @Column(name = "category", nullable = false, length = 24)
    var category: String = MerchantCategory.RESTAURANT.name,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = MerchantStatus.ACTIVE.name,
    @Column(name = "site_id")
    var siteId: UUID? = null,
    /**
     * 매장 영업 지역 시간대 — 가맹 그레인 집계·예측의 하루 경계 앵커(V29).
     * 조직 타임존을 빌려 쓸 수 없다: 한 매장이 여러 조직 손님을 받으면 어느 조직 기준인지 정할 수 없고,
     * "오늘 몇 인분"은 매장이 영업하는 시간 기준이어야 현장 감각과 맞는다.
     */
    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String = "UTC",
    /**
     * 정액 단가(원). **null 이면 "설정 안 함"** 이고 POS 는 지금까지처럼 금액을 직접 입력받는다 —
     * 0 과 구분되어야 한다(0 원 자동 승인은 사고다). V38 의 CHECK 가 0 이하를 막는다.
     *
     * 가격은 **식당이 정한다**. 조직의 1식 한도(meal_policies)는 "회사가 얼마까지 지원하는가"이고
     * 가격이 아니다 — 그 값을 가격으로 쓰면 직원 부담이 항상 0 이 되고, 회사가 한도를 올리는 순간
     * 식당 매출이 따라 오른다.
     */
    @Column(name = "default_price_minor")
    var defaultPriceMinor: Long? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    /**
     * 저장 시 검증되므로 정상 경로에서는 항상 성공한다. 그럼에도 알 수 없는 값을 SUSPENDED 로 낙하시키는
     * 이유는, 손상된 행(수동 UPDATE·마이그레이션 사고) 하나가 500 으로 터지는 대신 **닫히는 쪽**으로
     * 실패해야 하기 때문이다 — 이 값은 redeem 가능 여부의 판정 근거다.
     */
    fun statusEnum(): MerchantStatus = MerchantStatus.entries.firstOrNull { it.name == status } ?: MerchantStatus.SUSPENDED
}
