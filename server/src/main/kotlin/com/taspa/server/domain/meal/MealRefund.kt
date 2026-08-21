package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * 환불 한 건(append-only). 부분 환불은 여러 번 일어날 수 있어 거래 컬럼만으로는 "언제 얼마를 왜"를
 * 남길 수 없다.
 *
 * 조직/개인 분담을 나눠 기록하는 이유: 정산에서 회사가 돌려받은 금액과 직원이 돌려받은 금액은
 * 서로 다른 장부에 들어간다. 합계만 남기면 나중에 둘을 갈라낼 방법이 없다.
 */
@Entity
@Table(name = "meal_refunds")
class MealRefund(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,
    @Column(name = "merchant_id", nullable = false)
    val merchantId: UUID,
    /** POS 가 생성해 재시도에서 재사용하는 멱등키 — 이중 환불은 곧 손실이다. */
    @Column(name = "pos_refund_id", nullable = false, length = 64)
    val posRefundId: String,
    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,
    @Column(name = "org_refunded_minor", nullable = false)
    val orgRefundedMinor: Long,
    @Column(name = "self_refunded_minor", nullable = false)
    val selfRefundedMinor: Long,
    @Column(name = "reason", length = 200)
    val reason: String? = null,
    @Column(name = "refunded_at", nullable = false)
    val refundedAt: Instant = Instant.now(),
)

/**
 * 거래 한 건의 환불 누계 — 목록 화면이 거래마다 질의하지 않도록(N+1) 한 번에 모은다.
 *
 * `orgRefunded + selfRefunded = totalRefunded` 는 행 단위 CHECK 제약(`ck_meal_refund_split`)이
 * 보장하므로, 이 셋을 함께 화면에 실어도 서로 어긋날 수 없다.
 */
interface RefundSummaryRow {
    fun getTransactionId(): UUID

    fun getTotalRefunded(): Long

    fun getOrgRefunded(): Long

    fun getSelfRefunded(): Long

    fun getRefundCount(): Long

    fun getLastRefundedAt(): Instant
}

interface MealRefundRepository : JpaRepository<MealRefund, UUID> {
    /** POS 멱등 조회 — 같은 (가맹, 환불키) 재전송이면 기존 결과를 재반환한다(새 환불 금지). */
    fun findByMerchantIdAndPosRefundId(
        merchantId: UUID,
        posRefundId: String,
    ): MealRefund?

    fun findByTransactionIdOrderByRefundedAtAsc(transactionId: UUID): List<MealRefund>

    @Query(
        """
        SELECT r.transactionId AS transactionId,
               SUM(r.amountMinor) AS totalRefunded,
               SUM(r.orgRefundedMinor) AS orgRefunded,
               SUM(r.selfRefundedMinor) AS selfRefunded,
               COUNT(r) AS refundCount,
               MAX(r.refundedAt) AS lastRefundedAt
        FROM MealRefund r
        WHERE r.transactionId IN :transactionIds
        GROUP BY r.transactionId
        """,
    )
    fun summarizeByTransactionIds(
        @Param("transactionIds") transactionIds: Collection<UUID>,
    ): List<RefundSummaryRow>
}
