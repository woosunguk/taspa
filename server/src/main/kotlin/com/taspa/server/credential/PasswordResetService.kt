package com.taspa.server.credential

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.credential.PasswordResetToken
import com.taspa.server.domain.credential.PasswordResetTokenRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.session.SessionManagementService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class PasswordResetService(
    private val userRepository: UserRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val passwordPolicyService: PasswordPolicyService,
    private val auditEventService: AuditEventService,
    private val trustedDeviceService: TrustedDeviceService,
    private val sessionManagementService: SessionManagementService,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${taspa.password-reset.token-expiry-minutes:30}")
    private val tokenExpiryMinutes: Long,
    @Value("\${taspa.password-reset.resend-interval-seconds:60}")
    private val resendIntervalSeconds: Long,
) {
    /**
     * 계정이 존재하면 (원본 토큰)을 반환한다. 존재하지 않으면 null(열거 공격 방지를 위해 호출부에서 항상 성공 응답).
     * 최근 발급 간격(기본 60초) 내면 재발급을 생략하고 null 을 반환한다 — magic-link 와 동일한 스로틀로
     * 이메일 폭탄 + 토큰 행 무한 증식을 막는다(호출부 응답은 결과와 무관하게 동일해야 한다).
     */
    @Transactional
    fun createResetToken(email: String): String? {
        val user = userRepository.findByEmail(email) ?: return null

        val latest = passwordResetTokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.id!!)
        if (latest != null && latest.createdAt.isAfter(Instant.now().minusSeconds(resendIntervalSeconds))) {
            return null
        }

        val rawToken = SecureTokenGenerator.generateToken()
        passwordResetTokenRepository.save(
            PasswordResetToken(
                userId = user.id!!,
                tokenHash = SecureTokenGenerator.hashToken(rawToken),
                expiresAt = Instant.now().plusSeconds(tokenExpiryMinutes * 60),
            ),
        )
        return rawToken
    }

    /**
     * 이 링크가 **지금 쓸 수 있는가**(비밀번호를 받기 전에 미리 본다).
     *
     * ★그전에는 GET 단계에서 토큰을 전혀 보지 않아, **이미 죽은 링크로도 비밀번호 입력 폼이 정상처럼
     * 열렸다.** 사용자는 새 비밀번호를 고민해 입력하고 나서야 "만료됐다"는 말을 듣는다(그마저도 영문
     * 이었다). 헛수고를 시킨 뒤 알려 주는 것과 미리 알려 주는 것은 다른 제품이다.
     *
     * **읽기만 하고 소비하지 않는다** — 매직 링크와 같은 이유로, 메일 스캐너의 선클릭이 링크를 태워서는
     * 안 된다. 존재 여부만 보므로 계정 열거에도 쓰이지 않는다(토큰을 이미 가진 사람만 물을 수 있다).
     */
    @Transactional(readOnly = true)
    fun tokenUsable(rawToken: String): Boolean {
        val resetToken = passwordResetTokenRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))
        return resetToken != null && !resetToken.used && !resetToken.isExpired()
    }

    /**
     * 토큰 소비·비밀번호 변경·신뢰 기기 폐기는 한 트랜잭션으로 커밋하고, 세션 폐기는 커밋 **이후**
     * 수행한다. spring-session-jdbc 의 저장소 연산은 REQUIRES_NEW 트랜잭션이라(3.4.2 실측) 외부
     * JPA 트랜잭션 안에서 호출하면 요청당 커넥션 2개를 점유해, 동시 재설정이 풀 크기(10)에 닿으면
     * 전원이 두 번째 커넥션을 대기하는 풀 고갈 정체가 된다. 커밋 후로 옮기면 첫 커넥션이 반납된
     * 뒤에만 세션 폐기가 실행되고, 커밋 실패 시 세션만 먼저 날아가는 비대칭도 사라진다.
     */
    fun resetPassword(
        rawToken: String,
        newPassword: String,
    ) {
        val user =
            requireNotNull(
                transactionTemplate.execute {
                    val tokenHash = SecureTokenGenerator.hashToken(rawToken)
                    val resetToken =
                        passwordResetTokenRepository.findByTokenHash(tokenHash)
                            ?: throw AuthException(ErrorCode.RESET_TOKEN_INVALID)

                    if (resetToken.used) {
                        throw AuthException(ErrorCode.RESET_TOKEN_INVALID)
                    }
                    if (resetToken.isExpired()) {
                        throw AuthException(ErrorCode.RESET_TOKEN_EXPIRED)
                    }

                    val violations = passwordPolicyService.validate(newPassword)
                    if (violations.isNotEmpty()) {
                        throw AuthException(ErrorCode.PASSWORD_POLICY_VIOLATION, violations.joinToString("; "))
                    }

                    val user =
                        userRepository
                            .findById(resetToken.userId)
                            .orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }

                    /*
                     * ★**정지된 계정은 여기서 멈추고 그 사실을 말한다.**
                     *
                     * 그전에는 재설정이 정상적으로 완료되고, 사용자는 새 비밀번호로 로그인했다가
                     * "이메일 또는 비밀번호가 올바르지 않습니다"를 본다 — 방금 자기가 정한 비밀번호인데도.
                     * 그래서 다시 재설정하고, 또 같은 곳으로 돌아온다(무한 루프이고 원인은 어디에도 없다).
                     *
                     * ★**로그인 화면의 문구는 반대로 바꾸면 안 된다**(같은 결함의 다른 절반이지만 해법이
                     * 정반대다): `DisabledException` 은 스프링의 **사전 검사**라 비밀번호 검증 **전에**
                     * 던져진다. 거기서 "정지된 계정"이라고 말하면 아무나 이메일만 넣어 계정의 존재와
                     * 상태를 확인할 수 있다(계정 열거).
                     *
                     * 여기는 다르다 — 이 지점에 도달하려면 **메일로 받은 토큰**을 갖고 있어야 하므로
                     * 이메일 소유가 이미 증명됐다. 소유를 증명한 사람에게 자기 계정 상태를 알리는 것은
                     * 열거가 아니다(같은 원리로 매직 링크·초대가 소유 증명 뒤에 정보를 준다).
                     */
                    if (user.status != UserStatus.ACTIVE.name) {
                        throw AuthException(ErrorCode.ACCOUNT_SUSPENDED)
                    }

                    user.passwordHash = passwordEncoder.encode(newPassword)
                    user.failedLoginAttempts = 0
                    user.lockedUntil = null
                    userRepository.save(user)

                    resetToken.used = true
                    passwordResetTokenRepository.save(resetToken)

                    // 신뢰 기기 폐기는 같은 JPA 트랜잭션에 참여한다(추가 커넥션 없음).
                    trustedDeviceService.revokeAll(user.id!!)
                    user
                },
            )

        // 비밀번호 재설정 = 자격 증명 탈취 가능성 → 모든 활성 세션 폐기(계정 탈취 대응) — 커밋 확정 후.
        sessionManagementService.revokeAll(user.id!!, user.email)
        auditEventService.record("PASSWORD_RESET", user.id, mapOf("email" to user.email))
    }
}
