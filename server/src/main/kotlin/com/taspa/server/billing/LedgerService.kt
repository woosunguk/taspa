package com.taspa.server.billing

import com.taspa.server.domain.ledger.LedgerAccount
import com.taspa.server.domain.ledger.LedgerEntry
import com.taspa.server.domain.ledger.LedgerEntryRepository
import com.taspa.server.domain.ledger.LedgerEntryType
import com.taspa.server.domain.ledger.LedgerPosting
import com.taspa.server.domain.ledger.LedgerPostingRepository
import com.taspa.server.domain.meal.MealTransaction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 이중부기 원장 기록.
 *
 * ★**결제와 같은 트랜잭션에서** 기록한다. 나중에 별도로 채우면 그 사이에 원장과 장부가 어긋나고,
 * 그 어긋남을 잡으려고 만든 대사가 자기 자신의 지연 때문에 매번 경보를 울린다(경보 피로 → 아무도
 * 안 봄 → 진짜 불일치가 묻힘). 원장이 실패하면 결제도 실패하는 편이 낫다 — 돈이 오갔는데 기록이
 * 없는 것보다 결제가 거절되는 쪽이 복구 가능하다.
 *
 * 부호 규약: **차변 +, 대변 −**. 한 사건의 합은 항상 0 이다. 취소·환불은 기존 줄을 고치지 않고
 * 반대 부호의 새 줄을 덧붙인다 — 그래서 어느 시점으로 잘라도 그때의 잔액이 재구성된다.
 */
@Service
class LedgerService(
    private val entryRepository: LedgerEntryRepository,
    private val postingRepository: LedgerPostingRepository,
) {
    /** 승인 — 조직 부담만큼 미수금이 늘고 가맹 미지급금이 는다. */
    @Transactional
    fun recordRedeem(transaction: MealTransaction) {
        record(
            orgId = transaction.orgId,
            type = LedgerEntryType.REDEEM,
            transactionId = transaction.id,
            refundId = null,
            merchantId = transaction.merchantId,
            userId = transaction.userId,
            selfPaidMinor = transaction.selfPaidMinor,
            amountMinor = transaction.orgPaidMinor(),
            occurredAt = transaction.approvedAt,
        )
    }

    /**
     * 취소 — 승인의 **반대 분개**. 금액은 취소 시점의 조직부담이다.
     *
     * 원 분개를 지우지 않는 이유가 원장의 존재 이유 그 자체다: 지우면 "그날 얼마가 오갔다가 취소됐나"를
     * 알 수 없다. 취소한 **시각**은 `created_at` 에 남는다.
     *
     * ★`occurred_at` 은 취소한 날이 아니라 **원 거래 승인 시각**이다. 6월 거래를 7월에 취소하면
     * 장부(`amount − self_paid`)는 6월 금액이 **소급해서** 줄어드는데(V36 이 amount 를 가변으로 만들었다),
     * 원장만 7월에 달면 6월·7월이 **둘 다** 어긋나 대사가 허위 경보를 낸다. 두 기록이 같은 기간
     * 의미론을 써야 대사가 의미를 갖는다 — 안 그러면 아무도 그 보고를 안 보게 되고(경보 피로),
     * 진짜 불일치가 묻힌다. (같은 달 안에서만 테스트하면 이 결함이 보이지 않는다.)
     */
    @Transactional
    fun recordVoid(
        transaction: MealTransaction,
        orgPaidMinor: Long,
    ) {
        record(
            orgId = transaction.orgId,
            type = LedgerEntryType.VOID,
            transactionId = transaction.id,
            refundId = null,
            merchantId = transaction.merchantId,
            userId = transaction.userId,
            selfPaidMinor = 0,
            amountMinor = -orgPaidMinor,
            occurredAt = transaction.approvedAt,
        )
    }

    /**
     * 환불 — 조직에게 돌아간 몫만 분개한다.
     *
     * 개인에게 돌아간 몫은 계산대에서 직접 오가므로 우리 장부를 지나지 않는다. 그걸 분개에 넣으면
     * 대차가 안 맞고, 맞추려고 가공 계정을 만들면 원장이 거짓말을 하게 된다.
     *
     * ★[recordVoid] 와 같은 이유로 `occurred_at` 은 **원 거래 승인 시각**이다. 환불한 시각은
     * `created_at` 에 남는다.
     */
    @Transactional
    fun recordRefund(
        transaction: MealTransaction,
        refundId: UUID,
        orgRefundedMinor: Long,
        selfRefundedMinor: Long,
    ) {
        record(
            orgId = transaction.orgId,
            type = LedgerEntryType.REFUND,
            transactionId = transaction.id,
            refundId = refundId,
            merchantId = transaction.merchantId,
            userId = transaction.userId,
            selfPaidMinor = -selfRefundedMinor,
            amountMinor = -orgRefundedMinor,
            occurredAt = transaction.approvedAt,
        )
    }

    private fun record(
        orgId: UUID,
        type: LedgerEntryType,
        transactionId: UUID?,
        refundId: UUID?,
        merchantId: UUID?,
        userId: UUID?,
        selfPaidMinor: Long,
        amountMinor: Long,
        occurredAt: Instant,
    ) {
        // 멱등 — 재시도·백필이 겹쳐도 원장이 부풀지 않는다. 원장이 부풀면 조직 청구가 부풀고,
        // 그건 조용히 두 배 청구된다(DB UNIQUE 와 이중 방어).
        entryRepository
            .findByEntryTypeAndTransactionIdAndRefundId(type.name, transactionId, refundId)
            ?.let { return }

        val entry =
            entryRepository.save(
                LedgerEntry(
                    orgId = orgId,
                    entryType = type.name,
                    transactionId = transactionId,
                    refundId = refundId,
                    merchantId = merchantId,
                    userId = userId,
                    selfPaidMinor = selfPaidMinor,
                    occurredAt = occurredAt,
                ),
            )
        // 0원 사건도 분개는 남긴다 — "일어났는데 금액이 0" 과 "일어나지 않음"은 다른 사실이다.
        postingRepository.saveAll(
            listOf(
                LedgerPosting(
                    entryId = entry.id!!,
                    orgId = orgId,
                    account = LedgerAccount.ORG_RECEIVABLE.name,
                    amountMinor = amountMinor,
                    merchantId = merchantId,
                    userId = userId,
                    occurredAt = occurredAt,
                ),
                LedgerPosting(
                    entryId = entry.id!!,
                    orgId = orgId,
                    account = LedgerAccount.MERCHANT_PAYABLE.name,
                    amountMinor = -amountMinor,
                    merchantId = merchantId,
                    userId = userId,
                    occurredAt = occurredAt,
                ),
            ),
        )
    }
}
