package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.meal.MealQrToken
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealRefundRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.meal.dto.MealQrIssueResponse
import com.taspa.server.meal.dto.MealTransactionView
import com.taspa.server.org.OrganizationService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 식권 QR 발급(사용자 세션 경로). 불투명 핸들 방식 — 원문 토큰(256bit)은 응답으로만 나가고 저장은
 * SHA-256 해시뿐이다(trusted_devices·magic_link 와 동일 패턴). 60초 TTL·단일사용은 redeem 이 강제한다.
 */
@Service
class MealQrService(
    private val qrTokenRepository: MealQrTokenRepository,
    private val transactionRepository: MealTransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val refundRepository: MealRefundRepository,
    private val organizationService: OrganizationService,
    private val membershipRepository: OrgMembershipRepository,
    private val properties: MealProperties,
) {
    /**
     * QR 발급 — 완전 인증 세션 사용자 + 해당 org 활성 멤버(조직도 ACTIVE — isActiveMember 가 함께 판정)만.
     * 레이트리밋: 직전 발급 후 쿨다운(기본 10초) 내 재요청은 429 — 원문 재반환이 불가능(해시만 저장)하므로
     * 재사용 반환 대신 거절을 채택했다. 만료 토큰은 발급 시 삭제 쿼리 1회로 지연 정리한다.
     *
     * 동시성: 쿨다운 판정(read-then-write)은 멤버십 행 FOR UPDATE(사용자×조직 직렬화)로 감싼다 —
     * 동시 발급 N 건이 같은 '직전 발급' 스냅샷을 읽고 전부 통과하는 429 우회(유효 토큰 다발 비축)를 막는다.
     * 잠금 순서 불변식: **토큰 행(만료 삭제) → 멤버십 행** — redeem 경로(토큰 잠금 → 멤버십 잠금)와 같은
     * 순서를 지켜 교차 데드락을 방지한다(삭제를 멤버십 잠금보다 먼저 수행하는 이유).
     */
    @Transactional
    fun issue(
        orgId: UUID,
        userId: UUID,
    ): MealQrIssueResponse {
        if (!organizationService.isActiveMember(orgId, userId)) {
            throw AuthException(ErrorCode.FORBIDDEN, "해당 조직의 활성 멤버가 아닙니다")
        }
        val now = Instant.now()
        qrTokenRepository.deleteExpiredByUser(userId, now)
        membershipRepository.findByOrgIdAndUserIdForUpdate(orgId, userId)
            ?: throw AuthException(ErrorCode.FORBIDDEN, "해당 조직의 활성 멤버가 아닙니다")
        requireEmployed(orgId, userId)
        // 잠금 획득 후 판정 — 선행 동시 요청이 커밋한 발급이 여기서 보인다(만료 토큰은 위에서 삭제됐지만
        // TTL(60s) > 쿨다운(10s)이라 쿨다운에 걸리는 직전 발급은 항상 미만료로 남아 판정이 달라지지 않는다).
        qrTokenRepository.findTopByUserIdOrderByCreatedAtDesc(userId)?.let { latest ->
            if (latest.createdAt.plus(properties.qrIssueCooldown).isAfter(now)) {
                throw AuthException(ErrorCode.QR_RATE_LIMITED)
            }
        }
        val rawToken = SecureTokenGenerator.generateToken()
        val expiresAt = now.plus(properties.qrTtl)
        qrTokenRepository.save(
            MealQrToken(
                tokenHash = SecureTokenGenerator.hashToken(rawToken),
                userId = userId,
                orgId = orgId,
                expiresAt = expiresAt,
            ),
        )
        return MealQrIssueResponse(token = rawToken, expiresAt = expiresAt)
    }

    /** 본인 거래 이력 최근순(상점명 라벨 포함). 소유권은 userId 필터 자체가 보장한다. */
    @Transactional(readOnly = true)
    fun myTransactions(
        userId: UUID,
        limit: Int,
    ): List<MealTransactionView> {
        val transactions = transactionRepository.findByUserIdOrderByApprovedAtDesc(userId, PageRequest.of(0, limit))
        val merchantNames =
            merchantRepository
                .findAllById(transactions.map { it.merchantId }.distinct())
                .associateBy({ it.id!! }, { it.name })
        // 환불 이력은 거래마다 질의하지 않고 한 번에 모은다(N+1 방지). 환불이 하나도 없으면 질의 자체를
        // 생략한다 — 빈 IN 절은 무의미한 왕복이고, 대부분의 이력에는 환불이 없다.
        val refundedIds = transactions.filter { it.refundedMinor > 0 }.mapNotNull { it.id }
        val refunds =
            if (refundedIds.isEmpty()) {
                emptyMap()
            } else {
                refundRepository.summarizeByTransactionIds(refundedIds).associateBy { it.getTransactionId() }
            }
        return transactions.map {
            val refund = it.id?.let { id -> refunds[id] }
            MealTransactionView(
                authId = it.authId,
                orgId = it.orgId,
                merchantName = merchantNames[it.merchantId],
                amountMinor = it.amountMinor,
                selfPaidMinor = it.selfPaidMinor,
                mealWindow = it.mealWindow,
                status = it.status,
                approvedAt = it.approvedAt,
                voidedAt = it.voidedAt,
                refundedMinor = it.refundedMinor,
                selfRefundedMinor = refund?.getSelfRefunded() ?: 0,
                originalAmountMinor = it.originalAmountMinor(),
                lastRefundedAt = refund?.getLastRefundedAt(),
            )
        }
    }

    /**
     * 재직 상태 확인 — 휴직·퇴직자에게는 식권을 발급하지 않는다.
     *
     * ★멤버십 ACTIVE 와 재직상태는 **다른 축**이다. HR 시스템(SCIM)이 휴직을 밀면
     * `employment_status` 만 ON_LEAVE 가 되고 멤버십은 ACTIVE 로 남는데, 그동안 회사는 계속 지불한다.
     * 조직 입장에서 휴직자 식대는 지급 대상이 아니므로 여기서 끊는다.
     *
     * 반드시 **멤버십 행을 FOR UPDATE 로 잠근 뒤** 호출한다 — 잠금 전 값으로 판정하면 동시에 들어온
     * 휴직 처리와 경합한다. 프로젝션이라 1차 캐시를 타지 않아 잠금 결과가 반영된다.
     */
    private fun requireEmployed(
        orgId: UUID,
        userId: UUID,
    ) {
        val eligibility =
            membershipRepository.findEligibilityView(orgId, userId)
                ?: throw AuthException(ErrorCode.FORBIDDEN, "해당 조직의 활성 멤버가 아닙니다")
        // 알 수 없는 값은 **닫히는 쪽**으로 낙하한다(손상된 행이 500 이 아니라 거부가 되게).
        if (EmploymentStatus.entries.firstOrNull { it.name == eligibility.getEmploymentStatus() } != EmploymentStatus.EMPLOYED) {
            throw AuthException(ErrorCode.NOT_EMPLOYED)
        }
    }
}
