package com.taspa.server.consumption.dto

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 소비 이벤트 적재 요청(단건). org_id 는 **경로**(URL)에서만 취하며 본문에 두지 않는다 —
 * 본문으로 타 org 를 지정하는 스푸핑을 원천 차단한다(테넌시 격리). (source, externalId) 는 생산자 멱등키다.
 */
data class ConsumptionEventWriteRequest(
    val source: String = "",
    val externalId: String = "",
    val userSub: UUID? = null,
    /**
     * 가맹 귀속. **공개 적재 API 로는 지정할 수 없다**(컨트롤러가 400 으로 거부) — 가맹 그레인 집계는
     * org 를 넘어 merchant_id 로 합산하므로, 본문 값을 믿으면 타 조직 생산자가 남의 매장 예측을 오염시킨다.
     * 이 필드를 세우는 유일한 경로는 승인이 실제로 일어난 redeem/void 트랜잭션이다.
     */
    val merchantId: UUID? = null,
    /**
     * 사업장 귀속(선택, V25). 기존 생산자는 미전송(기본 null) — full-replace 규약에 따라 생략 재전송은 비운다.
     * 지정하면 **경로 org 소속 사업장이어야 한다**(서비스가 검증) — 타 조직 site UUID 오염 차단.
     */
    val siteId: UUID? = null,
    val mealWindow: String = "",
    val menuRef: String? = null,
    val quantity: Int = 1,
    val status: String? = null,
    val occurredAt: Instant? = null,
)

/** 적재 요청 봉투 — 단건/배치를 하나의 스키마로 받는다. events 는 1건 이상. */
data class ConsumptionWriteBatch(
    val events: List<ConsumptionEventWriteRequest> = emptyList(),
)

/** 적재 결과 요약 — 개별 이벤트/식별자를 노출하지 않는다(집계 카운트만). */
data class ConsumptionIngestResult(
    val received: Int,
    val inserted: Int,
    val updated: Int,
)

/**
 * 집계 1행 — date × meal_window [× menu]. count = CONFIRMED 이벤트 건수, quantity = 수량 합.
 * ★개별 이벤트·user_sub 는 절대 포함하지 않는다(집계만 노출).
 */
data class ConsumptionAggregateRow(
    val date: LocalDate,
    val mealWindow: String,
    val menuRef: String? = null,
    val count: Long,
    val quantity: Long,
)

/** 집계 응답. rows 는 groupBy 축으로 접힌 카운트만 담는다(원시 조회 API 는 존재하지 않는다). */
data class ConsumptionAggregateResponse(
    val orgId: UUID,
    val from: Instant,
    val to: Instant,
    val groupBy: List<String>,
    val rows: List<ConsumptionAggregateRow>,
)
