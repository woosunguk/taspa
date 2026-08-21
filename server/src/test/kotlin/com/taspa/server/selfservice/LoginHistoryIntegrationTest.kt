package com.taspa.server.selfservice

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.login.LoginEvent
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * 최근 로그인 활동(Stage 7) 통합 테스트. login_events 를 사용자별 최신순 최근 N건만 읽어 노출하며,
 * 다른 사용자의 이력이 섞이지 않는다(읽기 전용, 세션 목록과 별개).
 */
class LoginHistoryIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "history@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        loginEventRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        userRepository.save(User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true))
    }

    @Test
    fun `login history returns recent events newest-first capped at limit`() {
        val user = userRepository.findByEmail(email)!!
        val base = Instant.now().minus(Duration.ofDays(1))
        // 상한(10)을 넘기려 12건을 과거로 삽입한다.
        (1..12).forEach { i ->
            loginEventRepository.save(
                LoginEvent(
                    userId = user.id!!,
                    ip = "10.0.0.$i",
                    uaLabel = "Chrome / macOS",
                    method = "password",
                    createdAt = base.minus(Duration.ofMinutes(i.toLong())),
                ),
            )
        }

        val session = login()
        val events =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/login-history"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )

        // N=10 상한.
        assertThat(events).hasSize(10)
        // occurredAt 내림차순(최신순) — 방금 로그인한 실시간 이벤트가 첫 항목.
        val times = events.map { Instant.parse(it.get("occurredAt").asText()) }
        assertThat(times).isSortedAccordingTo(Comparator.reverseOrder())
        // 필드 노출: 시각·method·ip·device.
        val first = events.first()
        assertThat(first.get("method").asText()).isEqualTo("password")
        assertThat(first.has("ip")).isTrue()
        assertThat(first.has("device")).isTrue()
    }

    @Test
    fun `login history is isolated per user`() {
        val other =
            userRepository.save(
                User(email = "other@example.com", passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        loginEventRepository.save(
            LoginEvent(userId = other.id!!, ip = "8.8.8.8", uaLabel = "OtherDevice", method = "passkey"),
        )

        val session = login()
        val events =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/login-history"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )

        // 다른 사용자의 이벤트(OtherDevice)가 절대 섞이지 않는다.
        assertThat(events.map { it.get("device").asText() }).doesNotContain("OtherDevice")
    }

    private fun login(): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
