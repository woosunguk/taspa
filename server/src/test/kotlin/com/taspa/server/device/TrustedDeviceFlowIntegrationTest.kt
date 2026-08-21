package com.taspa.server.device

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.credential.PasswordResetTokenRepository
import com.taspa.server.domain.device.TrustedDeviceRepository
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.mfa.BackupCodeRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mfa.MfaService
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.time.SystemTimeProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 신뢰 기기(B-1) 통합 테스트: MFA 스킵 + 사용 시 토큰 회전 + 비밀번호 재설정 시 전체 폐기.
 */
class TrustedDeviceFlowIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var trustedDeviceRepository: TrustedDeviceRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var backupCodeRepository: BackupCodeRepository

    @Autowired lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var mfaService: MfaService

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "trusted-device@example.com"
    private val password = "SecureP@ssw0rd123"
    private lateinit var totpSecret: String
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        trustedDeviceRepository.deleteAll()
        loginEventRepository.deleteAll()
        backupCodeRepository.deleteAll()
        passwordResetTokenRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs

        val user =
            userRepository.save(
                User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        val setup = mfaService.setup(user.id!!)
        totpSecret = setup.secret
        mfaService.activate(user.id!!, validTotp())
    }

    @Test
    fun `trusted device skips mfa and rotates token on each use`() {
        // 1) 첫 로그인: MFA 게이트 → 코드 + "이 기기에서 30일 동안 묻지 않음" → 쿠키 발급.
        val firstSession = webSession()
        loginToMfaGate(firstSession)
        val mfaResult =
            firstSession
                .perform(
                    post("/login/mfa")
                        .param("code", validTotp())
                        .param("trustDevice", "on")
                        .with(csrf()),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/account"))
                .andReturn()
        val issuedToken = trustedDeviceCookie(mfaResult)
        assertThat(issuedToken).isNotNull()
        assertThat(trustedDeviceRepository.findAll()).hasSize(1)

        // 2) 재로그인(쿠키 지참): MFA 게이트 없이 완전 인증 + 토큰 회전(Set-Cookie 값 변경).
        val secondSession = webSession()
        submitIdentifier(secondSession)
        val loginResult =
            secondSession
                .perform(
                    post("/login/password")
                        .param("username", email)
                        .param("password", password)
                        .cookie(Cookie(TrustedDeviceService.COOKIE_NAME, issuedToken!!))
                        .with(csrf()),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/account"))
                .andReturn()
        val rotatedToken = trustedDeviceCookie(loginResult)
        assertThat(rotatedToken).isNotNull()
        assertThat(rotatedToken).isNotEqualTo(issuedToken)
        // 행은 그대로 1개(회전 — 재발급 아님).
        assertThat(trustedDeviceRepository.findAll()).hasSize(1)

        // 3) 회전 전(구) 토큰은 더 이상 유효하지 않다 → 다시 MFA 게이트.
        val thirdSession = webSession()
        submitIdentifier(thirdSession)
        thirdSession
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", password)
                    .cookie(Cookie(TrustedDeviceService.COOKIE_NAME, issuedToken))
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/mfa"))
    }

    @Test
    fun `password reset revokes all trusted devices`() {
        // 신뢰 기기 확보.
        val session = webSession()
        loginToMfaGate(session)
        val mfaResult =
            session
                .perform(
                    post("/login/mfa")
                        .param("code", validTotp())
                        .param("trustDevice", "on")
                        .with(csrf()),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
        val token = trustedDeviceCookie(mfaResult)!!
        assertThat(trustedDeviceRepository.findAll()).hasSize(1)

        // 비밀번호 재설정.
        mockMvc
            .perform(post("/password-reset").param("email", email).with(csrf()))
            .andExpect(status().isOk)
        val resetToken = extractResetToken()
        val newPassword = "NewSecureP@ss456"
        mockMvc
            .perform(
                post("/password-reset/confirm")
                    .param("token", resetToken)
                    .param("newPassword", newPassword)
                    .with(csrf()),
            ).andExpect(redirectedUrl("/login?reset=true"))

        // 전체 폐기 → 쿠키가 있어도 MFA 게이트로 돌아간다.
        assertThat(trustedDeviceRepository.findAll()).isEmpty()
        val newSession = webSession()
        submitIdentifier(newSession)
        newSession
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", newPassword)
                    .cookie(Cookie(TrustedDeviceService.COOKIE_NAME, token))
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/mfa"))
    }

    // ---- helpers ----

    private fun submitIdentifier(session: WebSession) {
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
    }

    private fun loginToMfaGate(session: WebSession) {
        submitIdentifier(session)
        session
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", password)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/mfa"))
    }

    private fun trustedDeviceCookie(result: MvcResult): String? =
        result.response
            .getHeaders("Set-Cookie")
            .firstOrNull { it.startsWith("${TrustedDeviceService.COOKIE_NAME}=") }
            ?.substringAfter("${TrustedDeviceService.COOKIE_NAME}=")
            ?.substringBefore(";")

    private fun validTotp(): String =
        DefaultCodeGenerator(HashingAlgorithm.SHA1).generate(
            totpSecret,
            Math.floorDiv(SystemTimeProvider().time, 30L),
        )

    private fun extractResetToken(): String {
        val body = sentMessages.last { it.subject?.contains("비밀번호 재설정") == true }.text ?: ""
        return Regex("token=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }
}
