package com.taspa.server.stepup

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * Step-up 재인증(B-3) 통합 테스트. 시간 경과는 세션 저장소의 속성(TASPA_AUTH_TIME) 직접 조작으로
 * 시뮬레이션한다(WebSession.setAttribute — JDBC 세션 저장소에 바로 쓴다).
 */
class StepUpIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "stepup@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        loginEventRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
        every { mailSender.send(any<org.springframework.mail.SimpleMailMessage>()) } just Runs
    }

    @Test
    fun `stale auth time blocks sensitive api until password reauth`() {
        val session = login()

        // 로그인 직후에는 auth_time 이 신선하다 → 민감 API 접근 가능.
        session
            .perform(post("/api/mfa/setup"))
            .andExpect(status().isOk)
        session
            .perform(get("/api/reauth/check"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reauthRequired").value(false))

        // 임계 초과 시뮬레이션.
        expireAuthTime(session)

        session
            .perform(post("/api/mfa/setup"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
        session
            .perform(get("/api/reauth/check"))
            .andExpect(jsonPath("$.reauthRequired").value(true))

        // 잘못된 비밀번호 → 재인증 실패(페이지 재렌더), 여전히 차단.
        session
            .perform(
                post("/reauth")
                    .param("password", "WrongP@ssword1")
                    .param("continue", "/account")
                    .with(csrf()),
            ).andExpect(status().isOk)
        session
            .perform(post("/api/mfa/setup"))
            .andExpect(status().isUnauthorized)

        // 올바른 비밀번호로 재인증 → continue 복귀 + auth_time 갱신 → 다시 접근 가능.
        session
            .perform(
                post("/reauth")
                    .param("password", password)
                    .param("continue", "/account")
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        session
            .perform(post("/api/mfa/setup"))
            .andExpect(status().isOk)
    }

    @Test
    fun `webauthn registration endpoints are gated by the step-up filter`() {
        val session = login()

        // 신선한 세션은 통과(옵션 발급 200).
        session
            .perform(post("/webauthn/register/options").with(csrf()))
            .andExpect(status().isOk)

        expireAuthTime(session)

        session
            .perform(post("/webauthn/register/options").with(csrf()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
        session
            .perform(post("/webauthn/register").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    @Test
    fun `reauth continue path is sanitized against open redirect`() {
        val session = login()
        expireAuthTime(session)

        session
            .perform(
                post("/reauth")
                    .param("password", password)
                    .param("continue", "https://evil.example.com/phish")
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
    }

    // ---- helpers ----

    private fun login(): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", password)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
        return session
    }

    private fun expireAuthTime(session: WebSession) {
        session.setAttribute(
            StepUp.AUTH_TIME_SESSION_KEY,
            Instant.now().minus(Duration.ofMinutes(11)),
        )
    }
}
