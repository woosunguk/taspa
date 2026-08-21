package com.taspa.server.passkey

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.login.LoginEventService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.savedrequest.RequestCache
import org.springframework.security.web.savedrequest.SavedRequest
import java.util.UUID

/**
 * 패스키 성공 응답 회귀 테스트.
 * 배경: 기본 HttpMessageConverterAuthenticationSuccessHandler 는 saved request 가 없으면 "/" 로
 * 보내고(앱에 매핑 없음 → 404) 감사 로그·잠금 카운터 리셋도 없다. 커스텀 핸들러가
 * {"redirectUrl": ..., "authenticated": true} 계약을 유지하며 이를 보정해야 한다.
 */
class PasskeyAuthenticationSuccessHandlerTest {
    private val requestCache = mockk<RequestCache>(relaxed = true)
    private val userRepository = mockk<UserRepository>()
    private val accountLockoutService = mockk<AccountLockoutService>(relaxed = true)
    private val auditEventService = mockk<AuditEventService>(relaxed = true)
    private val loginEventService = mockk<LoginEventService>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val handler =
        PasskeyAuthenticationSuccessHandler(
            requestCache,
            userRepository,
            accountLockoutService,
            auditEventService,
            loginEventService,
            objectMapper,
        )

    private val email = "passkey-user@example.com"
    private val authentication = mockk<Authentication> { every { name } returns email }

    @Test
    fun `falls back to account page when there is no saved request`() {
        // 핸들러는 리포지토리에서 로드된(영속) 사용자를 전제로 하므로 id 를 부여한다.
        val user = User(id = UUID.randomUUID(), email = email, passwordHash = "hash")
        every { userRepository.findByEmail(email) } returns user
        every { requestCache.getRequest(any(), any()) } returns null
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(MockHttpServletRequest(), response, authentication)

        val json = objectMapper.readTree(response.contentAsString)
        assertThat(json["redirectUrl"].asText()).isEqualTo("/account")
        assertThat(json["authenticated"].asBoolean()).isTrue()
        verify { accountLockoutService.recordSuccessfulLogin(user) }
        verify { auditEventService.record("LOGIN_SUCCESS", user.id, any()) }
    }

    @Test
    fun `redirects to the saved request for oidc continuation and consumes it`() {
        every { userRepository.findByEmail(email) } returns
            User(id = UUID.randomUUID(), email = email, passwordHash = "hash")
        val authorizeUrl = "http://localhost:9100/oauth2/authorize?client_id=demo"
        val savedRequest = mockk<SavedRequest> { every { redirectUrl } returns authorizeUrl }
        every { requestCache.getRequest(any(), any()) } returns savedRequest
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(MockHttpServletRequest(), response, authentication)

        val json = objectMapper.readTree(response.contentAsString)
        assertThat(json["redirectUrl"].asText()).isEqualTo(authorizeUrl)
        assertThat(json["authenticated"].asBoolean()).isTrue()
        verify { requestCache.removeRequest(any(), any()) }
    }
}
