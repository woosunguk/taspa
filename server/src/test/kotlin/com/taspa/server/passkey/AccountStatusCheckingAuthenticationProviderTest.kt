package com.taspa.server.passkey

import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.LockedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UsernameNotFoundException

/**
 * 패스키 인증 후처리 상태 검사 회귀 테스트.
 * 배경: SS 6.4.4 WebAuthnAuthenticationProvider 는 enabled/locked 를 검사하지 않으므로
 * 래퍼가 SUSPENDED·잠금 계정을 차단해야 한다(비밀번호 경로와 통제 일치).
 */
class AccountStatusCheckingAuthenticationProviderTest {
    private val delegate = mockk<AuthenticationProvider>()
    private val userRepository = mockk<UserRepository>()
    private val accountLockoutService = mockk<AccountLockoutService>()
    private val auditEventService = mockk<AuditEventService>(relaxed = true)
    private val provider =
        AccountStatusCheckingAuthenticationProvider(
            delegate,
            userRepository,
            accountLockoutService,
            auditEventService,
        )

    private val email = "passkey-user@example.com"
    private val request = mockk<Authentication>()
    private val result = mockk<Authentication> { every { name } returns email }

    private fun user(status: UserStatus): User = User(email = email, passwordHash = "hash", status = status.name)

    @Test
    fun `active unlocked user passes through unchanged`() {
        val user = user(UserStatus.ACTIVE)
        every { delegate.authenticate(request) } returns result
        every { userRepository.findByEmail(email) } returns user
        every { accountLockoutService.isAccountLocked(user) } returns false

        assertThat(provider.authenticate(request)).isSameAs(result)
    }

    @Test
    fun `suspended user is rejected even with a valid assertion`() {
        every { delegate.authenticate(request) } returns result
        every { userRepository.findByEmail(email) } returns user(UserStatus.SUSPENDED)

        assertThatThrownBy { provider.authenticate(request) }
            .isInstanceOf(DisabledException::class.java)
        verify { auditEventService.record("LOGIN_FAILURE", any(), any()) }
    }

    @Test
    fun `locked user is rejected even with a valid assertion`() {
        val user = user(UserStatus.ACTIVE)
        every { delegate.authenticate(request) } returns result
        every { userRepository.findByEmail(email) } returns user
        every { accountLockoutService.isAccountLocked(user) } returns true

        assertThatThrownBy { provider.authenticate(request) }
            .isInstanceOf(LockedException::class.java)
        verify { auditEventService.record("LOGIN_FAILURE", any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { delegate.authenticate(request) } returns result
        every { userRepository.findByEmail(email) } returns null

        assertThatThrownBy { provider.authenticate(request) }
            .isInstanceOf(UsernameNotFoundException::class.java)
    }

    @Test
    fun `supports delegates to the wrapped provider`() {
        every { delegate.supports(Authentication::class.java) } returns true

        assertThat(provider.supports(Authentication::class.java)).isTrue()
        verify { delegate.supports(Authentication::class.java) }
    }
}
