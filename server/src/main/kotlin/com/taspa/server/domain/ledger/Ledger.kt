package com.taspa.server.domain.ledger

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

/** 원장 사건의 종류. 상태가 아니라 **일어난 일**이다 — 원장은 상태를 갖지 않는다. */
enum class LedgerEntryType {
    REDEEM,
    VOID,
    REFUND,
}

/**
 * 계정. 플랫폼은 통과 지점이라 두 개면 충분하다.
 *
 * 직원 개인부담은 계산대에서 직접 오가므로 **우리 돈이 아니고**, 그래서 계정이 없다. 굳이 넣으면
 * 대차가 안 맞거나 맞추려고 가공 계정을 만들게 되는데 둘 다 원장을 거짓말하게 만든다.
 */
enum class LedgerAccount {
    /** 조직이 우리에게 낼 돈(자산). 차변 = 양수. */
    ORG_RECEIVABLE,

    /** 우리가 가맹에 줄 돈(부채). 대변 = 음수. */
    MERCHANT_PAYABLE,
}

@Entity
@Table(name = "ledger_entries")
class LedgerEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "entry_type", nullable = false, length = 24)
    val entryType: String,
    @Column(name = "transaction_id")
    val transactionId: UUID? = null,
    @Column(name = "refund_id")
    val refundId: UUID? = null,
    @Column(name = "merchant_id")
    val merchantId: UUID? = null,
    @Column(name = "user_id")
    val userId: UUID? = null,
    /** 분개 대상이 아닌 메모 — 대사에서 "장부와 왜 다른가"를 설명한다. */
    @Column(name = "self_paid_minor", nullable = false)
    val selfPaidMinor: Long = 0,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

/**
 * 분개 한 줄. **부호 있는 금액**(차변 +, 대변 −)이라 한 사건의 합은 항상 0 이다.
 *
 * 취소·환불은 기존 줄을 고치지 않고 **반대 부호의 새 줄**을 덧붙인다. 그래서 어느 시점으로 잘라도
 * 그때의 잔액이 나온다 — 거래 테이블이 환불로 소급 변경되는 것과 정반대 성질이고, 그게 원장의 값어치다.
 */
@Entity
@Table(name = "ledger_postings")
class LedgerPosting(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "entry_id", nullable = false)
    val entryId: UUID,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "account", nullable = false, length = 32)
    val account: String,
    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,
    @Column(name = "merchant_id")
    val merchantId: UUID? = null,
    @Column(name = "user_id")
    val userId: UUID? = null,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)

interface LedgerEntryRepository : JpaRepository<LedgerEntry, UUID> {
    fun findByOrgIdAndOccurredAtBetweenOrderByOccurredAtAsc(
        orgId: UUID,
        from: Instant,
        to: Instant,
    ): List<LedgerEntry>

    /**
     * 이 창에 **분개가 있는 org** 만 — 플랫폼 전역 대사가 훑을 대상을 좁힌다.
     *
     * org 마다 달 경계가 다르므로(타임존) 한 번의 group-by 로 전 조직을 대사할 수 없다. 그래서
     * "활동이 있었을 수 있는" org 를 UTC 로 넉넉히 추린 뒤 org 별로 정확히 계산한다 —
     * 전 조직 순회가 아니라 **활동 있는 조직**으로 한정되므로 비용이 실제 사용량에 비례한다.
     */
    @Query(
        """
        SELECT DISTINCT e.orgId FROM LedgerEntry e
        WHERE e.occurredAt >= :from AND e.occurredAt < :to
        """,
    )
    fun orgIdsWithActivity(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<UUID>

    /** 멱등 확인 — 같은 사건을 두 번 분개하지 않는다(DB UNIQUE 와 이중 방어). */
    fun findByEntryTypeAndTransactionIdAndRefundId(
        entryType: String,
        transactionId: UUID?,
        refundId: UUID?,
    ): LedgerEntry?
}

interface LedgerPostingRepository : JpaRepository<LedgerPosting, UUID> {
    /**
     * 계정 잔액([from, to) org 로컬 창). 부호 있는 합이라 취소·환불이 자동으로 상계된다.
     * 행이 없으면 0 — 원장이 비었다는 사실과 잔액이 0 이라는 사실은 대사에서 같은 의미다.
     */
    @Query(
        """
        SELECT COALESCE(SUM(p.amountMinor), 0) FROM LedgerPosting p
        WHERE p.orgId = :orgId AND p.account = :account
          AND p.occurredAt >= :from AND p.occurredAt < :to
        """,
    )
    fun balance(
        @Param("orgId") orgId: UUID,
        @Param("account") account: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /**
     * **대차평형 위반 사건**을 찾는다 — 한 사건의 분개 합이 0 이 아니면 원장 자체가 깨진 것이다.
     * 애플리케이션이 항상 짝으로 쓰므로 정상이면 0건이고, 0건이 아니면 그건 버그의 직접 증거다.
     */
    @Query(
        """
        SELECT p.entryId FROM LedgerPosting p
        WHERE p.orgId = :orgId AND p.occurredAt >= :from AND p.occurredAt < :to
        GROUP BY p.entryId
        HAVING SUM(p.amountMinor) <> 0
        """,
    )
    fun unbalancedEntryIds(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<UUID>
}
