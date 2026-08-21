package com.taspa.server.passkey

import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.LockedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UsernameNotFoundException

/**
 * 패스키 인증에 계정 상태 검사를 결합하는 래퍼.
 *
 * Spring Security 6.4.4 의 WebAuthnAuthenticationProvider 는 어서션 검증 후 UserDetailsService 에서
 * authorities 만 취하고 isEnabled()/isAccountNonLocked() 를 검사하지 않는다(바이트코드 실측).
 * 그대로 두면 SUSPENDED(정지)·잠금(lockedUntil) 계정이 미리 등록해 둔 패스키로 완전 인증 세션을
 * 확립할 수 있어, 비밀번호 경로(DaoAuthenticationProvider 의 pre-auth check)에서만 강제되는 통제가
 * 우회된다. 이 래퍼가 위임 인증 성공 직후 상태를 재검사해 두 경로의 통제를 일치시킨다.
 *
 * 잠금은 비밀번호 브루트포스 방어 장치라 패스키(브루트포스 불가)에는 원리상 불필요하지만,
 * "잠긴 계정은 로그인할 수 없다"는 정책 일관성을 위해 패스키 경로에서도 차단한다
 * (결정 근거: docs/architecture.md §8.3).
 */
class AccountStatusCheckingAuthenticationProvider(
    private val delegate: AuthenticationProvider,
    private val userRepository: UserRepository,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
) : AuthenticationProvider {
    override fun authenticate(authentication: Authentication): Authentication? {
        val result = delegate.authenticate(authentication) ?: return null
        val email = result.name
        val user =
            userRepository.findByEmail(email)
                ?: throw UsernameNotFoundException("User not found with email: $email")
        if (user.status != UserStatus.ACTIVE.name) {
            auditEventService.record(
                "LOGIN_FAILURE",
                user.id,
                mapOf("email" to user.email, "method" to "passkey", "reason" to "disabled"),
            )
            throw DisabledException("User account is not active")
        }
        // 만료된 잠금은 isAccountLocked 내부에서 자동 해제된다(카운터는 보존 — 리스크 신호 입력).
        if (accountLockoutService.isAccountLocked(user)) {
            auditEventService.record(
                "LOGIN_FAILURE",
                user.id,
                mapOf("email" to user.email, "method" to "passkey", "reason" to "locked"),
            )
            throw LockedException("User account is locked")
        }
        return result
    }

    override fun supports(authentication: Class<*>): Boolean = delegate.supports(authentication)
}
