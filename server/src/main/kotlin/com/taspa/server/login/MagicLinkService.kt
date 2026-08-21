package com.taspa.server.login

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.credential.MagicLinkToken
import com.taspa.server.domain.credential.MagicLinkTokenRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.mail.MailService
import com.taspa.server.org.OrgAutoJoinService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 매직 링크(이메일 로그인) — B-4.
 *
 * - 요청: 미존재/비활성 이메일이어도 호출부는 항상 동일 화면을 보여준다(열거 공격 방지). 60초 재발급 제한.
 * - GET 랜딩은 토큰을 소비하지 않는다(peek) — 이메일 스캐너의 선클릭 방지.
 * - POST 확정(consume): 해시 조회 + 15분 만료 + 단일 사용(used_at). 클릭한 브라우저에서만 로그인이 성립하며
 *   이메일 소유가 증명되므로 미검증 계정은 검증 처리된다. MFA 게이트는 유지된다(호출부에서 requiredGate).
 */
@Service
class MagicLinkService(
    private val userRepository: UserRepository,
    private val magicLinkTokenRepository: MagicLinkTokenRepository,
    private val mailService: MailService,
    private val auditEventService: AuditEventService,
    private val orgAutoJoinService: OrgAutoJoinService,
    @Value("\${taspa.magic-link.token-expiry-minutes:15}")
    private val tokenExpiryMinutes: Long,
    @Value("\${taspa.magic-link.resend-interval-seconds:60}")
    private val resendIntervalSeconds: Long,
    @Value("\${taspa.magic-link.base-url:http://localhost:9100}")
    private val baseUrl: String,
) {
    /** 계정이 존재·활성이고 재발급 제한에 걸리지 않으면 링크를 발송한다. 호출부 응답은 결과와 무관하게 동일해야 한다. */
    @Transactional
    fun request(email: String) {
        val user = userRepository.findByEmail(email) ?: return
        if (user.status != UserStatus.ACTIVE.name) {
            return
        }
        val latest = magicLinkTokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.id!!)
        if (latest != null && latest.createdAt.isAfter(Instant.now().minusSeconds(resendIntervalSeconds))) {
            return
        }

        val rawToken = SecureTokenGenerator.generateToken()
        magicLinkTokenRepository.save(
            MagicLinkToken(
                userId = user.id,
                tokenHash = SecureTokenGenerator.hashToken(rawToken),
                expiresAt = Instant.now().plusSeconds(tokenExpiryMinutes * 60),
            ),
        )
        mailService.sendMagicLink(user.email, "$baseUrl/login/magic?token=$rawToken", tokenExpiryMinutes)
        auditEventService.record("MAGIC_LINK_SENT", user.id, mapOf("email" to user.email))
    }

    /** GET 랜딩용: 소비하지 않고 유효하면 대상 사용자를 돌려준다. */
    @Transactional(readOnly = true)
    fun peek(rawToken: String): User? {
        val token = findValid(rawToken) ?: return null
        return activeUserOf(token.userId)
    }

    /** POST 확정용: used_at 마킹(단일 사용)과 이메일 검증 마킹을 같은 트랜잭션으로 처리한다. */
    @Transactional
    fun consume(rawToken: String): User? {
        val token = findValid(rawToken) ?: return null
        val user = activeUserOf(token.userId) ?: return null

        // 단일 사용 보증: used_at IS NULL 조건부 UPDATE 로 원자적으로 마킹한다.
        // read-then-write 였다면 동시 POST 2건이 모두 성공할 수 있다(READ_COMMITTED 경쟁).
        if (magicLinkTokenRepository.markUsed(token.id!!, Instant.now()) != 1) {
            return null
        }

        if (!user.emailVerified) {
            // pre-hijacking 방어: 이메일 소유 증명 전에 설정된 비밀번호는 선점 가입(공격자)일 수 있다.
            // 매직 링크는 비밀번호를 바꾸지 않고 계정에 입장시키므로, 여기서 무효화하지 않으면
            // 선점자의 비밀번호가 검증 완료 후에도 유효하게 남는다. 실소유자는 비밀번호 재설정
            // (이메일 소유만으로 가능)으로 언제든 새 비밀번호를 만들 수 있다.
            if (user.passwordHash != null) {
                user.passwordHash = null
                auditEventService.record(
                    "PASSWORD_INVALIDATED",
                    user.id,
                    mapOf("email" to user.email, "reason" to "set-before-email-verification"),
                )
            }
            // 링크 클릭 = 이메일 소유 증명 → 미검증 계정도 검증 처리한다.
            user.emailVerified = true
            userRepository.save(user)
            auditEventService.record("EMAIL_VERIFIED", user.id, mapOf("email" to user.email, "via" to "magic-link"))
            // 이메일 인증 성공 전이 — 자동 조직 가입 판정(실패 비전파, 멱등).
            orgAutoJoinService.evaluate(user)
        }
        auditEventService.record("MAGIC_LINK_CONSUMED", user.id, mapOf("email" to user.email))
        return user
    }

    private fun findValid(rawToken: String): MagicLinkToken? {
        val token = magicLinkTokenRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken)) ?: return null
        if (token.usedAt != null || token.isExpired()) {
            return null
        }
        return token
    }

    private fun activeUserOf(userId: UUID): User? {
        val user = userRepository.findById(userId).orElse(null) ?: return null
        return if (user.status == UserStatus.ACTIVE.name) user else null
    }
}
