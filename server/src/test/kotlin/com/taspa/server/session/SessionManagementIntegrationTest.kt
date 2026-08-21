package com.taspa.server.session

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.credential.PasswordResetTokenRepository
import com.taspa.server.domain.device.TrustedDevice
import com.taspa.server.domain.device.TrustedDeviceRepository
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
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * 원격 세션 관리(Stage B) 통합 테스트: 목록/현재 세션 식별/원격 로그아웃/revoke-others/
 * 비밀번호 재설정 시 전체 소멸/재시작 생존(DB 행 + 쿠키 재사용).
 */
class SessionManagementIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var trustedDeviceRepository: TrustedDeviceRepository

    @Autowired lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var indexedSessionRepository: FindByIndexNameSessionRepository<out Session>

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "sessions@example.com"
    private val otherEmail = "sessions-other@example.com"
    private val password = "SecureP@ssw0rd123"
    private val chromeMacUa =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val firefoxWinUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        loginEventRepository.deleteAll()
        trustedDeviceRepository.deleteAll()
        passwordResetTokenRepository.deleteAll()
        // 이전 테스트가 남긴 세션이 findByPrincipalName 목록을 오염시키지 않도록 정리한다.
        listOf(email, otherEmail).forEach { principal ->
            indexedSessionRepository.findByPrincipalName(principal).keys.forEach {
                indexedSessionRepository.deleteById(it)
            }
        }
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    @Test
    fun `session list shows both logins with client metadata and identifies the current one`() {
        val sessionA = login(chromeMacUa)
        val sessionB = login(firefoxWinUa)

        // principal 인덱스로 두 세션 모두 조회된다.
        assertThat(indexedSessionRepository.findByPrincipalName(email)).hasSize(2)

        sessionA
            .perform(get("/api/sessions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            // 정렬: 현재 세션이 항상 첫 번째.
            .andExpect(jsonPath("$[0].current").value(true))
            .andExpect(jsonPath("$[0].browser").value("Chrome / macOS"))
            .andExpect(jsonPath("$[0].ip").value("127.0.0.1"))
            .andExpect(jsonPath("$[1].current").value(false))
            .andExpect(jsonPath("$[1].browser").value("Firefox / Windows"))
            // 세션 ID 원문 비노출: publicId 는 16자리 hex 축약.
            .andExpect(jsonPath("$[0].publicId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{16}")))

        // B 쪽에서 보면 current 가 반대로 식별된다.
        sessionB
            .perform(get("/api/sessions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].current").value(true))
            .andExpect(jsonPath("$[0].browser").value("Firefox / Windows"))
    }

    @Test
    fun `remote revoke kills the target session and foreign publicId is 404`() {
        val sessionA = login(chromeMacUa)
        val sessionB = login(firefoxWinUa)

        // 파괴적 세션 API 는 /api/** CSRF 면제에서 제외된다 — 토큰 없는 요청은 403 (회귀 방지).
        val otherPublicId = publicIdOfOther(sessionA)
        sessionA
            .perform(delete("/api/sessions/$otherPublicId"))
            .andExpect(status().isForbidden)

        // A 에서 B(publicId)를 원격 로그아웃.
        sessionA
            .perform(delete("/api/sessions/$otherPublicId").with(csrf()))
            .andExpect(status().isNoContent)

        // B 쿠키 요청은 미인증 처리(로그인 리다이렉트), A 는 계속 유효.
        sessionB.perform(get("/account")).andExpect(status().is3xxRedirection)
        sessionA.perform(get("/account")).andExpect(status().isOk)

        // 타 사용자의 publicId 는 본인 목록에 없으므로 404 (존재 여부 비노출).
        userRepository.save(
            User(email = otherEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
        val otherUserSession = login(chromeMacUa, otherEmail)
        val victimPublicId = currentPublicId(sessionA)
        otherUserSession
            .perform(delete("/api/sessions/$victimPublicId").with(csrf()))
            .andExpect(status().isNotFound)
        sessionA.perform(get("/account")).andExpect(status().isOk)
    }

    @Test
    fun `revoke-others keeps only the current session`() {
        val sessionA = login(chromeMacUa)
        val sessionB = login(firefoxWinUa)
        val sessionC = login(firefoxWinUa)

        sessionA
            .perform(post("/api/sessions/revoke-others").with(csrf()))
            .andExpect(status().isNoContent)

        assertThat(indexedSessionRepository.findByPrincipalName(email)).hasSize(1)
        sessionA.perform(get("/account")).andExpect(status().isOk)
        sessionB.perform(get("/account")).andExpect(status().is3xxRedirection)
        sessionC.perform(get("/account")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `password reset revokes every session and trusted device`() {
        val user = userRepository.findByEmail(email)!!
        val sessionA = login(chromeMacUa)
        val sessionB = login(firefoxWinUa)
        trustedDeviceRepository.save(
            TrustedDevice(
                userId = user.id!!,
                tokenHash = SecureTokenGenerator.hashToken(SecureTokenGenerator.generateToken()),
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        assertThat(indexedSessionRepository.findByPrincipalName(email)).hasSize(2)

        mockMvc
            .perform(post("/password-reset").param("email", email).with(csrf()))
            .andExpect(status().isOk)
        mockMvc
            .perform(
                post("/password-reset/confirm")
                    .param("token", extractResetToken())
                    .param("newPassword", "NewSecureP@ss456")
                    .with(csrf()),
            ).andExpect(redirectedUrl("/login?reset=true"))

        // 계정 탈취 대응 완성: 모든 세션 + 신뢰 기기 소멸.
        assertThat(indexedSessionRepository.findByPrincipalName(email)).isEmpty()
        assertThat(trustedDeviceRepository.findAll()).isEmpty()
        sessionA.perform(get("/account")).andExpect(status().is3xxRedirection)
        sessionB.perform(get("/account")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `session survives in the store and the cookie keeps working (restart survival)`() {
        val session = login(chromeMacUa)

        // JDBC 저장소에 세션 행이 존재한다(재시작 생존의 근거 — 상태가 DB 에 있다).
        val sessionId = session.sessionId()!!
        assertThat(indexedSessionRepository.findById(sessionId)).isNotNull

        // 같은 쿠키 재사용으로 인증 상태 유지 + IP/UA 메타도 저장소에서 복원된다.
        session.perform(get("/account")).andExpect(status().isOk)
        val stored = indexedSessionRepository.findById(sessionId)!!
        assertThat(stored.getAttribute<String>(SessionMetadata.USER_AGENT_KEY)).isEqualTo("Chrome / macOS")
        assertThat(stored.getAttribute<String>(SessionMetadata.CLIENT_IP_KEY)).isEqualTo("127.0.0.1")
    }

    // ---- helpers ----

    private fun login(
        userAgent: String,
        loginEmail: String = email,
    ): WebSession {
        val session = webSession()
        session
            .perform(
                post("/login/identifier").param("email", loginEmail).header("User-Agent", userAgent).with(csrf()),
            ).andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password")
                    .param("username", loginEmail)
                    .param("password", password)
                    .header("User-Agent", userAgent)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        return session
    }

    private fun currentPublicId(session: WebSession): String = SessionManagementService.publicId(session.sessionId()!!)

    private fun publicIdOfOther(session: WebSession): String {
        val body =
            session
                .perform(get("/api/sessions"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return Regex(""""publicId":"([0-9a-f]{16})"[^}]*"current":false""").find(body)?.groupValues?.get(1)
            ?: Regex(""""current":false[^}]*"publicId":"([0-9a-f]{16})"""").find(body)!!.groupValues[1]
    }

    private fun extractResetToken(): String {
        val body = sentMessages.last { it.subject?.contains("비밀번호 재설정") == true }.text ?: ""
        return Regex("token=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }
}
