package com.taspa.server.domain.meal

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MerchantRepository : JpaRepository<Merchant, UUID>

interface MerchantMemberRepository : JpaRepository<MerchantMember, UUID> {
    /** 이 사용자가 관리하는 활성 가맹점(매장 선택 화면). 매장이 정지돼도 멤버십은 남으므로 상태로 거른다. */
    fun findByUserIdAndStatus(
        userId: UUID,
        status: String,
    ): List<MerchantMember>

    fun findByMerchantIdAndStatus(
        merchantId: UUID,
        status: String,
    ): List<MerchantMember>

    fun findByMerchantIdAndUserId(
        merchantId: UUID,
        userId: UUID,
    ): MerchantMember?
}

interface MealPolicyRepository : JpaRepository<MealPolicy, UUID>

interface MealPolicyRevisionRepository : JpaRepository<MealPolicyRevision, UUID> {
    /**
     * 조직의 정책 변경 이력 최근순(idx_meal_policy_revision_org_time). 행 상한은 Pageable 로 준다.
     *
     * ★삭제·수정 메서드를 두지 않는다. append-only 는 스키마가 아니라 **여기 없는 것**이 지킨다 —
     * `JpaRepository` 가 물려주는 `delete*`/`save` 가 있긴 하지만, 서비스가 append 외의 용도로 이
     * 리포지터리를 부르지 않는 것이 규약이다(청구서 근거를 사후에 고칠 수 있으면 근거가 아니다).
     */
    fun findByOrgIdOrderByRecordedAtDesc(
        orgId: UUID,
        pageable: Pageable,
    ): List<MealPolicyRevision>
}

interface MealPolicyOverrideRepository : JpaRepository<MealPolicyOverride, UUID> {
    /**
     * 조직의 전 재정의를 **한 번에** 읽는다(idx_mpo_org). 부서 트리를 타고 올라가며 노드마다 질의하면
     * 깊이만큼 왕복이 생기는데, 그 왕복이 redeem 의 멤버십 잠금 구간 **안에서** 일어난다 — 잠금을 쥔
     * 시간이 조직도 깊이에 비례해 길어지는 것은 승인 처리량에 그대로 나타난다. 조직당 재정의는
     * 부서·사업장 수 규모라 전부 읽어 메모리에서 병합하는 편이 싸고 예측 가능하다.
     */
    fun findByOrgId(orgId: UUID): List<MealPolicyOverride>

    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): MealPolicyOverride?
}

interface MealQrTokenRepository : JpaRepository<MealQrToken, UUID> {
    /**
     * redeem 소비 경로 — **비관적 잠금(FOR UPDATE)** 으로 같은 토큰의 동시 이중사용을 직렬화한다.
     * 첫 트랜잭션이 used_at 을 마킹·커밋한 뒤 두 번째가 행을 읽는다 — 같은 posTxnId 재전송이면 잠금 후
     * 멱등 재조회로 기존 결과 재반환에 수렴하고, 다른 posTxnId(진짜 이중사용)만 ALREADY_USED 로 떨어진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MealQrToken t WHERE t.tokenHash = :tokenHash")
    fun findByTokenHashForUpdate(
        @Param("tokenHash") tokenHash: String,
    ): MealQrToken?

    /** 발급 쿨다운 판정용 — 사용자의 직전 발급 시각 조회(idx_meal_qr_user_time). */
    fun findTopByUserIdOrderByCreatedAtDesc(userId: UUID): MealQrToken?

    /** 만료 토큰 지연 정리 — 발급 시 삭제 쿼리 1회(별도 스케줄 잡 없이 자연 청소). */
    @Modifying
    @Query("DELETE FROM MealQrToken t WHERE t.userId = :userId AND t.expiresAt < :now")
    fun deleteExpiredByUser(
        @Param("userId") userId: UUID,
        @Param("now") now: Instant,
    )
}

interface MealTransactionRepository : JpaRepository<MealTransaction, UUID> {
    /** POS 멱등키 조회 — 같은 (merchant, posTxnId) 재전송이면 기존 승인 결과를 재반환한다(새 거래 금지). */
    fun findByMerchantIdAndPosTxnId(
        merchantId: UUID,
        posTxnId: String,
    ): MealTransaction?

    fun findByAuthId(authId: String): MealTransaction?

    /**
     * void 경로 — **비관적 잠금(FOR UPDATE)** 으로 같은 거래의 동시 이중 void 를 직렬화한다. 두 번째
     * 트랜잭션은 첫 커밋 후 VOIDED 상태를 읽어 멱등 재반환(mutated=false)이 되므로 audit 중복이 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MealTransaction t WHERE t.authId = :authId")
    fun findByAuthIdForUpdate(
        @Param("authId") authId: String,
    ): MealTransaction?

    /** 사용자 본인 거래 최근순(계정 화면·모바일 이력). */
    fun findByUserIdOrderByApprovedAtDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<MealTransaction>

    /** 오늘(org 타임존 일 경계 [from,to)) 승인 거래수 — daily_meal_count 판정. */
    @Query(
        """
        SELECT COUNT(t) FROM MealTransaction t
        WHERE t.userId = :userId AND t.orgId = :orgId AND t.status = 'APPROVED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun countApprovedInWindow(
        @Param("userId") userId: UUID,
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /**
     * org 전체의 승인 건수 — **청구할 것이 있는가**만 묻는다(자동 생성 잡의 사전 판정).
     *
     * 집계를 돌려 0 인지 보는 대신 이 값을 먼저 보는 이유: 0 이면 청구서 행도 감사 이벤트도 만들지
     * 않는다. 만들었다가 지우면 존재하지 않는 청구서의 INVOICE_GENERATED 가 활동로그에 남는다.
     */
    @Query(
        """
        SELECT COUNT(t) FROM MealTransaction t
        WHERE t.orgId = :orgId AND t.status = 'APPROVED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun countApprovedForOrgInWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /** 당월(org 타임존 월 경계 [from,to)) 조직 부담 합 — monthly_cap 판정(개인부담 제외). */
    @Query(
        """
        SELECT COALESCE(SUM(t.amountMinor - t.selfPaidMinor), 0) FROM MealTransaction t
        WHERE t.userId = :userId AND t.orgId = :orgId AND t.status = 'APPROVED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun sumOrgPaidInWindow(
        @Param("userId") userId: UUID,
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /**
     * 당월 개인부담 합 — **표시 전용**이다(한도 판정에 쓰지 않는다). 조직부담 누계는 승인 경로와 반드시
     * 같아야 하므로 `sumOrgPaidInWindow` 를 그대로 재사용하고, 여기서는 화면이 "내가 얼마를 보탰나"를
     * 말할 수 있게 하는 숫자만 별도로 센다(같은 창·같은 APPROVED 필터).
     */
    @Query(
        """
        SELECT COALESCE(SUM(t.selfPaidMinor), 0) FROM MealTransaction t
        WHERE t.userId = :userId AND t.orgId = :orgId AND t.status = 'APPROVED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun sumSelfPaidInWindow(
        @Param("userId") userId: UUID,
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /**
     * 청구 집계(billing) — org 월 창([from,to), org 타임존 앵커)의 **APPROVED** 거래를 사용자별로 묶어
     * (userId, 건수, 조직부담 합=Σ(amount−selfPaid))을 반환한다. VOIDED 는 자동 제외(청구 전 취소 반영).
     */
    @Query(
        """
        SELECT t.userId, COUNT(t), COALESCE(SUM(t.amountMinor - t.selfPaidMinor), 0)
        FROM MealTransaction t
        WHERE t.orgId = :orgId AND t.status = 'APPROVED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        GROUP BY t.userId
        """,
    )
    fun aggregateOrgPaidByUser(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>

    /**
     * 같은 창의 **개인부담** 합계. 청구 대상이 아니라(청구는 조직부담만) "직원이 얼마를 자기 돈으로 냈나"를
     * 대시보드가 함께 보여 주기 위한 값이다. WHERE 절은 aggregateOrgPaidByUser 와 동일해야 한다 —
     * 조건이 갈라지면 한 화면 안에서 두 숫자의 모집단이 달라진다.
     */
    @Query(
        """
        SELECT COALESCE(SUM(t.selfPaidMinor), 0) FROM MealTransaction t
        WHERE t.orgId = :orgId AND t.status = 'APPROVED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun sumSelfPaidByOrgInWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    /**
     * 가맹 콘솔 거래 로그 — 자기 매장의 [from,to) 구간 거래를 최근순으로 읽는다(merchant 타임존 앵커의
     * 일 경계는 호출부가 Instant 로 환산한다). VOIDED 도 포함해 반환한다 — 취소는 매장이 알아야 할
     * 사실이고, 집계(예측·정산)에서 빠지는 것과 로그에 보이는 것은 별개다.
     * 행 상한은 Pageable 로 준다(자원고갈 방지).
     */
    @Query(
        """
        SELECT t FROM MealTransaction t
        WHERE t.merchantId = :merchantId
          AND t.approvedAt >= :from AND t.approvedAt < :to
        ORDER BY t.approvedAt DESC
        """,
    )
    fun findByMerchantInWindow(
        @Param("merchantId") merchantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): List<MealTransaction>

    /**
     * 가맹 정산 집계 — 매장 월 창([from,to), **매장 타임존** 앵커)의 APPROVED 거래를 **조직별로** 묶는다.
     *
     * (orgId, 건수, 조직부담 합, 개인부담 합, 환불 누계). 조직별로 나누는 이유는 두 가지다:
     * 매장은 어느 고객사에서 얼마가 나왔는지로 자기 영업을 보고, 이의제기 때 조직 단위로 특정한다.
     *
     * ★org 단위 청구 집계(`aggregateOrgPaidByUser`)와 **창이 다르다** — 여기는 매장 타임존이 앵커다.
     * 한 매장이 여러 조직 손님을 받으므로 어느 조직의 달력을 빌릴지 정할 수 없다(예측 그레인과 같은 이유).
     * 그래서 매장 정산 합계와 조직 청구서 합계는 경계일 거래만큼 **정당하게 다를 수 있다**.
     *
     * ★**금액축은 APPROVED 만, 환불축은 상태 무관**이다(자체 검토에서 잡은 결함). 전액 환불은 거래를
     * **VOIDED 로 수렴**시키므로(`MealRedeemService.refund`), `WHERE status='APPROVED'` 하나로 묶으면
     * 전액 환불이 환불 합계에서 통째로 사라진다 — 10,000 전액 환불 + 3,000 부분 환불이 일어난 달에
     * 매장은 "환불 3,000원"을 본다. `refunded_minor` 는 **환불만이 올린다**(순수 void 는 0 그대로)라
     * 상태를 가리지 않고 더하는 것이 정확히 "되돌아간 금액"이다. 그 덕에 전액 환불로 지급액이 0 이 된
     * 조직도 줄에 남아 "환불 때문에 0원"이라는 사실이 화면에서 사라지지 않는다.
     */
    @Query(
        """
        SELECT t.orgId,
               SUM(CASE WHEN t.status = 'APPROVED' THEN 1L ELSE 0L END),
               COALESCE(SUM(CASE WHEN t.status = 'APPROVED' THEN t.amountMinor - t.selfPaidMinor ELSE 0L END), 0),
               COALESCE(SUM(CASE WHEN t.status = 'APPROVED' THEN t.selfPaidMinor ELSE 0L END), 0),
               COALESCE(SUM(t.refundedMinor), 0)
        FROM MealTransaction t
        WHERE t.merchantId = :merchantId
          AND t.approvedAt >= :from AND t.approvedAt < :to
        GROUP BY t.orgId
        """,
    )
    fun aggregateMerchantSettlementByOrg(
        @Param("merchantId") merchantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>

    /**
     * 이 창에 거래가 있는 **조직만** — 전역 대사가 훑을 후보에 더한다.
     *
     * ★대사의 후보를 원장(ledger_entries)에서만 뽑으면 **원장 자체가 빠진 조직**이 표본에서 구조적으로
     * 제외된다 — 조직별 대사는 amountDrift 로 정확히 잡아내는데 전역 화면만 "이상 없음"이라고 답하고,
     * 그 조직을 못 봤다는 흔적조차 남지 않는다. 대사가 잡으라고 만들어진 실패 형태가 경보 화면에서만
     * 안 보이는 셈이다. 그래서 후보는 원장 ∪ **장부**의 합집합으로 잡는다.
     *
     * 소비이벤트는 후보에 넣지 않는다(의도적): 결제 외 생산자도 소비를 적재할 수 있어(예측용 정답데이터)
     * 결제가 없는 조직은 countDrift 가 정상적으로 0 이 아니다 — 후보에 넣으면 정상 상태가 매달 경보로
     * 올라와 진짜 불일치를 가린다. 다른 이유로 후보에 든 조직은 건수축까지 함께 검사된다.
     */
    @Query(
        """
        SELECT DISTINCT t.orgId FROM MealTransaction t
        WHERE t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun orgIdsWithActivity(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<UUID>

    /**
     * 이 창에 거래가 있는 **매장만** — 플랫폼 전역 지급 현황이 훑을 대상을 좁힌다.
     *
     * 매장마다 달 경계가 다르므로(각자 타임존) 한 번의 group-by 로 끝낼 수 없다. 그래서 "활동이 있었을
     * 수 있는" 매장을 UTC 로 넉넉히 추린 뒤 매장별로 정확히 계산한다 — 비용이 전 매장 수가 아니라 실제
     * 사용량에 비례한다(전역 대사의 `orgIdsWithActivity` 와 같은 형태).
     */
    @Query(
        """
        SELECT DISTINCT t.merchantId FROM MealTransaction t
        WHERE t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun merchantIdsWithActivity(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<UUID>

    /** 같은 창의 **취소 건수** — 정산 명세가 "왜 건수가 로그보다 적은가"를 스스로 설명하게 한다. */
    @Query(
        """
        SELECT COUNT(t) FROM MealTransaction t
        WHERE t.merchantId = :merchantId AND t.status = 'VOIDED'
          AND t.approvedAt >= :from AND t.approvedAt < :to
        """,
    )
    fun countVoidedForMerchantInWindow(
        @Param("merchantId") merchantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long
}
