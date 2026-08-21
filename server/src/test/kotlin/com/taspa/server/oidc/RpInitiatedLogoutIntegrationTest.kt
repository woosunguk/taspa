package com.taspa.server.oidc

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Stage 4 SLO(RP-Initiated Logout) 의 OP(taspa) 측 런타임 검증.
 *
 * e2e(sso-flow.spec.ts test #3)는 demo-client·taspa 가 함께 떠 있어야만 돌아가고 CI 에서 자동
 * 실행되지 않으므로, taspa 의 end_session 경로(/connect/logout)가 실제로 동작함을 이 통합 테스트로
 * 고정한다. 시나리오:
 *   로그인 → authorization_code(+PKCE, openid) → 토큰 교환(id_token 수령)
 *   → GET /connect/logout?id_token_hint=..&post_logout_redirect_uri=..&state=..
 *   → (1) 등록된 post_logout_redirect_uri 로 302 왕복(+state 보존)
 *      (2) OP SSO 세션 무효화(로그아웃 후 보호 자원 접근이 로그인으로 유도).
 *
 * 핵심 회귀 방지: id_token 의 sub 는 users.id(UUID)인데(Stage 1 TokenCustomizer), OP 세션 principal
 * 이름은 이메일이다. SAS OidcLogoutAuthenticationProvider 는 sub 를 이름과 직접 비교하지 않고
 * (hasText 가드만 두고) "현재 세션 principal 이름 == 인가에 저장된 principal 이름"(둘 다 이메일)만
 * 비교하므로 로그아웃이 성립한다 — 이 불변식을 런타임으로 못박는다.
 */
class RpInitiatedLogoutIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    // 로그인 알림 등 부수 메일 발송이 실제 SMTP 로 나가지 않도록 대체(relaxed — 스텁 불필요).
    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "slo@example.com"
    private val password = "SecureP@ssw0rd123"
    private val clientId = "slo-app"
    private val clientSecret = "slo-secret"
    private val redirectUri = "http://localhost:8080/login/oauth2/code/taspa"
    private val postLogoutRedirectUri = "http://localhost:8080/"
    private val verifier = "taspa-pkce-verifier-abcdefghijklmnopqrstuvwxyz-0123456789"

    @BeforeEach
    fun setUp() {
        // 공유 컨테이너라 인가/동의 행이 테스트 간 잔존한다 — 매 테스트 초기화(AuthorizedClientIntegrationTest 와 동일 이유).
        jdbcTemplate.update("DELETE FROM oauth2_authorization")
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent")
        userRepository.deleteAll()
        userRepository.save(User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true))
        registerClient()
    }

    @Test
    fun `rp-initiated logout ends op sso session and redirects to registered post_logout_redirect_uri`() {
        val session = login()
        // 로그아웃 전: OP SSO 세션이 성립해 보호 자원 접근이 200.
        session.perform(get("/account")).andExpect(status().isOk)

        val idToken = idTokenFor(authorizeCode(session))

        // RP-Initiated Logout — 브라우저가 리다이렉트로 도달하는 GET /connect/logout.
        // SAS OidcLogoutAuthenticationConverter 는 GET 요청 파라미터를 쿼리스트링에서 읽으므로
        // (servlet 파라미터맵의 .param() 이 아니라) 값을 원문 쿼리로 실어야 한다. id_token(JWT, base64url)·
        // URI·state 모두 &/=/공백을 포함하지 않아 인코딩 없이 그대로 둔다(authorizeUrl 과 동일 규칙).
        val logout =
            session
                .perform(
                    get(
                        "/connect/logout?id_token_hint=$idToken" +
                            "&post_logout_redirect_uri=$postLogoutRedirectUri&state=logout-xyz",
                    ),
                ).andExpect(status().is3xxRedirection)
                .andReturn()

        // 검증 1: 등록된 post_logout_redirect_uri 로 왕복하며 state 를 그대로 되돌려준다.
        val location = logout.response.redirectedUrl!!
        assertThat(location).startsWith(postLogoutRedirectUri)
        assertThat(queryParam(location, "state")).isEqualTo("logout-xyz")

        // 검증 2: OP SSO 세션이 실제로 무효화됐다 — 로그아웃 후 보호 자원 접근은 로그인으로 유도된다
        // (e2e test #3 의 "재로그인 시 taspa 재인증 요구"에 대응하는 OP 측 불변식).
        session.perform(get("/account")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `logout with unregistered post_logout_redirect_uri is rejected (open-redirect blocked)`() {
        val session = login()
        val idToken = idTokenFor(authorizeCode(session))

        // 미등록 URI 로의 post_logout_redirect_uri 는 SAS 가 정확 일치 검증으로 거부한다(오픈 리다이렉트 차단).
        session
            .perform(
                get(
                    "/connect/logout?id_token_hint=$idToken" +
                        "&post_logout_redirect_uri=http://evil.example.com/&state=logout-xyz",
                ),
            ).andExpect(status().isBadRequest)
    }

    // ---- helpers ----

    private fun registerClient() {
        // registered_client 는 users 와 무관해 공유 컨테이너에서 잔존하므로 이미 있으면 재사용한다.
        registeredClientRepository.findByClientId(clientId)?.let { return }
        registeredClientRepository.save(
            RegisteredClient
                .withId("slo-app-internal-id")
                .clientId(clientId)
                .clientName("SLO App")
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                // RP-Initiated Logout 복귀 지점 — SAS 는 이 값을 등록 목록과 정확 일치로 검증한다.
                .postLogoutRedirectUri(postLogoutRedirectUri)
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

    private fun login(): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    /**
     * authorize → 인가 코드. openid 단독 scope 는 requireAuthorizationConsent=true 라도 SAS 가 동의
     * 없이 코드를 발급한다(isAuthorizationConsentRequired: openid 단독은 예외) — id_token 수령을 위해
     * openid 를 요청하면서 동의 페이지 우회로 테스트를 단순화한다.
     */
    private fun authorizeCode(session: WebSession): String {
        // 쿼리는 원문 문자열로 구성한다(값에 &/=/공백 없음) — MockMvc 의 사전 인코딩 이중 디코딩 회피.
        val authorizeUrl =
            "/oauth2/authorize?response_type=code&client_id=$clientId" +
                "&redirect_uri=$redirectUri&scope=${OidcScopes.OPENID}" +
                "&code_challenge=${pkceChallenge(verifier)}&code_challenge_method=S256&state=xyz"
        val callback =
            session
                .perform(
                    get(authorizeUrl).header("Accept", MediaType.TEXT_HTML_VALUE),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl!!
        assertThat(callback).startsWith(redirectUri)
        assertThat(callback).contains("code=")
        return queryParam(callback, "code")
    }

    private fun idTokenFor(code: String): String {
        val tokenResponse =
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
        val idToken = tokenResponse.get("id_token")
        assertThat(idToken).withFailMessage("openid scope 인데 id_token 이 없음: %s", tokenResponse).isNotNull
        return idToken.asText()
    }

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
