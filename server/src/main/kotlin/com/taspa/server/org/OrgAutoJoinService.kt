package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.org.OrgDomainRepository
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 이메일 도메인 자동 조직 가입 판정 — **이메일 인증 성공 시점**(email_verified=true 전이)마다 호출된다.
 *
 * 판정(전부 만족할 때만): 도메인이 org_domains 에 등록 && **verified** && 그 조직 opt-in
 * (**auto_join_enabled**) && 조직 **ACTIVE**. 결과는 ensureJitMembership(MEMBER 고정·JOINED 이력·멱등).
 * 공용 이메일 도메인은 등록 자체가 차단돼 있어 조회가 비지만, 값싼 선차단으로 DB 왕복도 줄인다.
 *
 * 격리 불변식: 자동 가입은 부가 기능 — 어떤 실패도 인증 플로우로 전파하지 않는다(WARN 로그만).
 * REQUIRES_NEW 로 자체 커밋해, 호출부 트랜잭션(EmailVerificationService.verify 등)이 이 판정의
 * 예외로 rollback-only 가 되는 것을 원천 차단한다(AuditEventService 와 동일한 격리 패턴 —
 * 중첩 커넥션 점유는 단건 INSERT 한정의 짧은 창).
 */
@Service
class OrgAutoJoinService(
    private val orgDomainRepository: OrgDomainRepository,
    private val organizationRepository: OrganizationRepository,
    private val organizationService: OrganizationService,
    private val auditEventService: AuditEventService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(OrgAutoJoinService::class.java)

    private val autoJoinTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /**
     * 이메일 인증 성공 직후 호출 — 자동 가입 대상이면 MEMBER 멤버십을 보장한다(이미 멤버면 no-op).
     *
     * **커밋 순서 불변식**: 호출부 트랜잭션(EmailVerificationService.verify·MagicLinkService.consume 등이
     * emailVerified=true 전이를 들고 있는)이 살아 있으면 판정을 afterCommit 으로 미룬다 — REQUIRES_NEW
     * 자체 커밋이 외부 트랜잭션 커밋보다 먼저 확정되면, 외부가 커밋 시점에 롤백될 때 '미검증 계정에
     * ACTIVE 멤버십·이력·감사만 영속'되는 역전이 생기기 때문이다. 외부 롤백 시 afterCommit 은 실행되지
     * 않으므로 판정 자체가 사라진다(다음 인증 성공 기회에 수렴). 트랜잭션 밖 호출은 즉시 실행한다.
     */
    fun evaluate(user: User) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        evaluateNow(user)
                    }
                },
            )
        } else {
            evaluateNow(user)
        }
    }

    private fun evaluateNow(user: User) {
        try {
            val userId = user.id ?: return
            val domain =
                user.email
                    .substringAfterLast('@', "")
                    .trim()
                    .lowercase()
            if (domain.isEmpty() || domain in OrgDomainService.PUBLIC_EMAIL_DOMAINS) return
            val joinedOrgId: UUID? =
                autoJoinTransaction.execute {
                    // 검증된 클레임만 판정 대상 — 부분 유니크(uq_org_domain_verified)로 최대 1행.
                    val orgDomain = orgDomainRepository.findByDomainAndVerifiedTrue(domain) ?: return@execute null
                    val org = organizationRepository.findById(orgDomain.orgId).orElse(null) ?: return@execute null
                    if (!org.autoJoinEnabled || org.statusEnum() != OrgStatus.ACTIVE) return@execute null
                    // MEMBER 고정 + 실제 생성 시에만 JOINED 이력(멱등 재평가는 no-op — 이력·감사 미기록).
                    if (organizationService.ensureJitMembership(org.id!!, userId)) org.id else null
                }
            if (joinedOrgId != null) {
                auditEventService.record(
                    "ORG_AUTO_JOINED",
                    userId,
                    joinedOrgId,
                    mapOf("orgId" to joinedOrgId.toString(), "domain" to domain),
                )
            }
        } catch (ex: Exception) {
            // 자동 가입 실패가 로그인/인증을 깨면 안 된다 — 경합·일시 오류는 다음 평가 기회에 수렴한다.
            log.warn("org auto-join evaluation failed for user={}", user.id, ex)
        }
    }
}
