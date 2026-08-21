package com.taspa.server.credential

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.credential.PasswordResetTokenRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class PasswordResetIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "reset@example.com"
    private val oldPassword = "OldSecureP@ss1"
    private val newPassword = "NewSecureP@ss2"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        passwordResetTokenRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(oldPassword), emailVerified = true),
        )
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    @Test
    fun `reset request emails a token that sets a new usable password`() {
        // 1) 재설정 요청 → 메일로 토큰 발송
        mockMvc
            .perform(post("/password-reset").param("email", email).with(csrf()))
            .andExpect(status().isOk)

        val token = extractToken()

        // 2) 새 비밀번호 설정
        mockMvc
            .perform(
                post("/password-reset/confirm")
                    .param("token", token)
                    .param("newPassword", newPassword)
                    .with(csrf()),
            ).andExpect(redirectedUrl("/login?reset=true"))

        // 3) 새 비밀번호로 로그인 성공(에러 리다이렉트가 아니어야 함)
        val result =
            webSession()
                .perform(
                    post("/login/password")
                        .param("username", email)
                        .param("password", newPassword)
                        .with(csrf()),
                ).andReturn()
        val location = result.response.redirectedUrl ?: ""
        assertThat(location).doesNotContain("error")

        assertThat(passwordEncoder.matches(newPassword, userRepository.findByEmail(email)!!.passwordHash)).isTrue()
    }

    /**
     * ★**정지된 계정은 재설정이 완료되지 않고, 그 사실을 말한다.**
     *
     * 그전에는 재설정이 정상 완료되고 사용자는 방금 자기가 정한 비밀번호로 로그인했다가
     * "이메일 또는 비밀번호가 올바르지 않습니다"를 봤다 — 그래서 다시 재설정하고 또 같은 곳으로
     * 돌아온다(무한 루프이고 원인은 화면 어디에도 없다).
     *
     * ★로그인 화면 쪽은 **반대로 두어야 한다**: 거기서 정지 사실을 말하면 비밀번호 검증 전에
     * 던져지는 사전 검사라 계정 열거가 된다. 여기는 메일 토큰으로 소유가 증명된 지점이라 다르다.
     * 아래 대조군이 그 비대칭을 함께 고정한다.
     */
    @Test
    fun `정지된 계정은 재설정을 완료하지 못하고 사유를 받는다`() {
        val user = userRepository.findByEmail(email)!!
        user.status = UserStatus.SUSPENDED.name
        userRepository.save(user)

        mockMvc
            .perform(post("/password-reset").param("email", email).with(csrf()))
            .andExpect(status().isOk)
        val token = extractToken()

        mockMvc
            .perform(
                post("/password-reset/confirm")
                    .param("token", token)
                    .param("newPassword", newPassword)
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(content().string(containsString("정지된 계정")))

        // 비밀번호는 바뀌지 않았다 — 실패를 성공처럼 보이게 두지 않는다.
        assertThat(passwordEncoder.matches(newPassword, userRepository.findByEmail(email)!!.passwordHash)).isFalse()
    }

    /** 대조군 — 로그인 화면은 정지 사실을 **말하지 않는다**(계정 열거 방어). */
    @Test
    fun `로그인 화면은 정지 사실을 드러내지 않는다`() {
        val user = userRepository.findByEmail(email)!!
        user.status = UserStatus.SUSPENDED.name
        userRepository.save(user)

        val result =
            webSession()
                .perform(
                    post("/login/password")
                        .param("username", email)
                        .param("password", oldPassword)
                        .with(csrf()),
                ).andReturn()
        // 잠금(locked)과도 구분되지 않는 일반 실패로 수렴한다.
        assertThat(result.response.redirectedUrl).isEqualTo("/login/password?error")
    }

    private fun extractToken(): String {
        val body = sentMessages.last().text ?: ""
        return Regex("token=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }
}
