package com.taspa.server.federation

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.config.i18n.MessageResolver
import com.taspa.server.domain.federation.FederatedIdentity
import com.taspa.server.domain.federation.FederatedIdentityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.federation.dto.FederationResponse
import com.taspa.server.mail.MailService
import com.taspa.server.passkey.PasskeyService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 연합 신원(federated identity) 연결/해제/조회.
 */
@Service
class FederationService(
    private val federatedIdentityRepository: FederatedIdentityRepository,
    private val userRepository: UserRepository,
    private val passkeyService: PasskeyService,
    private val mailService: MailService,
    private val auditEventService: AuditEventService,
    private val messages: MessageResolver,
) {
    @Transactional(readOnly = true)
    fun findIdentity(
        provider: String,
        providerUserId: String,
    ): FederatedIdentity? = federatedIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)

    /**
     * 기업 SSO 커넥션 스코프 신원 조회(계정 탈취 방지). 조직 로그인은 반드시 현재 커넥션 id 로 스코프해
     * 조회한다 — registration_id 재사용(삭제 후 재생성)으로 남은 옛 신원(connection_id=NULL)이 새 조직
     * 커넥션에 상속되지 않게 한다.
     */
    @Transactional(readOnly = true)
    fun findIdentityForConnection(
        provider: String,
        providerUserId: String,
        connectionId: UUID,
    ): FederatedIdentity? =
        federatedIdentityRepository.findByProviderAndProviderUserIdAndConnectionId(
            provider,
            providerUserId,
            connectionId,
        )

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<FederationResponse> =
        federatedIdentityRepository
            .findByUserId(userId)
            .sortedBy { it.createdAt }
            .map {
                FederationResponse(
                    provider = it.provider,
                    providerLabel = SocialProviders.label(it.provider),
                    emailAtLink = it.emailAtLink,
                    createdAt = it.createdAt,
                )
            }

    @Transactional(readOnly = true)
    fun linkedProviders(userId: UUID): Set<String> = federatedIdentityRepository.findByUserId(userId).map { it.provider }.toSet()

    /**
     * 경합 안전 연결. UNIQUE(provider, provider_user_id) 는 check-then-insert 사이에 다른 요청
     * (같은 소셜 계정 동시 첫 로그인, 연결-추가 동시 수행)이 먼저 INSERT 하면 위반된다 —
     * 이때 예외를 사용자에게 노출하는 대신 재조회로 수렴한다.
     *
     * @return 신원이 이 userId 로 연결돼 있으면 true(방금 연결했거나 이미 연결돼 있던 멱등 성공),
     *         다른 계정에 선점돼 있으면 false — 호출부가 실패 경로(error=social / linkError=inuse)로 보낸다.
     */
    fun linkOrConverge(
        userId: UUID,
        attributes: SocialAttributes,
    ): Boolean =
        try {
            // saveAndFlush: 제약 위반을 이 호출 안에서 확정적으로 발생시켜 잡는다(리포지토리 자체 트랜잭션).
            federatedIdentityRepository.saveAndFlush(
                FederatedIdentity(
                    userId = userId,
                    provider = attributes.provider,
                    providerUserId = attributes.providerUserId,
                    emailAtLink = attributes.email,
                    connectionId = attributes.connectionId,
                ),
            )
            auditEventService.record(
                "FEDERATION_LINKED",
                userId,
                mapOf("provider" to attributes.provider, "providerUserId" to attributes.providerUserId),
            )
            true
        } catch (ex: DataIntegrityViolationException) {
            federatedIdentityRepository
                .findByProviderAndProviderUserId(attributes.provider, attributes.providerUserId)
                ?.userId == userId
        }

    /** 소셜 신원 기반 신규 계정 생성 — 비밀번호 없음(password_hash NULL). 이메일은 소문자로 정규화 저장. */
    @Transactional
    fun createSocialUser(
        email: String,
        attributes: SocialAttributes,
    ): User {
        val user =
            userRepository.save(
                User(
                    email = email.trim().lowercase(),
                    passwordHash = null,
                    displayName = attributes.displayName?.trim()?.takeIf { it.isNotEmpty() },
                    emailVerified = attributes.emailVerifiedByProvider,
                ),
            )
        auditEventService.record(
            "SIGNUP",
            user.id,
            mapOf("email" to user.email, "method" to "social:${attributes.provider}"),
        )
        return user
    }

    /**
     * 연결 해제. 잔여 로그인 수단 검증: 비밀번호 + 패스키 + 남은 소셜 연결이 하나도 없으면
     * 계정이 잠기는 것과 같으므로 409 로 거부한다. 해제 시 알림 메일 + 감사 기록.
     *
     * 검증-삭제 TOCTOU 방지: 먼저 사용자 행을 PESSIMISTIC_WRITE 로 잠가 같은 사용자의 수단 변경
     * (소셜 2개 동시 해제, 마지막 패스키 삭제와의 교차)을 직렬화한다 — 그렇지 않으면 두 요청이
     * 각자 스냅샷 검증을 통과해 로그인 수단 0개 계정이 만들어질 수 있다.
     */
    @Transactional
    fun unlink(
        userId: UUID,
        provider: String,
    ) {
        val user = userRepository.findByIdForUpdate(userId) ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
        val identities = federatedIdentityRepository.findByUserIdAndProvider(userId, provider)
        if (identities.isEmpty()) {
            throw AuthException(ErrorCode.NOT_FOUND)
        }

        val remainingSocial = federatedIdentityRepository.countByUserId(userId) - identities.size
        val hasPassword = user.passwordHash != null
        val hasPasskeys = passkeyService.hasPasskeys(userId)
        if (!hasPassword && !hasPasskeys && remainingSocial < 1) {
            // account.js.unlinkLastMethod 와 동일 문구(ko 바이트 동일) — 로케일화해 재사용한다.
            throw AuthException(
                ErrorCode.LAST_LOGIN_METHOD,
                messages.get("account.js.unlinkLastMethod"),
            )
        }

        federatedIdentityRepository.deleteAll(identities)
        auditEventService.record("FEDERATION_UNLINKED", userId, mapOf("provider" to provider))
        mailService.sendFederationUnlinkedNotice(user.email, SocialProviders.label(provider))
    }
}
