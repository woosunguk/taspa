package com.taspa.server.meal

import com.taspa.server.billing.LedgerService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.consumption.ConsumptionEventService
import com.taspa.server.consumption.dto.ConsumptionEventWriteRequest
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.consumption.ConsumptionEventStatus
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealRefund
import com.taspa.server.domain.meal.MealRefundRepository
import com.taspa.server.domain.meal.MealTransaction
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.MealTransactionStatus
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.meal.dto.RedeemRequest
import com.taspa.server.meal.dto.RedeemResponse
import com.taspa.server.meal.dto.RefundRequest
import com.taspa.server.org.OrganizationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/** redeem/void 결과 — mutated=false 면 멱등 재전송(신규 상태변경 없음 — 컨트롤러가 audit 를 생략한다). */
data class RedeemOutcome(
    val response: RedeemResponse,
    val mutated: Boolean,
    val orgId: UUID,
    val userId: UUID,
)

/**
 * 식권 redeem(승인)·void(취소) — 폐쇄루프 장부 기록만 수행한다(실 자금이동·PG 연동 없음).
 * 승인과 소비 이벤트 적재(source=payment, external_id=auth_id)는 **같은 트랜잭션**이다 — 장부와
 * 예측 정답데이터가 원자적으로 정합한다. 이중사용은 토큰 행 FOR UPDATE 로, 일 횟수·월 cap 판정은
 * 멤버십 행 FOR UPDATE(사용자×조직 직렬화)로 직렬화한다 — 서로 다른 토큰 두 개의 동시 redeem 이
 * 커밋 전 스냅샷으로 한도 검사를 동시에 통과하는 TOCTOU 를 막는다.
 *
 * 잠금 순서 불변식: **토큰 행 → 멤버십 행** — MealQrService.issue(만료 토큰 삭제 → 멤버십 잠금)와
 * 같은 순서를 지켜 교차 데드락을 방지한다.
 */
@Service
class MealRedeemService(
    private val qrTokenRepository: MealQrTokenRepository,
    private val transactionRepository: MealTransactionRepository,
    private val policyResolver: MealPolicyResolver,
    private val organizationRepository: OrganizationRepository,
    private val organizationService: OrganizationService,
    private val membershipRepository: OrgMembershipRepository,
    private val siteRepository: SiteRepository,
    private val consumptionEventService: ConsumptionEventService,
    private val consumptionEventRepository: ConsumptionEventRepository,
    private val refundRepository: MealRefundRepository,
    private val ledgerService: LedgerService,
    private val menuService: MealMenuService,
) {
    /**
     * 승인 흐름(설계 §4 CPM): POS 멱등 재전송 확인 → 토큰 소비(FOR UPDATE·단일사용) → 멤버십 재확인 →
     * 정책 평가(org 타임존: 끼니창·일 횟수·per-meal/월 cap) → 거래 기록 + 소비 이벤트 적재(동일 트랜잭션).
     *
     * 멱등 확인이 토큰 검증보다 먼저다 — 재전송은 이미 소비된 토큰을 들고 오므로 순서가 바뀌면
     * ALREADY_USED 로 오탐한다. 동시 같은 posTxnId 재전송 레이스(원거래가 미커밋이라 첫 멱등 조회가
     * null)는 토큰 행 잠금 대기 후 — 원거래 커밋이 보이는 시점 — 멱등 재조회로 기존 결과 재반환에
     * 수렴시킨다. (merchant_id, pos_txn_id) UNIQUE 는 최후 방어선으로 남는다(위반 시 409).
     */
    @Transactional
    fun redeem(
        merchant: Merchant,
        request: RedeemRequest,
    ): RedeemOutcome {
        val posTxnId = request.posTxnId.trim()
        if (posTxnId.isEmpty() || posTxnId.length > MAX_POS_TXN_ID_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "posTxnId 는 1~${MAX_POS_TXN_ID_LENGTH}자여야 합니다")
        }
        transactionRepository.findByMerchantIdAndPosTxnId(merchant.id!!, posTxnId)?.let { existing ->
            return RedeemOutcome(toResponse(existing), mutated = false, orgId = existing.orgId, userId = existing.userId)
        }
        if (request.amountMinor <= 0) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "amountMinor 는 1 이상이어야 합니다")
        }
        if (request.token.isBlank()) {
            throw AuthException(ErrorCode.QR_TOKEN_INVALID)
        }

        val now = Instant.now()
        // 비관적 잠금 — 같은 토큰의 동시 이중사용을 직렬화한다(두 번째는 반드시 ALREADY_USED).
        val qrToken =
            qrTokenRepository.findByTokenHashForUpdate(SecureTokenGenerator.hashToken(request.token))
                ?: throw AuthException(ErrorCode.QR_TOKEN_INVALID)
        if (qrToken.usedAt != null) {
            // 동시 같은 posTxnId 재전송 수렴 — 첫 멱등 조회 시점엔 원거래가 미커밋이라 null 이었지만,
            // 토큰 행 잠금 대기 후에는 커밋된 원거래가 보인다. 재전송을 "이미 사용된 QR(부정 사용)"로
            // 오판해 POS 가 승인 완료 거래를 현장 거절하는 계약 위반을 막는다.
            transactionRepository.findByMerchantIdAndPosTxnId(merchant.id!!, posTxnId)?.let { existing ->
                return RedeemOutcome(toResponse(existing), mutated = false, orgId = existing.orgId, userId = existing.userId)
            }
            throw AuthException(ErrorCode.QR_TOKEN_ALREADY_USED)
        }
        if (qrToken.expiresAt.isBefore(now)) {
            throw AuthException(ErrorCode.QR_TOKEN_EXPIRED)
        }
        qrToken.usedAt = now

        // 발급 시점과 redeem 시점 사이의 멤버십/조직 상태 변화를 재확인한다(정지 제어 실효성).
        if (!organizationService.isActiveMember(qrToken.orgId, qrToken.userId)) {
            throw AuthException(ErrorCode.FORBIDDEN, "조직 멤버십이 유효하지 않습니다")
        }
        // ★사용자×조직 단위 직렬화 — 토큰 행 잠금은 "같은 토큰"만 직렬화하므로, 서로 다른 유효 토큰
        // 두 개의 동시 redeem 은 서로 차단되지 않는다. 멤버십 행 FOR UPDATE 를 한도 판정(count/sum)보다
        // 먼저 잡아 두 트랜잭션이 커밋 전 스냅샷으로 daily/monthly 검사를 동시에 통과하는 우회를 막는다.
        membershipRepository.findByOrgIdAndUserIdForUpdate(qrToken.orgId, qrToken.userId)
            ?: throw AuthException(ErrorCode.FORBIDDEN, "조직 멤버십이 유효하지 않습니다")
        // 재직 상태 재확인 — 발급 시점엔 재직이었어도 그 사이 휴직 처리됐을 수 있다(토큰 TTL 60초라
        // 창은 좁지만, 발급이 막히는 것과 승인이 막히는 것은 별개 통제다). 잠금 직후 프로젝션이라
        // 1차 캐시가 아니라 DB 최신값으로 판정한다.
        val eligibility =
            membershipRepository.findEligibilityView(qrToken.orgId, qrToken.userId)
                ?: throw AuthException(ErrorCode.FORBIDDEN, "조직 멤버십이 유효하지 않습니다")
        if (EmploymentStatus.entries.firstOrNull { it.name == eligibility.getEmploymentStatus() } != EmploymentStatus.EMPLOYED) {
            throw AuthException(ErrorCode.NOT_EMPLOYED)
        }
        val org =
            organizationRepository.findById(qrToken.orgId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        val zone = MealPolicyCalculus.zoneOf(org.timezone)
        val localNow = ZonedDateTime.ofInstant(now, zone)
        // 정책 해석은 자격 조회(MealEntitlementService)와 **같은 입구**를 쓴다 — 부서 재정의가 붙어도
        // 화면과 계산대가 갈라질 수 없다. 소속 축은 방금 잠근 멤버십 행의 프로젝션에서 온다(1차 캐시가
        // 아니라 DB 최신값). 해석기는 잠금도 REQUIRES_NEW 도 열지 않는다(MealPolicyResolver 불변식).
        val policy =
            policyResolver
                .resolve(
                    qrToken.orgId,
                    MemberPolicyScope(eligibility.getDepartmentId(), eligibility.getSiteId()),
                    localNow.toLocalDate(),
                ).policy

        // 끼니창 판정(org 로컬 시각, [start, end)). 어느 창에도 안 들면 거절.
        // ★판정식은 MealPolicyCalculus 하나뿐이다 — 자격 조회(MealEntitlementService)가 같은 함수를
        //   쓰기 때문에 "화면은 가능인데 POS 는 거절" 이 구조적으로 불가능하다.
        val window =
            MealPolicyCalculus.resolveWindow(policy, localNow)
                ?: throw AuthException(ErrorCode.MEAL_WINDOW_CLOSED)

        // 일 횟수(org 로컬 일 경계) — APPROVED 거래수 기준(VOIDED 는 복원).
        val (dayStart, dayEnd) = MealPolicyCalculus.dayBounds(localNow)
        val todayCount = transactionRepository.countApprovedInWindow(qrToken.userId, qrToken.orgId, dayStart, dayEnd)
        if (todayCount >= policy.dailyMealCount) {
            throw AuthException(ErrorCode.DAILY_MEAL_LIMIT)
        }

        // 금액 분리: 조직 부담 = min(amount, per-meal 한도, 당월 잔여 cap). 초과분은 개인부담(selfPaid)으로
        // 거래는 승인한다 — 거절 대신 분리 승인이 현장 UX(부분 지원)에 맞다(설계 §6 정책 기본값).
        val (monthStart, monthEnd) = MealPolicyCalculus.monthBounds(localNow)
        val monthUsed = transactionRepository.sumOrgPaidInWindow(qrToken.userId, qrToken.orgId, monthStart, monthEnd)
        val monthRemaining = (policy.monthlyCapMinor - monthUsed).coerceAtLeast(0)
        val orgShare = minOf(request.amountMinor, policy.perMealLimitMinor, monthRemaining)
        val selfPaid = request.amountMinor - orgShare

        val transaction =
            transactionRepository.save(
                MealTransaction(
                    authId = UUID.randomUUID().toString(),
                    orgId = qrToken.orgId,
                    userId = qrToken.userId,
                    merchantId = merchant.id!!,
                    amountMinor = request.amountMinor,
                    selfPaidMinor = selfPaid,
                    mealWindow = window.name,
                    posTxnId = posTxnId,
                    approvedAt = now,
                ),
            )
        // 소비 이벤트 seam 적재(같은 트랜잭션) — 예측 정답데이터. external_id=auth_id 로 거래와 1:1 결속,
        // site 귀속은 가맹의 운영 사업장(선택, 거래 org 소속일 때만 — 교차 테넌트 site 오귀속 방지).
        // 취소는 같은 멱등키로 VOIDED 재적재(full-replace)된다.
        val menuRef = resolveMenuRef(transaction, resolveSiteId(merchant, qrToken.orgId), request.menuId)
        consumptionEventService.ingest(
            qrToken.orgId,
            listOf(consumptionRequestOf(transaction, merchant, resolveSiteId(merchant, qrToken.orgId), menuRef)),
        )
        // ★원장도 **같은 트랜잭션**에서 쓴다. 나중에 채우면 그 사이 원장과 장부가 어긋나고, 그 어긋남을
        // 잡으려고 만든 대사가 자기 지연 때문에 매번 경보를 울린다(경보 피로 → 진짜 불일치가 묻힌다).
        ledgerService.recordRedeem(transaction)
        return RedeemOutcome(
            toResponse(transaction, menuName = menuRef),
            mutated = true,
            orgId = qrToken.orgId,
            userId = qrToken.userId,
        )
    }

    /**
     * 취소(void) — 자기 가맹 거래만(타 가맹 거래는 존재 오라클 방지 위해 404). 멱등: 이미 VOIDED 면 재적용
     * 없이 현재 상태를 재반환한다. 거래 행 FOR UPDATE 로 동시 이중 void 를 직렬화한다(두 번째는
     * mutated=false 재반환 — MEAL_TX_VOIDED audit 중복 방지). 소비 이벤트는 같은 external_id 로
     * status=VOIDED full-replace — CONFIRMED 만 세는 집계에서 자동 제외된다. site 귀속은 redeem 시점에
     * 적재된 기존 이벤트 값을 보존한다(void 사이 가맹-site 재연결로 인한 이력 귀속 드리프트 방지).
     */
    @Transactional
    fun void(
        merchant: Merchant,
        authId: String,
    ): RedeemOutcome {
        val transaction =
            transactionRepository
                .findByAuthIdForUpdate(authId.trim())
                ?.takeIf { it.merchantId == merchant.id }
                ?: throw AuthException(ErrorCode.NOT_FOUND, "거래를 찾을 수 없습니다")
        if (transaction.statusEnum() == MealTransactionStatus.VOIDED) {
            return RedeemOutcome(toResponse(transaction), mutated = false, orgId = transaction.orgId, userId = transaction.userId)
        }
        // 상태를 바꾸기 **전에** 조직부담을 붙잡는다 — 바꾼 뒤엔 무엇을 되돌려야 하는지 알 수 없다.
        val reversedOrgPaid = transaction.orgPaidMinor()
        transaction.status = MealTransactionStatus.VOIDED.name
        transaction.voidedAt = Instant.now()
        ledgerService.recordVoid(transaction, reversedOrgPaid)
        val siteId =
            consumptionEventRepository
                .findByOrgIdAndSourceAndExternalId(transaction.orgId, CONSUMPTION_SOURCE, transaction.authId)
                ?.siteId
                ?: resolveSiteId(merchant, transaction.orgId)
        consumptionEventService.ingest(
            transaction.orgId,
            listOf(consumptionRequestOf(transaction, merchant, siteId, existingMenuRef(transaction))),
        )
        return RedeemOutcome(toResponse(transaction), mutated = true, orgId = transaction.orgId, userId = transaction.userId)
    }

    /**
     * **부분 환불** — 승인은 유지하고 금액만 줄인다.
     *
     * 전액 취소(void)와 나누어 둔 이유: 식사는 실제로 일어났고 예측·정산의 "인분 수"는 그대로여야
     * 하는데 금액만 틀렸을 때가 있다(주문 하나가 잘못 나간 경우). void 후 재승인으로 흉내 내면
     * 손님이 QR 을 다시 받아야 하고(토큰은 단일 사용) 장부에 거래가 둘로 남는다.
     *
     * ★**분담 재계산이 이 함수의 핵심**이다. 환불 후 금액으로 조직/개인 분담을 승인과 **같은 식**으로
     * 다시 계산하고 차액을 각자에게 돌린다. 조직 12,000 + 개인 3,000 인 거래에서 3,000을 환불하면
     * 남은 12,000은 한도 안에 온전히 들어가므로 조직이 전액 부담하고 **개인이 3,000을 돌려받는다** —
     * "개인부담 먼저 환불" 같은 규칙을 따로 두지 않아도 그 결과가 자연히 나온다. 규칙을 따로 두면
     * 승인 로직이 바뀔 때 둘이 어긋난다.
     *
     * 전액을 환불하면 void 와 같은 상태(VOIDED + 소비 이벤트 제외)로 수렴한다 — 두 경로가 다른
     * 결과를 내면 "전액 환불했는데 예측에는 남아 있다"가 된다.
     *
     * 멱등: 같은 (merchant, posRefundId) 재전송은 기존 결과를 재반환한다. 통신 단절 후 재시도가
     * 이중 환불이 되면 그건 그대로 회사·직원의 손실이다.
     */
    @Transactional
    fun refund(
        merchant: Merchant,
        authId: String,
        request: RefundRequest,
    ): RedeemOutcome {
        val posRefundId = request.posRefundId.trim()
        if (posRefundId.isEmpty() || posRefundId.length > MAX_POS_TXN_ID_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "posRefundId 는 1~${MAX_POS_TXN_ID_LENGTH}자여야 합니다")
        }
        // 멱등 확인이 거래 잠금보다 먼저 — 재전송은 이미 반영된 거래를 들고 오므로 순서가 바뀌면
        // "환불 금액이 남은 금액을 초과" 로 오탐한다(승인 경로의 멱등 선확인과 같은 이유).
        refundRepository.findByMerchantIdAndPosRefundId(merchant.id!!, posRefundId)?.let { existing ->
            val transaction =
                transactionRepository.findById(existing.transactionId).orElse(null)
                    ?: throw AuthException(ErrorCode.NOT_FOUND, "거래를 찾을 수 없습니다")
            // 재전송에도 **같은 분담**을 돌려준다 — 재시도가 다른 답을 주면 계산원이 현금을 두 번 센다.
            return RedeemOutcome(
                toResponse(transaction, existing.orgRefundedMinor, existing.selfRefundedMinor),
                mutated = false,
                orgId = transaction.orgId,
                userId = transaction.userId,
            )
        }
        if (request.amountMinor <= 0) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "amountMinor 는 1 이상이어야 합니다")
        }

        val transaction =
            transactionRepository
                .findByAuthIdForUpdate(authId.trim())
                ?.takeIf { it.merchantId == merchant.id }
                ?: throw AuthException(ErrorCode.NOT_FOUND, "거래를 찾을 수 없습니다")
        if (transaction.statusEnum() == MealTransactionStatus.VOIDED) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이미 취소된 거래는 환불할 수 없습니다")
        }
        if (request.amountMinor > transaction.amountMinor) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "환불 금액이 남은 결제 금액을 초과합니다")
        }

        val org =
            organizationRepository.findById(transaction.orgId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        val zone = MealPolicyCalculus.zoneOf(org.timezone)
        val localNow = ZonedDateTime.ofInstant(Instant.now(), zone)
        val eligibility = membershipRepository.findEligibilityView(transaction.orgId, transaction.userId)
        val policy =
            policyResolver
                .resolve(
                    transaction.orgId,
                    MemberPolicyScope(eligibility?.getDepartmentId(), eligibility?.getSiteId()),
                    localNow.toLocalDate(),
                ).policy

        val newAmount = transaction.amountMinor - request.amountMinor
        val previousOrgPaid = transaction.orgPaidMinor()
        // 이 거래를 뺀 당월 조직부담 — 자기 자신을 빼야 "내가 쓴 만큼은 다시 쓸 수 있다"가 성립한다.
        val (monthStart, monthEnd) =
            MealPolicyCalculus.monthBounds(
                ZonedDateTime.ofInstant(transaction.approvedAt, zone),
            )
        val monthUsedExcludingThis =
            transactionRepository.sumOrgPaidInWindow(transaction.userId, transaction.orgId, monthStart, monthEnd) -
                previousOrgPaid
        val remainingCap = (policy.monthlyCapMinor - monthUsedExcludingThis).coerceAtLeast(0)

        /*
         * 환불 후의 이상적 조직 부담 — 승인과 **같은 식**이다.
         *
         * ★그런데 이 값을 그대로 새 분담으로 쓰면 안 된다. 승인과 환불 **사이에 정책이 하향**되면
         *   (1식 한도 인하·월 한도 인하) 이상값이 승인 당시보다 훨씬 작아지고, 그 차이가 통째로
         *   "조직에 돌려줄 금액"이 된다. 실측: 15,000(조직 12,000+개인 3,000) 거래에서 한도가
         *   12,000→5,000 으로 내려간 뒤 1,000 을 환불하면 orgRefunded=7,000, selfRefunded=**−6,000**,
         *   직원 부담이 3,000→9,000 으로 **늘어난다**. 환불이 직원에게 청구서를 보내는 셈이다.
         *   (DB CHECK 가 음수를 막아 500 이 나므로 조용히 통과하지는 않지만, 그 자체로 결제 취소 실패다.)
         *
         * 그래서 이상값에서 곧장 분담을 정하지 않고 **환불 배분**을 먼저 정한다. 불변식 둘이면 충분하다:
         *   ① 두 주머니 모두 **줄기만** 한다(환불이 어느 쪽 부담도 늘릴 수 없다).
         *   ② 두 환불의 합은 정확히 요청 금액이다(없는 돈을 돌려주지 않는다).
         * `coerceIn(0, 환불액)` 한 줄이 둘을 동시에 강제한다.
         */
        val idealOrgShare = minOf(newAmount, policy.perMealLimitMinor, remainingCap).coerceAtLeast(0)
        val orgRefunded = (previousOrgPaid - idealOrgShare).coerceIn(0, request.amountMinor)
        val selfRefunded = request.amountMinor - orgRefunded
        val newOrgShare = previousOrgPaid - orgRefunded

        transaction.amountMinor = newAmount
        // 개인 부담도 **차감으로** 구한다(newAmount − newOrgShare 로 재계산하지 않는다). 두 식은 대수적으로
        // 같지만, 차감은 "줄기만 한다"는 불변식을 코드 모양에서 바로 읽히게 한다.
        transaction.selfPaidMinor = transaction.selfPaidMinor - selfRefunded
        transaction.refundedMinor += request.amountMinor
        // 전액 환불은 void 와 같은 자리로 수렴한다 — 두 경로가 다른 결과를 내면 "전액 환불했는데
        // 예측에는 남아 있다"가 된다.
        val fullyRefunded = newAmount == 0L
        if (fullyRefunded) {
            transaction.status = MealTransactionStatus.VOIDED.name
            transaction.voidedAt = Instant.now()
        }

        val savedRefund =
            refundRepository.save(
                MealRefund(
                    transactionId = transaction.id!!,
                    merchantId = merchant.id!!,
                    posRefundId = posRefundId,
                    amountMinor = request.amountMinor,
                    orgRefundedMinor = orgRefunded,
                    selfRefundedMinor = selfRefunded,
                    reason =
                        request.reason
                            ?.trim()
                            ?.take(200)
                            ?.ifBlank { null },
                ),
            )

        ledgerService.recordRefund(
            transaction = transaction,
            refundId = savedRefund.id!!,
            orgRefundedMinor = orgRefunded,
            selfRefundedMinor = selfRefunded,
        )

        // 소비 이벤트는 **인분 수**를 나른다 — 부분 환불은 금액만 바꾸므로 이벤트가 달라질 게 없다.
        // 전액 환불일 때만 VOIDED 로 full-replace 해서 집계에서 빠지게 한다(void 와 같은 처리).
        if (fullyRefunded) {
            val siteId =
                consumptionEventRepository
                    .findByOrgIdAndSourceAndExternalId(transaction.orgId, CONSUMPTION_SOURCE, transaction.authId)
                    ?.siteId
                    ?: resolveSiteId(merchant, transaction.orgId)
            consumptionEventService.ingest(
                transaction.orgId,
                listOf(consumptionRequestOf(transaction, merchant, siteId, existingMenuRef(transaction))),
            )
        }
        return RedeemOutcome(
            toResponse(transaction, orgRefunded, selfRefunded),
            mutated = true,
            orgId = transaction.orgId,
            userId = transaction.userId,
        )
    }

    /**
     * 소비 이벤트 site 귀속 — merchants 는 플랫폼 전역, sites 는 org 스코프다. 가맹의 site 가 거래 org
     * 소속일 때만 귀속하고 아니면 null(타 조직 사업장 UUID 가 이 org 의 이벤트에 스며드는 것을 차단).
     */
    private fun resolveSiteId(
        merchant: Merchant,
        orgId: UUID,
    ): UUID? = merchant.siteId?.takeIf { siteRepository.findByIdAndOrgId(it, orgId) != null }

    /**
     * 실적의 **메뉴 귀속** — `consumption_events.menu_ref` 를 채우는 유일한 프로덕션 경로다.
     * V17 부터 컬럼이 있었지만 채우는 코드가 없어 집계 API 의 `groupBy=menu` 는 항상 null 을 냈다.
     *
     * ★**그 끼니의 메뉴가 정확히 하나일 때만** 귀속한다. 여러 개면(A코너/B코너) 단말은 손님이 어느
     * 코너에서 받았는지 알 수 없고, 아무 쪽이나 고르면 **절반의 확률로 틀린 메뉴의 인기가 올라간다** —
     * 그 왜곡은 집계에만 나타나 아무도 반증할 수 없다. 모르면 null 로 둔다.
     *
     * ★값은 **메뉴 이름**이다(id 가 아니다). 식단 행은 날짜마다 새로 생기므로 id 로 저장하면 같은
     * "돈까스"가 날마다 다른 키가 되어 **날짜를 넘는 집계가 영구히 불가능**하다 — 메뉴별 인기를 보는 것이
     * 이 축의 목적이므로 이름이 정확히 그 목적에 맞는 키다. 이름 길이는 `menu_ref`(128) 안에 들어오도록
     * 서비스가 제한한다(잘라내면 키가 조용히 달라진다).
     *
     * ★날짜는 **org-로컬 달력**이다(소비 집계 date 버킷과 같은 앵커). UTC 절단으로 잡으면 KST 아침 식사가
     * 전날 식단에 귀속된다.
     *
     * 잠금·REQUIRES_NEW 를 열지 않는 읽기 한 번이다(멤버십 FOR UPDATE 구간 안에서 호출되므로 —
     * `MealPolicyResolver` 와 같은 규약).
     */
    private fun resolveMenuRef(
        transaction: MealTransaction,
        siteId: UUID?,
        explicitMenuId: UUID?,
    ): String? {
        val org = organizationRepository.findById(transaction.orgId).orElse(null) ?: return null
        val zone = MealPolicyCalculus.zoneOf(org.timezone)
        val localDate = transaction.approvedAt.atZone(zone).toLocalDate()
        val menus = menuService.forSlot(transaction.orgId, localDate, transaction.mealWindow, siteId)
        if (menus.isEmpty()) return null
        if (explicitMenuId != null) {
            // 그 끼니의 메뉴 목록 안에서만 찾는다 — 다른 조직·다른 날 메뉴 id 를 보내 실적을 옮기지 못하게.
            // 못 찾으면 결제는 그대로 승인하고 귀속만 비운다(응답 menuName=null 로 단말이 알 수 있다).
            return menus.firstOrNull { it.id == explicitMenuId }?.name
        }
        return menus.singleOrNull()?.name
    }

    /**
     * 재적재(취소·환불)에서 **기존 귀속을 보존**한다. full-replace 계약이라 여기서 다시 계산하면,
     * 메뉴가 여럿이라 단말이 골라 준 경우의 선택이 null 로 덮여 **사라진다**(그 끼니의 코너별 실적이
     * 취소 한 건 때문에 조용히 지워진다).
     */
    private fun existingMenuRef(transaction: MealTransaction): String? =
        consumptionEventRepository
            .findByOrgIdAndSourceAndExternalId(transaction.orgId, CONSUMPTION_SOURCE, transaction.authId)
            ?.menuRef

    /** 거래 → 소비 이벤트 적재 요청(full-replace 계약: 거래의 완전한 현재 상태를 재전송한다). */
    private fun consumptionRequestOf(
        transaction: MealTransaction,
        merchant: Merchant,
        siteId: UUID?,
        menuRef: String?,
    ): ConsumptionEventWriteRequest =
        ConsumptionEventWriteRequest(
            source = CONSUMPTION_SOURCE,
            externalId = transaction.authId,
            userSub = transaction.userId,
            merchantId = merchant.id,
            siteId = siteId,
            menuRef = menuRef,
            mealWindow = transaction.mealWindow,
            quantity = 1,
            status =
                when (transaction.statusEnum()) {
                    MealTransactionStatus.APPROVED -> ConsumptionEventStatus.CONFIRMED.name
                    MealTransactionStatus.VOIDED -> ConsumptionEventStatus.VOIDED.name
                },
            occurredAt = transaction.approvedAt,
        )

    private fun toResponse(
        transaction: MealTransaction,
        orgRefundedMinor: Long? = null,
        selfRefundedMinor: Long? = null,
        menuName: String? = null,
    ): RedeemResponse =
        RedeemResponse(
            authId = transaction.authId,
            approvedAmountMinor = transaction.orgPaidMinor(),
            selfPaidMinor = transaction.selfPaidMinor,
            mealWindow = transaction.mealWindow,
            status = transaction.status,
            orgRefundedMinor = orgRefundedMinor,
            selfRefundedMinor = selfRefundedMinor,
            menuName = menuName,
        )

    private companion object {
        /** 소비 이벤트 source — redemption 은 결제(생산자=거래서비스) 경로다. */
        const val CONSUMPTION_SOURCE = "payment"

        /** meal_transactions.pos_txn_id 컬럼 상한(VARCHAR(128), V25). */
        const val MAX_POS_TXN_ID_LENGTH = 128
    }
}
