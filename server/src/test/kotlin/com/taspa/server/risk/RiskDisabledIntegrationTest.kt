package com.taspa.server.risk

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
 * taspa.risk.enabled=false(테스트 기본값 — application-test.yml)면 강한 신호(신규 기기 +
 * 실패 누적)에도 챌린지·경고 없이 기존 동작 그대로다.
 */
class RiskDisabledIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "risk-disabled@example.com"
    private val password = "SecureP@ssw0rd123"
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
    fun `disabled risk keeps legacy behavior even with strong signals`() {
        // HIGH 급 신호를 만든다: 로그인 이력 없음(신규 기기) + 실패 3회.
        repeat(3) {
            val session = webSession()
            session
                .perform(post("/login/identifier").param("email", email).with(csrf()))
                .andExpect(redirectedUrl("/login/password"))
            session
                .perform(
                    post("/login/password").param("username", email).param("password", "Wrong-$password").with(csrf()),
                ).andExpect(redirectedUrl("/login/password?error"))
        }

        // 비활성화 상태에서는 챌린지 없이 기존 동작(바로 완전 인증).
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password").param("username", email).param("password", password).with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))

        assertThat(sentMessages.map { it.subject }).doesNotContain(
            "[taspa] 보안 경고: 의심스러운 로그인이 감지되었습니다",
            "[taspa] 이메일 인증 코드",
        )
        assertThat(userRepository.findByEmail(email)!!.failedLoginAttempts).isEqualTo(0)
    }
}
