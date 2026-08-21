package com.taspa.server.domain.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 조직 월 청구서(정산 집계) — meal_transactions(APPROVED)의 조직부담(amount−selfPaid) 월 합계.
 * (org_id, period) 유니크. DRAFT 는 재생성(라인 full-replace) 가능, FINALIZED 이후는 불변이다.
 * 실 자금이동·수수료·부가세는 범위 밖(설계 §4.3 — 확정까지만, 수납은 위탁 연동 후속).
 */
@Entity
@Table(name = "invoices")
class Invoice(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    /** org 타임존 달력 월 'YYYY-MM' — 월 경계는 생성 시점에 org.timezone 으로 앵커링해 집계한다. */
    @Column(name = "period", nullable = false, length = 7)
    val period: String,
    /**
     * 집계 창 [periodStart, periodEnd) 스냅샷 — 생성 시점의 org 타임존으로 계산된 실제 경계.
     * org.timezone 이 이후 변경돼도 finalize 재검증과 인접 월 정합(갭/중복 방지)의 기준이 된다.
     */
    @Column(name = "period_start", nullable = false)
    var periodStart: Instant,
    @Column(name = "period_end", nullable = false)
    var periodEnd: Instant,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = InvoiceStatus.DRAFT.name,
    /** 조직부담 합(개인부담 self_paid 제외). */
    @Column(name = "subtotal_minor", nullable = false)
    var subtotalMinor: Long = 0,
    @Column(name = "txn_count", nullable = false)
    var txnCount: Int = 0,
    @Column(name = "generated_at", nullable = false)
    var generatedAt: Instant = Instant.now(),
    @Column(name = "finalized_at")
    var finalizedAt: Instant? = null,
) {
    fun statusEnum(): InvoiceStatus = InvoiceStatus.valueOf(status)
}

enum class InvoiceStatus { DRAFT, FINALIZED }
