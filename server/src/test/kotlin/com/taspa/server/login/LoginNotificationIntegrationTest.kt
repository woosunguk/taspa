package com.taspa.server.login

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 로그인 알림(B-2) 통합 테스트: 신규 기기 첫 로그인 알림 + 같은 (ip, ua) 재로그인 재발송 억제.
 */
class LoginNotificationIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "notify@example.com"
    private val password = "SecureP@ssw0rd123"
    private val chromeMacUa =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val firefoxWinUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        loginEventRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    @Test
    fun `first login from a new device sends a notice and repeat logins are suppressed`() {
        // 1) 신규 기기 첫 로그인 → 알림 1통 (기기/IP 정보 포함).
        login(chromeMacUa)
        val notices = sentMessages.filter { it.subject == "[taspa] 새 로그인이 감지되었습니다" }
        assertThat(notices).hasSize(1)
        assertThat(notices.first().text).contains("Chrome / macOS")
        assertThat(notices.first().text).contains("127.0.0.1")

        // 2) 같은 (ip, ua) 재로그인 → 재발송 억제(이벤트 이력 기반 — 24시간/30일 내 중복 없음).
        login(chromeMacUa)
        assertThat(sentMessages.count { it.subject == "[taspa] 새 로그인이 감지되었습니다" }).isEqualTo(1)

        // 3) 다른 기기(ua 변경) 로그인 → 새 알림.
        login(firefoxWinUa)
        assertThat(sentMessages.count { it.subject == "[taspa] 새 로그인이 감지되었습니다" }).isEqualTo(2)

        // 이벤트는 로그인마다 기록된다(method=password).
        val events = loginEventRepository.findAll()
        assertThat(events).hasSize(3)
        assertThat(events.map { it.method }.toSet()).containsExactly("password")
        assertThat(events.map { it.uaLabel }.toSet()).contains("Chrome / macOS", "Firefox / Windows")
    }

    private fun login(userAgent: String) {
        val session = webSession()
        session
            .perform(
                post("/login/identifier")
                    .param("email", email)
                    .header("User-Agent", userAgent)
                    .with(csrf()),
            ).andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", password)
                    .header("User-Agent", userAgent)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
    }
}
