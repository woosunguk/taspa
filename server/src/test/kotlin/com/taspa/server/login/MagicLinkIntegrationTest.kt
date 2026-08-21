package com.taspa.server.login

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.credential.MagicLinkToken
import com.taspa.server.domain.credential.MagicLinkTokenRepository
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
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * 매직 링크(B-4) 통합 테스트: 정상 플로우 / 단일 사용 / 만료 / MFA 게이트 유지 / 미검증 계정 검증 마킹 / 재발급 제한.
 */
class MagicLinkIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var magicLinkTokenRepository: MagicLinkTokenRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var backupCodeRepository: BackupCodeRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var mfaService: MfaService

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "magic@example.com"
    private val password = "SecureP@ssw0rd123"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        magicLinkTokenRepository.deleteAll()
        loginEventRepository.deleteAll()
        backupCodeRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    @Test
    fun `magic link logs in on confirm and is single use`() {
        createUser(emailVerified = true)
        val session = webSession()
        val token = requestMagicLink(session)

        // GET 랜딩은 토큰을 소비하지 않는다 — 두 번 열어도 유효하다(스캐너 선클릭 방지).
        mockMvc
            .perform(get("/login/magic").param("token", token))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("로그인하시겠습니까")))
        mockMvc
            .perform(get("/login/magic").param("token", token))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(email)))

        // POST 확정 → 클릭한 브라우저(세션)에서 로그인 성립.
        session
            .perform(post("/login/magic").param("token", token).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        session.perform(get("/account")).andExpect(status().isOk)

        // login_events 에 method=magic 기록.
        assertThat(loginEventRepository.findAll().map { it.method }).contains("magic")

        // 단일 사용: 같은 토큰 재사용 불가.
        webSession()
            .perform(post("/login/magic").param("token", token).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("만료되었거나 이미 사용")))
        mockMvc
            .perform(get("/login/magic").param("token", token))
            .andExpect(content().string(containsString("만료되었거나 이미 사용")))
    }

    @Test
    fun `magic link keeps the mfa gate`() {
        val user = createUser(emailVerified = true)
        val secret = mfaService.setup(user.id!!).secret
        mfaService.activate(user.id!!, validTotp(secret))

        val session = webSession()
        val token = requestMagicLink(session)

        // 매직 링크는 1차 인증일 뿐 — MFA 게이트가 유지된다.
        session
            .perform(post("/login/magic").param("token", token).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/mfa"))
        session.perform(get("/account")).andExpect(status().is3xxRedirection)

        // 직전 /account 시도가 saved request 로 남아 completeAuthentication 이 그곳(?continue 포함)으로 복귀시킨다.
        val redirected =
            session
                .perform(
                    post("/login/mfa").param("code", validTotp(secret)).with(csrf()),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl
        assertThat(redirected).contains("/account")
        session.perform(get("/account")).andExpect(status().isOk)
    }

    @Test
    fun `magic link marks an unverified account as verified`() {
        createUser(emailVerified = false)
        val session = webSession()
        val token = requestMagicLink(session)

        // 링크 클릭 = 이메일 소유 증명 → 검증 마킹 후 이메일 인증 게이트 없이 로그인.
        session
            .perform(post("/login/magic").param("token", token).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        assertThat(userRepository.findByEmail(email)!!.emailVerified).isTrue()
    }

    @Test
    fun `expired magic link is rejected`() {
        val user = createUser(emailVerified = true)
        val session = webSession()
        val token = requestMagicLink(session)

        // 저장된 행을 만료된 행으로 교체해 시간 경과를 시뮬레이션한다.
        val hash = SecureTokenGenerator.hashToken(token)
        magicLinkTokenRepository.delete(magicLinkTokenRepository.findByTokenHash(hash)!!)
        magicLinkTokenRepository.save(
            MagicLinkToken(userId = user.id!!, tokenHash = hash, expiresAt = Instant.now().minusSeconds(1)),
        )

        session
            .perform(post("/login/magic").param("token", token).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("만료되었거나 이미 사용")))
        session.perform(get("/account")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `request is throttled and response does not reveal account existence`() {
        createUser(emailVerified = true)
        val session = webSession()
        requestMagicLink(session)
        // 60초 내 재요청 → 메일은 1통뿐이지만 응답 화면은 동일하다.
        session
            .perform(post("/login/magic/request").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("메일함을 확인하세요")))
        assertThat(sentMessages.count { it.subject == "[taspa] 로그인 링크" }).isEqualTo(1)

        // 미존재 이메일도 같은 화면(열거 공격 방지) + 메일 미발송.
        val ghostSession = webSession()
        ghostSession.perform(post("/login/identifier").param("email", email).with(csrf()))
        userRepository.deleteAll()
        ghostSession
            .perform(post("/login/magic/request").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("메일함을 확인하세요")))
        assertThat(sentMessages.count { it.subject == "[taspa] 로그인 링크" }).isEqualTo(1)
    }

    // ---- helpers ----

    private fun createUser(emailVerified: Boolean): User =
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = emailVerified),
        )

    /** identifier 제출로 LOGIN_HINT 를 심고 매직 링크를 요청한 뒤, 메일에서 토큰을 추출한다. */
    private fun requestMagicLink(session: WebSession): String {
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(status().is3xxRedirection)
        session
            .perform(post("/login/magic/request").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("메일함을 확인하세요")))
        val body = sentMessages.last { it.subject == "[taspa] 로그인 링크" }.text ?: ""
        return Regex("token=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }

    private fun validTotp(secret: String): String =
        DefaultCodeGenerator(HashingAlgorithm.SHA1).generate(
            secret,
            Math.floorDiv(SystemTimeProvider().time, 30L),
        )
}
