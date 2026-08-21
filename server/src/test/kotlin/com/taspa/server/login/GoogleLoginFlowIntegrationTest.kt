package com.taspa.server.login

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.credential.PasswordResetTokenRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.util.Base64

class GoogleLoginFlowIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var backupCodeRepository: BackupCodeRepository

    @Autowired lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @Autowired lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var mfaService: MfaService

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "flow@example.com"
    private val password = "SecureP@ssw0rd123"
    private val clientId = "demo-app"
    private val redirectUri = "http://localhost:8080/login/oauth2/code/taspa"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        backupCodeRepository.deleteAll()
        emailVerificationCodeRepository.deleteAll()
        passwordResetTokenRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
        ensureRegisteredClient()
    }

    private fun ensureRegisteredClient() {
        if (registeredClientRepository.findByClientId(clientId) != null) return
        registeredClientRepository.save(
            RegisteredClient
                .withId(clientId)
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode("demo-secret"))
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

    @Test
    fun `signup then email verification unlocks protected page`() {
        val session = webSession()

        session
            .perform(
                post("/signup")
                    .param("email", email)
                    .param("password", password)
                    .param("displayName", "테스터")
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/verify-email"))

        // 미인증 상태에서는 보호 페이지 접근 불가.
        session
            .perform(get("/account"))
            .andExpect(status().is3xxRedirection)

        val code = latestCode()
        session
            .perform(post("/login/verify-email").param("code", code).with(csrf()))
            .andExpect(status().is3xxRedirection)

        assertThat(userRepository.findByEmail(email)!!.emailVerified).isTrue()

        session
            .perform(get("/account"))
            .andExpect(status().isOk)
    }

    @Test
    fun `mfa gate prevents oauth2 authorize code issuance until totp verified`() {
        val user = createUser(emailVerified = true)
        val setup = mfaService.setup(user.id!!)
        mfaService.activate(user.id, validTotp(setup.secret))

        val session = webSession()
        loginToMfaGate(session)

        // 보안 핵심: pending(비밀번호만 통과) 상태에서는 authorization code 가 발급되지 않고 /login 으로 유도된다.
        val pending = authorize(session)
        assertThat(pending.response.status).isEqualTo(302)
        assertThat(pending.response.redirectedUrl ?: "").contains("/login")
        assertThat(pending.response.redirectedUrl ?: "").doesNotContain("code=")
        // pending 은 인증이 아니므로 보호 자원(/account) 접근도 로그인으로 되돌려진다.
        session.perform(get("/account")).andExpect(status().is3xxRedirection)

        // 유효한 TOTP 제출 후 완전 인증.
        session
            .perform(post("/login/mfa").param("code", validTotp(setup.secret)).with(csrf()))
            .andExpect(status().is3xxRedirection)
        session.perform(get("/account")).andExpect(status().isOk)

        // 완전 인증 후에는 동일한 authorize 요청이 클라이언트 redirect_uri 로 code 를 발급한다.
        // → 앞서 code 가 발급되지 않은 유일한 이유가 MFA 게이트였음을 증명한다(요청 자체는 유효).
        val authed = authorize(session)
        assertThat(authed.response.status).isEqualTo(302)
        assertThat(authed.response.redirectedUrl ?: "").startsWith(redirectUri)
        assertThat(authed.response.redirectedUrl ?: "").contains("code=")
    }

    private fun authorize(session: WebSession) =
        session
            .perform(
                get(authorizeUrl()).header("Accept", MediaType.TEXT_HTML_VALUE),
            ).andReturn()

    private fun authorizeUrl(): String {
        // redirect_uri/code_challenge 는 쿼리에서 &/= 를 포함하지 않으므로 인코딩 없이 그대로 둔다
        // (MockMvc 가 미리 인코딩된 값을 이중 디코딩하는 문제를 피한다).
        val challenge = pkceChallenge("taspa-pkce-verifier-abcdefghijklmnopqrstuvwxyz-0123456789")
        return "/oauth2/authorize?response_type=code&client_id=$clientId" +
            "&redirect_uri=$redirectUri&scope=openid" +
            "&code_challenge=$challenge&code_challenge_method=S256&state=xyz"
    }

    @Test
    fun `backup code authenticates once and cannot be reused`() {
        val user = createUser(emailVerified = true)
        val setup = mfaService.setup(user.id!!)
        val backupCode = mfaService.activate(user.id, validTotp(setup.secret)).first()

        val firstSession = webSession()
        loginToMfaGate(firstSession)
        firstSession
            .perform(post("/login/mfa").param("code", backupCode).with(csrf()))
            .andExpect(status().is3xxRedirection)
        firstSession.perform(get("/account")).andExpect(status().isOk)

        // 동일 백업 코드 재사용은 실패해야 한다.
        val secondSession = webSession()
        loginToMfaGate(secondSession)
        secondSession
            .perform(post("/login/mfa").param("code", backupCode).with(csrf()))
            .andExpect(status().isOk) // MFA 페이지 재렌더(에러)
        secondSession.perform(get("/account")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `logout confirmation page requires authentication and returns 200 when logged in`() {
        createUser(emailVerified = true)

        // F1: GET /logout 은 permitAll 목록에 없으므로 인증되지 않은 접근은 로그인으로 리다이렉트된다.
        mockMvc.perform(get("/logout")).andExpect(status().is3xxRedirection)

        // MFA 미설정 사용자는 게이트 없이 identifier → password 로 바로 완전 인증된다.
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

        session.perform(get("/logout")).andExpect(status().isOk)
    }

    private fun loginToMfaGate(session: WebSession) {
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", password)
                    .with(csrf()),
            ).andExpect(redirectedUrl("/login/mfa"))
    }

    private fun createUser(emailVerified: Boolean): User =
        userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = emailVerified,
            ),
        )

    private fun latestCode(): String {
        val body = sentMessages.last().text ?: ""
        return Regex("\\b\\d{6}\\b").find(body)!!.value
    }

    private fun validTotp(secret: String): String {
        val counter = Math.floorDiv(SystemTimeProvider().time, 30)
        return DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(secret, counter)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
