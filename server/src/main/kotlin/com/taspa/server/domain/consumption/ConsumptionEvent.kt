package com.taspa.server.domain.consumption

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 소비 이벤트(Phase 0ب-C) — append-only 정답 데이터 로그. 생산자(결제·POS·수동·임포트)가 (source, external_id)
 * 멱등키로 적재하고, 예측 소비자는 집계로만 읽는다. org_id 로 테넌시가 강제되고, user_sub 는 선택 라벨이며
 * 조회 API 에는 개별 노출되지 않는다(집계만).
 *
 * 저장 규약: source 는 소문자(payment|pos|manual|import), mealWindow·status 는 대문자 enum 이름으로 정규화한다 —
 * 대소문자 차이로 멱등키·집계 필터가 어긋나지 않게 한다.
 */
@Entity
@Table(name = "consumption_events")
class ConsumptionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "source", nullable = false, length = 24)
    var source: String,
    @Column(name = "external_id", nullable = false, length = 128)
    val externalId: String,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "user_sub")
    var userSub: UUID? = null,
    @Column(name = "merchant_id")
    var merchantId: UUID? = null,
    /** 사업장 귀속(선택, V25) — redemption 경로가 merchant.site_id 를 전달한다(사이트 롤업 예측용). */
    @Column(name = "site_id")
    var siteId: UUID? = null,
    @Column(name = "meal_window", nullable = false, length = 16)
    var mealWindow: String,
    @Column(name = "menu_ref", length = 128)
    var menuRef: String? = null,
    @Column(name = "quantity", nullable = false)
    var quantity: Int = 1,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = ConsumptionEventStatus.CONFIRMED.name,
    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
