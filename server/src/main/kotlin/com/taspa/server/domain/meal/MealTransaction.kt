package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 식권 승인 거래(폐쇄루프 장부 — 실 자금이동 없음). authId 는 소비 이벤트(consumption_events)의
 * external_id 로 재사용돼 redemption→consumption seam 을 잇는다. (merchantId, posTxnId) 는 POS 재전송
 * 멱등키(UNIQUE). selfPaidMinor 는 한도 초과 개인부담 — 조직 부담 = amountMinor − selfPaidMinor.
 */
@Entity
@Table(name = "meal_transactions")
class MealTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "auth_id", nullable = false, unique = true, length = 64)
    val authId: String,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "merchant_id", nullable = false)
    val merchantId: UUID,
    /**
     * **환불 후 현재 금액**(승인 시점 원금이 아니다 — 원금은 [originalAmountMinor]).
     *
     * 부분 환불이 이 값을 줄이도록 한 것은 의도적이다. 그래야 청구·월 한도·자격 조회의 집계
     * (`amount - self_paid`)가 쿼리를 한 줄도 고치지 않고 환불을 반영한다. 원금을 남기고 환불을
     * 따로 빼는 방식이었다면 그 8개 쿼리를 모두 고쳐야 하고, 하나만 빠뜨려도 회사가 환불된 돈을
     * 계속 청구한다.
     */
    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long,
    @Column(name = "self_paid_minor", nullable = false)
    var selfPaidMinor: Long = 0,
    /** 환불 누계. 원금 = amountMinor + refundedMinor. */
    @Column(name = "refunded_minor", nullable = false)
    var refundedMinor: Long = 0,
    @Column(name = "meal_window", nullable = false, length = 16)
    val mealWindow: String,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = MealTransactionStatus.APPROVED.name,
    @Column(name = "pos_txn_id", nullable = false, length = 128)
    val posTxnId: String,
    /**
     * 승인 시각 — 모든 기간 집계(청구·월 한도·원장·소비)의 **공통 앵커**다.
     *
     * 프로덕션 코드는 이 값을 바꾸지 않는다(생성 시 한 번). `updatable` 을 연 것은 테스트가 월 경계를
     * 넘는 상황을 만들 수 있어야 하기 때문이다 — 그 시나리오를 못 만들면 "승인과 환불이 다른 달"인
     * 결함이 영원히 보이지 않는다(실제로 그 사각에 결함이 하나 있었다).
     */
    @Column(name = "approved_at", nullable = false)
    var approvedAt: Instant = Instant.now(),
    @Column(name = "voided_at")
    var voidedAt: Instant? = null,
) {
    fun statusEnum(): MealTransactionStatus = MealTransactionStatus.valueOf(status)

    /** 조직 부담액(청구 대상). 개인부담을 제외한 나머지. */
    fun orgPaidMinor(): Long = amountMinor - selfPaidMinor

    /**
     * 승인 당시의 원래 금액. [amountMinor] 는 **환불 후 현재값**이라, 영수증·이력에서 "원래 얼마였나"를
     * 말하려면 환불 누계를 더해야 한다. 별도 컬럼을 두지 않는 이유는 두 값이 어긋날 여지를 만들지
     * 않기 위해서다(현재값 + 환불누계 = 원금이 항상 성립한다).
     */
    fun originalAmountMinor(): Long = amountMinor + refundedMinor
}
