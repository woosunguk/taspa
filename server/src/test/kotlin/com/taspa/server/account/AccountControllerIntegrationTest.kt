package com.taspa.server.account

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class AccountControllerIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val validEmail = "user@example.com"
    private val validPassword = "SecureP@ssw0rd123"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        emailVerificationCodeRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    @Test
    fun `signup with valid data returns 201 and persists the account`() {
        val request = mapOf("email" to validEmail, "password" to validPassword)

        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value(validEmail))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.emailVerified").value(false))

        val user = userRepository.findByEmail(validEmail)
        assertThat(user).isNotNull
        assertThat(passwordEncoder.matches(validPassword, user!!.passwordHash)).isTrue()
    }

    @Test
    fun `signup with duplicate email returns 409`() {
        val request = mapOf("email" to validEmail, "password" to validPassword)

        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated)

        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"))
    }

    @Test
    fun `signup with email over 100 chars returns 400 - session PRINCIPAL_NAME limit`() {
        // RFC 상 유효(로컬 64자 + 도메인)하지만 100자 초과 — 허용하면 세션 커밋(PRINCIPAL_NAME
        // VARCHAR(100) INSERT) 시점에 SQL 오류로 해당 계정이 영구 로그인 불가가 된다.
        val longEmail = "a".repeat(64) + "@" + "b".repeat(40) + ".example.com"
        assertThat(longEmail.length).isGreaterThan(100)
        val request = mapOf("email" to longEmail, "password" to validPassword)

        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        assertThat(userRepository.findByEmail(longEmail)).isNull()
    }

    /**
     * ★가입은 **모든 경로에서** 인증 코드를 발송해야 한다.
     *
     * 한동안 서버 렌더링 `/signup` 만 발송했고 이 공개 JSON API 는 하지 않았다. 그 경로로 가입한
     * 사용자는 로그인하는 순간 "…으로 보낸 6자리 코드를 입력하세요" 화면에 도착하는데 **메일이 한 통도
     * 가지 않은** 상태였다(prod 리허설에서 실제로 재현 — 화면은 존재하지 않는 메일을 기다리라고 말한다).
     * e2e 가 전부 HTML `/signup` 을 쓰는 바람에 아무 테스트도 이 경로를 밟지 않았다.
     */
    @Test
    fun `signup sends the verification code - API 경로도 포함`() {
        val request = mapOf("email" to validEmail, "password" to validPassword)

        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated)

        assertThat(sentMessages).hasSize(1)
        assertThat(sentMessages.single().to).containsExactly(validEmail)
        // 코드 행이 실제로 저장돼야 검증 화면이 통과할 수 있다(메일만 나가고 행이 없으면 영영 실패한다).
        val user = userRepository.findByEmail(validEmail)!!
        assertThat(emailVerificationCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(user.id!!)).isNotNull
    }

    /** 가입이 거절되면 코드도 나가지 않는다(대조군 — 위 단언이 "항상 1통"으로 통과하지 않게). */
    @Test
    fun `rejected signup sends no mail`() {
        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("email" to validEmail, "password" to "weak"))),
            ).andExpect(status().isBadRequest)

        assertThat(sentMessages).isEmpty()
    }

    @Test
    fun `signup with weak password returns 400`() {
        val request = mapOf("email" to validEmail, "password" to "weak")

        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PASSWORD_POLICY_VIOLATION"))
    }
}
