package com.taspa.server.risk

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.device.TrustedDeviceRepository
import com.taspa.server.domain.login.LoginEvent
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.mfa.BackupCodeRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
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
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * 리스크 기반 인증(Stage B) 통합 테스트 — 테스트 기본값(application-test.yml)은 비활성화이므로
 * 이 클래스만 명시적으로 켠다. 비활성화 동작은 RiskDisabledIntegrationTest.
 */
@TestPropertySource(properties = ["taspa.risk.enabled=true"])
class RiskBasedAuthIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var trustedDeviceRepository: TrustedDeviceRepository

    @Autowired lateinit var backupCodeRepository: BackupCodeRepository

    @Autowired lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var mfaService: MfaService

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "risk@example.com"
    private val password = "SecureP@ssw0rd123"
    private val chromeMacUa =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val clientId = "risk-demo-client"
    private val redirectUri = "http://localhost:8080/login/oauth2/code/taspa"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        trustedDeviceRepository.deleteAll()
        loginEventRepository.deleteAll()
        backupCodeRepository.deleteAll()
        emailVerificationCodeRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    @Test
    fun `unseen device without mfa triggers risk challenge and known device does not`() {
        createUser(emailVerified = true)

        // 1) 신규 기기(로그인 이력 없음) → MEDIUM → 리스크 챌린지 게이트.
        val first = webSession()
        loginWithPassword(first)
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/risk-challenge"))

        // pending 은 인증이 아니다 — 보호 자원 접근 불가.
        first
            .perform(get("/account").header("User-Agent", chromeMacUa))
            .andExpect(status().is3xxRedirection)

        // 2) 메일로 받은 코드 통과 → 완전 인증(리다이렉트 대상은 saved request — 위 /account 시도).
        first
            .perform(
                post("/login/risk-challenge")
                    .param("code", latestChallengeCode())
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
        first.perform(get("/account").header("User-Agent", chromeMacUa)).andExpect(status().isOk)

        // 3) 같은 (ip, ua) 재로그인 → 알려진 기기 → 게이트 없음.
        val second = webSession()
        loginWithPassword(second)
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
    }

    @Test
    fun `medium risk forces mfa even with valid trusted device cookie`() {
        val user = createUser(emailVerified = true)
        val secret = mfaService.setup(user.id!!).secret
        mfaService.activate(user.id!!, validTotp(secret))

        // 1) 첫 로그인: MFA 게이트 통과 + 신뢰 기기 발급.
        val first = webSession()
        loginWithPassword(first).andExpect(redirectedUrl("/login/mfa"))
        val mfaResult =
            first
                .perform(
                    post("/login/mfa")
                        .param("code", validTotp(secret))
                        .param("trustDevice", "on")
                        .header("User-Agent", chromeMacUa)
                        .with(csrf()),
                ).andExpect(redirectedUrl("/account"))
                .andReturn()
        val trustedToken = trustedDeviceCookie(mfaResult)!!

        // 2) 실패 3회 → recentFailures 신호(잠금 상한 5 미만).
        repeat(3) { failLogin() }
        assertThat(userRepository.findByEmail(email)!!.failedLoginAttempts).isEqualTo(3)

        // 3) 유효한 신뢰 기기 쿠키가 있어도 MEDIUM(recentFailures) → 스킵 무시, MFA 강제.
        val second = webSession()
        loginWithPassword(second, Cookie(TrustedDeviceService.COOKIE_NAME, trustedToken))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/mfa"))
        // 평가는 리셋 전 값으로 했고, 성공 처리로 카운터는 리셋됐다.
        assertThat(userRepository.findByEmail(email)!!.failedLoginAttempts).isEqualTo(0)

        // 4) 신호가 사라지면(카운터 리셋) 같은 쿠키로 MFA 스킵 — 강제가 리스크 때문이었음을 증명.
        val third = webSession()
        loginWithPassword(third, Cookie(TrustedDeviceService.COOKIE_NAME, trustedToken))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
    }

    @Test
    fun `high risk sends security alert mail`() {
        createUser(emailVerified = true)
        repeat(3) { failLogin() }

        // 신규 기기 && recentFailures → HIGH → 챌린지 + 보안 경고 메일.
        val session = webSession()
        loginWithPassword(session).andExpect(redirectedUrl("/login/risk-challenge"))

        val alerts = sentMessages.filter { it.subject == "[taspa] 보안 경고: 의심스러운 로그인이 감지되었습니다" }
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first().text).contains("비밀번호를 변경하세요")
        // 챌린지 코드 메일도 별도로 발송된다.
        assertThat(sentMessages.count { it.subject == "[taspa] 이메일 인증 코드" }).isEqualTo(1)
    }

    @Test
    fun `unverified account gets only email verification gate without duplicate challenge`() {
        createUser(emailVerified = false)

        // 미인증 계정 + 신규 기기: EMAIL_VERIFICATION 게이트가 이메일 소유를 증명하므로
        // RISK_CHALLENGE 는 중복 발동하지 않는다.
        val session = webSession()
        loginWithPassword(session)
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/verify-email"))

        // 리스크 챌린지 코드가 자동 발급되지 않았음을 함께 검증한다(중복 챌린지 금지) —
        // 인증 코드는 재발송 요청으로만 생긴다(가입 경로가 아니라 최초 코드가 없음).
        assertThat(sentMessages.count { it.subject == "[taspa] 이메일 인증 코드" }).isEqualTo(0)
        session
            .perform(
                post("/login/verify-email/resend").header("User-Agent", chromeMacUa).with(csrf()),
            ).andExpect(redirectedUrl("/login/verify-email?resent=true"))

        session
            .perform(
                post("/login/verify-email")
                    .param("code", latestChallengeCode())
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        session.perform(get("/account").header("User-Agent", chromeMacUa)).andExpect(status().isOk)
    }

    @Test
    fun `risk challenge pending does not issue oauth2 authorization code`() {
        ensureRegisteredClient()
        createUser(emailVerified = true)

        val session = webSession()
        loginWithPassword(session).andExpect(redirectedUrl("/login/risk-challenge"))

        // 보안 핵심: RISK_CHALLENGE pending 상태에서는 authorization code 가 발급되지 않는다.
        val pending = authorize(session)
        assertThat(pending.response.status).isEqualTo(302)
        assertThat(pending.response.redirectedUrl ?: "").contains("/login")
        assertThat(pending.response.redirectedUrl ?: "").doesNotContain("code=")
        session
            .perform(get("/account").header("User-Agent", chromeMacUa))
            .andExpect(status().is3xxRedirection)

        // 챌린지 통과 후에는 동일한 authorize 요청이 code 를 발급한다 —
        // 앞서 미발급의 유일한 이유가 리스크 게이트였음을 증명한다(요청 자체는 유효).
        session
            .perform(
                post("/login/risk-challenge")
                    .param("code", latestChallengeCode())
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
        session.perform(get("/account").header("User-Agent", chromeMacUa)).andExpect(status().isOk)
        val authed = authorize(session)
        assertThat(authed.response.status).isEqualTo(302)
        assertThat(authed.response.redirectedUrl ?: "").startsWith(redirectUri)
        assertThat(authed.response.redirectedUrl ?: "").contains("code=")
    }

    @Test
    fun `rapid ip change alone stays low but with unseen device escalates to high`() {
        val user = createUser(emailVerified = true)
        val now = Instant.now()
        // 40분 전 10.0.0.2 로그인 이력 → 10.0.0.2 는 알려진 기기(90일 창 안, 30분 rapid 창 밖).
        loginEventRepository.save(
            LoginEvent(
                userId = user.id!!,
                ip = "10.0.0.2",
                uaLabel = "Chrome / macOS",
                method = "password",
                createdAt = now.minus(Duration.ofMinutes(40)),
            ),
        )
        // 직전(5분 전) 성공 로그인은 127.0.0.1 → 10.0.0.2 로그인은 30분 창 안의 IP 변경이다.
        loginEventRepository.save(
            LoginEvent(
                userId = user.id!!,
                ip = "127.0.0.1",
                uaLabel = "Chrome / macOS",
                method = "password",
                createdAt = now.minus(Duration.ofMinutes(5)),
            ),
        )

        // 1) rapidIpChange 단독(알려진 기기, 실패 없음) → LOW — 게이트도 경고 메일도 없다.
        val first = webSession()
        loginWithPassword(first, remoteAddr = "10.0.0.2")
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        assertThat(sentMessages).isEmpty()

        // 2) 신규 기기(10.0.0.9 이력 없음) && 직전 로그인(10.0.0.2, 방금) 대비 IP 변경 → HIGH.
        val second = webSession()
        loginWithPassword(second, remoteAddr = "10.0.0.9")
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/risk-challenge"))
        assertThat(sentMessages.count { it.subject == "[taspa] 보안 경고: 의심스러운 로그인이 감지되었습니다" })
            .isEqualTo(1)
    }

    @Test
    fun `mfa user with high risk is forced to mfa gate and gets alert mail`() {
        val user = createUser(emailVerified = true)
        val secret = mfaService.setup(user.id!!).secret
        mfaService.activate(user.id!!, validTotp(secret))
        repeat(3) { failLogin() }

        // HIGH(unseenDevice + recentFailures)여도 MFA 등록 사용자의 게이트는 MFA 다
        // (RISK_CHALLENGE 아님) — 경고 메일은 발송되고 챌린지 코드 메일은 없다.
        val session = webSession()
        loginWithPassword(session).andExpect(redirectedUrl("/login/mfa"))
        assertThat(sentMessages.count { it.subject == "[taspa] 보안 경고: 의심스러운 로그인이 감지되었습니다" })
            .isEqualTo(1)
        assertThat(sentMessages.count { it.subject == "[taspa] 이메일 인증 코드" }).isZero()

        session
            .perform(
                post("/login/mfa")
                    .param("code", validTotp(secret))
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(redirectedUrl("/account"))
        session.perform(get("/account").header("User-Agent", chromeMacUa)).andExpect(status().isOk)
    }

    @Test
    fun `recent failures signal survives expired lockout`() {
        createUser(emailVerified = true)
        // 잠금까지 간 최악의 브루트포스 후 잠금이 만료된 상태를 시뮬레이션한다.
        val user = userRepository.findByEmail(email)!!
        user.failedLoginAttempts = 5
        user.lockedUntil = Instant.now().minusSeconds(1)
        userRepository.save(user)

        // 만료 자동 해제는 카운터를 보존하므로(AccountLockoutService) 올바른 비밀번호 + 신규
        // 기기 로그인은 HIGH(unseenDevice + recentFailures) — 챌린지 + 경고 메일이어야 한다.
        val session = webSession()
        loginWithPassword(session)
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/risk-challenge"))
        assertThat(sentMessages.count { it.subject == "[taspa] 보안 경고: 의심스러운 로그인이 감지되었습니다" })
            .isEqualTo(1)
        // 성공 처리(recordSuccessfulLogin)가 카운터를 정리한다.
        assertThat(userRepository.findByEmail(email)!!.failedLoginAttempts).isEqualTo(0)
    }

    @Test
    fun `challenge code is invalidated after five wrong attempts`() {
        createUser(emailVerified = true)
        val session = webSession()
        loginWithPassword(session).andExpect(redirectedUrl("/login/risk-challenge"))
        val code = latestChallengeCode()
        val wrong = if (code == "000000") "111111" else "000000"

        // 코드당 시도 상한(5회) — 불일치 제출은 챌린지 화면을 오류와 함께 재렌더한다.
        repeat(5) {
            session
                .perform(
                    post("/login/risk-challenge")
                        .param("code", wrong)
                        .header("User-Agent", chromeMacUa)
                        .with(csrf()),
                ).andExpect(status().isOk)
        }
        // 상한 도달로 코드가 소진(무효화)되어 이제 올바른 코드도 거부된다 — 브루트포스로
        // 같은 코드를 무한 대입할 수 없다(복구는 재발송 = 새 코드).
        session
            .perform(
                post("/login/risk-challenge")
                    .param("code", code)
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(status().isOk)
        session
            .perform(get("/account").header("User-Agent", chromeMacUa))
            .andExpect(status().is3xxRedirection)
    }

    @Test
    fun `repeated gate entries within resend interval reuse the challenge code`() {
        createUser(emailVerified = true)

        // 두 세션이 연속으로 게이트에 진입해도(60초 내) 코드는 한 번만 발송된다 —
        // 반복 로그인의 메일 폭주 방지 + 병행 세션의 유효 코드 무효화 방지.
        val first = webSession()
        loginWithPassword(first).andExpect(redirectedUrl("/login/risk-challenge"))
        val second = webSession()
        loginWithPassword(second).andExpect(redirectedUrl("/login/risk-challenge"))
        assertThat(sentMessages.count { it.subject == "[taspa] 이메일 인증 코드" }).isEqualTo(1)

        // 첫 세션은 처음 받은(유일한) 코드로 여전히 통과한다.
        first
            .perform(
                post("/login/risk-challenge")
                    .param("code", latestChallengeCode())
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
        first.perform(get("/account").header("User-Agent", chromeMacUa)).andExpect(status().isOk)
    }

    // ---- helpers ----

    private fun createUser(emailVerified: Boolean): User =
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = emailVerified),
        )

    private fun loginWithPassword(
        session: WebSession,
        vararg cookies: Cookie,
        remoteAddr: String? = null,
    ): ResultActions {
        session
            .perform(
                post("/login/identifier").param("email", email).header("User-Agent", chromeMacUa).with(csrf()),
            ).andExpect(redirectedUrl("/login/password"))
        val builder =
            post("/login/password")
                .param("username", email)
                .param("password", password)
                .header("User-Agent", chromeMacUa)
                .with(csrf())
        if (cookies.isNotEmpty()) {
            builder.cookie(*cookies)
        }
        // remoteAddr 지정 시 다른 클라이언트 IP 를 시뮬레이션한다(MockMvc 기본은 127.0.0.1) —
        // 리스크 신호는 X-Forwarded-For 가 아닌 remoteAddr 만 신뢰하므로 헤더로는 못 바꾼다.
        if (remoteAddr != null) {
            builder.with { request ->
                request.remoteAddr = remoteAddr
                request
            }
        }
        return session.perform(builder)
    }

    private fun failLogin() {
        val session = webSession()
        session
            .perform(
                post("/login/identifier").param("email", email).header("User-Agent", chromeMacUa).with(csrf()),
            ).andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", "Wrong-$password")
                    .header("User-Agent", chromeMacUa)
                    .with(csrf()),
            ).andExpect(redirectedUrl("/login/password?error"))
    }

    private fun latestChallengeCode(): String {
        val body = sentMessages.last { it.subject == "[taspa] 이메일 인증 코드" }.text ?: ""
        return Regex("\\b\\d{6}\\b").find(body)!!.value
    }

    private fun trustedDeviceCookie(result: MvcResult): String? =
        result.response
            .getHeaders("Set-Cookie")
            .firstOrNull { it.startsWith("${TrustedDeviceService.COOKIE_NAME}=") }
            ?.substringAfter("${TrustedDeviceService.COOKIE_NAME}=")
            ?.substringBefore(";")

    private fun validTotp(secret: String): String =
        DefaultCodeGenerator(HashingAlgorithm.SHA1).generate(
            secret,
            Math.floorDiv(SystemTimeProvider().time, 30L),
        )

    private fun ensureRegisteredClient() {
        if (registeredClientRepository.findByClientId(clientId) != null) return
        registeredClientRepository.save(
            RegisteredClient
                .withId(clientId)
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode("risk-demo-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .clientSettings(
                    ClientSettings
                        .builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build(),
                ).build(),
        )
    }

    private fun authorize(session: WebSession) =
        session
            .perform(
                get(authorizeUrl()).header("Accept", MediaType.TEXT_HTML_VALUE).header("User-Agent", chromeMacUa),
            ).andReturn()

    private fun authorizeUrl(): String {
        // redirect_uri/code_challenge 는 쿼리에서 &/= 를 포함하지 않으므로 인코딩 없이 그대로 둔다
        // (MockMvc 의 이중 디코딩 문제 회피 — GoogleLoginFlowIntegrationTest 와 동일 패턴).
        val challenge = pkceChallenge("taspa-risk-pkce-verifier-abcdefghijklmnopqrstuvwxyz-0123456789")
        return "/oauth2/authorize?response_type=code&client_id=$clientId" +
            "&redirect_uri=$redirectUri&scope=openid" +
            "&code_challenge=$challenge&code_challenge_method=S256&state=xyz"
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
