package com.taspa.server.selfservice

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
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
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Stage 3(연결된 앱) 통합 테스트. 실제 authorization_code + PKCE 동의 → 토큰 발급(refresh 포함)까지 수행한 뒤
 * 목록 노출과 철회를 검증한다: 철회 시 refresh_token 재사용이 불가능(invalid_grant)해야 한다.
 */
class AuthorizedClientIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "apps@example.com"
    private val password = "SecureP@ssw0rd123"
    private val clientId = "connected-app"
    private val clientSecret = "connected-secret"
    private val redirectUri = "http://localhost:8080/callback"
    private val verifier = "taspa-pkce-verifier-abcdefghijklmnopqrstuvwxyz-0123456789"
    private lateinit var internalClientId: String
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        // 공유 컨테이너라 authorization/consent 행이 테스트 간 잔존한다(users FK 없음). 남은 consent 는
        // SAS 가 동의 화면을 건너뛰게 만들어 authorizeWithConsent 를 깨뜨리므로 매 테스트 초기화한다.
        jdbcTemplate.update("DELETE FROM oauth2_authorization")
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent")
        auditEventRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
        userRepository.save(User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true))
        registerClient()
    }

    @Test
    fun `authorized client is listed after consent and revocation invalidates refresh token`() {
        val session = login()
        val code = authorizeWithConsent(session)
        val tokens = exchangeCode(code)
        val refreshToken = tokens.get("refresh_token").asText()

        // 목록 노출: 동의한 클라이언트가 부여 scope 와 함께 보인다.
        val listed =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/authorized-clients"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(listed).hasSize(1)
        val entry = listed.single()
        assertThat(entry.get("clientName").asText()).isEqualTo("Connected App")
        assertThat(entry.get("scopes").map { it.asText() }).contains(OidcScopes.EMAIL)
        assertThat(entry.get("lastUsedAt").isNull).isFalse()
        val registeredClientId = entry.get("registeredClientId").asText()
        assertThat(registeredClientId).isEqualTo(internalClientId)

        // 철회 전에는 refresh_token 재사용이 성공한다(토큰이 실제로 유효했음을 증명).
        // reuseRefreshTokens=false 라 회전되므로 새 refresh 를 받아 다음 단계에서 다시 쓴다.
        val rotated = refresh(refreshToken)
        assertThat(rotated.andReturn().response.status).isEqualTo(200)
        val rotatedRefresh =
            objectMapper
                .readTree(rotated.andReturn().response.contentAsString)
                .get("refresh_token")
                .asText()

        // 철회(step-up 신선 — 로그인 직후).
        session
            .perform(delete("/api/account/authorized-clients/{id}", registeredClientId))
            .andExpect(status().isNoContent)

        // 목록에서 사라진다.
        val afterRevoke =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/authorized-clients"))
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(afterRevoke).isEmpty()

        // 철회 후 refresh_token 재사용 불가(authorization 행 삭제 → invalid_grant).
        assertThat(refresh(rotatedRefresh).andReturn().response.status).isEqualTo(400)

        assertThat(
            auditEventRepository.findByTypeOrderByCreatedAtDesc("THIRDPARTY_ACCESS_REVOKED", PageRequest.of(0, 1)),
        ).isNotEmpty
    }

    @Test
    fun `revoking a client the user never authorized returns 404`() {
        val session = login()

        session
            .perform(delete("/api/account/authorized-clients/{id}", internalClientId))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `revoking authorized client requires recent auth (step-up)`() {
        val session = login()
        val code = authorizeWithConsent(session)
        exchangeCode(code)
        session.setAttribute(
            com.taspa.server.stepup.StepUp.AUTH_TIME_SESSION_KEY,
            java.time.Instant
                .now()
                .minus(java.time.Duration.ofMinutes(11)),
        )

        session
            .perform(delete("/api/account/authorized-clients/{id}", internalClientId))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `changing email revokes third-party grants and invalidates existing refresh tokens`() {
        val session = login()
        val code = authorizeWithConsent(session)
        val refreshToken = exchangeCode(code).get("refresh_token").asText()

        // 변경 전: 연결앱이 노출된다.
        val before =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/authorized-clients"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(before).hasSize(1)

        // 이메일 변경(step-up 신선 — 로그인 직후) → 확인.
        val newEmail = "moved-apps@example.com"
        session
            .perform(
                post("/api/account/email/change")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newEmail":"$newEmail"}"""),
            ).andExpect(status().isAccepted)
        val verifyCode = codeSentTo(newEmail)
        session
            .perform(
                post("/api/account/email/change/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"code":"$verifyCode"}"""),
            ).andExpect(status().isNoContent)

        // 변경 후: 새 이메일 principal 로 연결앱 목록이 비어 있고(옛 grant 폐기),
        val after =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/authorized-clients"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(after).isEmpty()
        // 기존 refresh_token 재사용이 불가능하다(authorization 행 삭제 → invalid_grant).
        assertThat(refresh(refreshToken).andReturn().response.status).isEqualTo(400)
    }

    // ---- helpers ----

    private fun codeSentTo(target: String): String {
        val message = sentMessages.last { it.to?.contains(target) == true }
        return Regex("\\b\\d{6}\\b").find(message.text ?: "")!!.value
    }

    private fun registerClient() {
        // registered_client 는 users 와 무관해 테스트 간(공유 컨테이너) 잔존하므로, 이미 있으면 그 id 를 쓴다.
        registeredClientRepository.findByClientId(clientId)?.let {
            internalClientId = it.id
            return
        }
        val client =
            RegisteredClient
                .withId("connected-app-internal-id")
                .clientId(clientId)
                .clientName("Connected App")
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.EMAIL)
                .clientSettings(
                    ClientSettings
                        .builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build(),
                ).build()
        registeredClientRepository.save(client)
        internalClientId = client.id
    }

    private fun login(): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    /** authorize → 동의 페이지 리다이렉트 → 동의 제출 → 인가 코드. */
    private fun authorizeWithConsent(session: WebSession): String {
        // 쿼리는 원문 문자열로 구성한다 — GoogleLoginFlowIntegrationTest 와 동일하게 MockMvc 의
        // 사전 인코딩 값 이중 디코딩 문제를 피한다(값에 &/=/공백이 없어야 한다). 단일 비-openid scope(email)만
        // 요청해 공백 인코딩을 피하면서도 동의 화면을 유발한다(openid 단독은 SAS 가 동의 없이 코드 발급).
        val authorizeUrl =
            "/oauth2/authorize?response_type=code&client_id=$clientId" +
                "&redirect_uri=$redirectUri&scope=${OidcScopes.EMAIL}" +
                "&code_challenge=${pkceChallenge(verifier)}&code_challenge_method=S256&state=xyz"
        val toConsent =
            session
                .perform(
                    get(authorizeUrl).header("Accept", MediaType.TEXT_HTML_VALUE),
                ).andExpect(status().is3xxRedirection)
                .andReturn()

        val consentLocation = toConsent.response.redirectedUrl!!
        assertThat(consentLocation).contains("/oauth2/consent")
        val consentState = queryParam(consentLocation, "state")

        val approved =
            session
                .perform(
                    post("/oauth2/authorize")
                        .param("client_id", clientId)
                        .param("state", consentState)
                        .param("scope", OidcScopes.EMAIL),
                ).andExpect(status().is3xxRedirection)
                .andReturn()

        val callback = approved.response.redirectedUrl!!
        assertThat(callback).startsWith(redirectUri)
        return queryParam(callback, "code")
    }

    private fun exchangeCode(code: String) =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/oauth2/token")
                        .header("Authorization", basicAuth())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", verifier),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun refresh(refreshToken: String) =
        mockMvc.perform(
            post("/oauth2/token")
                .header("Authorization", basicAuth())
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken),
        )

    private fun basicAuth(): String = "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

    private fun queryParam(
        url: String,
        name: String,
    ): String {
        val query = url.substringAfter('?', "")
        val pair =
            query.split("&").firstOrNull { it.startsWith("$name=") }
                ?: error("no '$name' param in: $url")
        return URLDecoder.decode(pair.substringAfter("="), StandardCharsets.UTF_8)
    }

    private fun pkceChallenge(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
