package com.taspa.server.oidc

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Stage 5 Device Authorization Grant(RFC 8628) 사용자 화면 + 배선의 런타임 검증.
 *
 * 시나리오(왕복):
 *   POST /oauth2/device_authorization(client 인증) → user_code·device_code 발급
 *   → 사용자 로그인 → GET /activate?user_code=..(verification_uri_complete) → device_verification 리다이렉트
 *   → GET /oauth2/device_verification?user_code(브라우저 프리필) → 동의 페이지(/oauth2/consent) 리다이렉트
 *   → GET /oauth2/consent(동의 UI 렌더링) → device 분기·formAction·user_code·거부 폼 검증
 *   → POST /oauth2/device_verification(client_id·state·user_code·scope=허용) → 승인
 *   → POST /oauth2/token(grant_type=device_code, device_code) → access_token 발급.
 *
 * 추가 불변식:
 *   - 미인증 /activate 는 /login 게이트로 유도된다(permitAll 아님).
 *   - 잘못된 user_code 는 device_verification 이 400 으로 거부한다(SAS 기본 오류 응답).
 *   - 거부(scope 없이 제출)는 device_code 를 무효화해 토큰 발급이 access_denied 로 실패한다.
 */
class DeviceAuthorizationGrantIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "device@example.com"
    private val password = "SecureP@ssw0rd123"
    private val clientId = "device-app"
    private val clientSecret = "device-secret"

    @BeforeEach
    fun setUp() {
        // 공유 컨테이너 — device_code/user_code 는 oauth2_authorization 에 저장되므로 매 테스트 초기화한다.
        jdbcTemplate.update("DELETE FROM oauth2_authorization")
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent")
        userRepository.deleteAll()
        userRepository.save(User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true))
        registerDeviceClient()
    }

    @Test
    fun `device flow round-trip issues an access token after user approves`() {
        val (userCode, deviceCode) = requestDeviceAuthorization()
        val session = login()

        // verification_uri_complete 진입(/activate?user_code=..) → device_verification 으로 넘어간다.
        val activateRedirect =
            session
                .perform(
                    get("/activate").param("user_code", userCode).header("Accept", MediaType.TEXT_HTML_VALUE),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl!!
        assertThat(activateRedirect).contains("/oauth2/device_verification")
        assertThat(activateRedirect).contains("user_code=")

        // 프리필 경로: 브라우저는 activate 리다이렉트를 GET 으로 따라간다(POST 폼 제출이 아니다).
        // SAS device_verification 이 GET+user_code 를 매칭해 동의 페이지(/oauth2/consent)로 넘긴다.
        // GET 경로의 user_code 는 반드시 쿼리스트링에 실어야 한다 — SAS converter 가 GET 요청에서는
        // getQueryParameters(=raw query string)만 읽으므로 .param(폼 파라미터)만으로는 인식되지 않는다.
        val consentRedirect =
            session
                .perform(
                    get("/oauth2/device_verification?user_code={code}", userCode)
                        .header("Accept", MediaType.TEXT_HTML_VALUE),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl!!
        assertThat(consentRedirect).contains("/oauth2/consent")
        val state = queryParam(consentRedirect, "state")
        val consentScope = queryParam(consentRedirect, "scope")

        // 실제 동의 UI 렌더링(ConsentController device 분기)을 직접 검증한다 — SAS 백엔드 배선이 아니라
        // 사용자 화면 회귀를 잡는다: deviceFlow 판정 → formAction=/oauth2/device_verification,
        // user_code hidden input, 기기 코드 표시, 별도 '거부' sibling 폼이 모두 렌더링돼야 한다.
        session
            .perform(
                get("/oauth2/consent")
                    .param("client_id", clientId)
                    .param("scope", consentScope)
                    .param("state", state)
                    .param("user_code", userCode)
                    .header("Accept", MediaType.TEXT_HTML_VALUE),
            ).andExpect(status().isOk)
            .andExpect(content().string(containsString("action=\"/oauth2/device_verification\"")))
            .andExpect(content().string(containsString("name=\"user_code\"")))
            .andExpect(content().string(containsString(userCode)))
            .andExpect(content().string(containsString("거부")))

        // 허용 제출(profile·email scope) → 승인 처리.
        session
            .perform(
                post("/oauth2/device_verification")
                    .param("client_id", clientId)
                    .param("state", state)
                    .param("user_code", userCode)
                    .param("scope", OidcScopes.PROFILE, OidcScopes.EMAIL),
            ).andExpect(status().is3xxRedirection)

        // device_code 토큰 교환 → access_token 발급.
        val tokenResponse =
            objectMapper.readTree(
                mockMvc
                    .perform(
                        post("/oauth2/token")
                            .header("Authorization", basicAuth())
                            .param("grant_type", AuthorizationGrantType.DEVICE_CODE.value)
                            .param("device_code", deviceCode),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(tokenResponse.get("access_token")).isNotNull
        assertThat(tokenResponse.get("access_token").asText()).isNotBlank
        assertThat(tokenResponse.get("scope").asText()).contains(OidcScopes.PROFILE)
    }

    @Test
    fun `activate requires authentication and redirects unauthenticated visitors to login`() {
        val location =
            mockMvc
                .perform(
                    get("/activate").header("Accept", MediaType.TEXT_HTML_VALUE),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl!!
        assertThat(location).contains("/login")
    }

    @Test
    fun `invalid user code is rejected at the device verification endpoint`() {
        requestDeviceAuthorization() // 유효한 코드가 존재해도 위조 코드는 매칭되지 않는다.
        val session = login()

        session
            .perform(
                post("/oauth2/device_verification").param("user_code", "ZZZZ-ZZZZ"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `denying consent invalidates the device code so the token request fails`() {
        val (userCode, deviceCode) = requestDeviceAuthorization()
        val session = login()

        session
            .perform(post("/oauth2/device_verification").param("user_code", userCode))
            .andExpect(status().is3xxRedirection)
        val consentRedirect =
            session
                .perform(
                    post("/oauth2/device_verification").param("user_code", userCode),
                ).andReturn()
                .response.redirectedUrl!!
        val state = queryParam(consentRedirect, "state")

        // scope 없이 제출 = 거부 → SAS 가 device_code 를 무효화한다.
        session.perform(
            post("/oauth2/device_verification")
                .param("client_id", clientId)
                .param("state", state)
                .param("user_code", userCode),
        )

        val error =
            objectMapper.readTree(
                mockMvc
                    .perform(
                        post("/oauth2/token")
                            .header("Authorization", basicAuth())
                            .param("grant_type", AuthorizationGrantType.DEVICE_CODE.value)
                            .param("device_code", deviceCode),
                    ).andReturn()
                    .response.contentAsString,
            )
        assertThat(error.get("error").asText()).isEqualTo("access_denied")
    }

    // ---- helpers ----

    private fun registerDeviceClient() {
        registeredClientRepository.findByClientId(clientId)?.let { return }
        registeredClientRepository.save(
            RegisteredClient
                .withId("device-app-internal-id")
                .clientId(clientId)
                .clientName("Device App")
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                // 동의 필요 → device_verification 이 사용자 동의 화면으로 유도한다(자동 승인 방지).
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
                .build(),
        )
    }

    /** RFC 8628 device authorization 요청 → (user_code, device_code). */
    private fun requestDeviceAuthorization(): Pair<String, String> {
        val response =
            objectMapper.readTree(
                mockMvc
                    .perform(
                        post("/oauth2/device_authorization")
                            .header("Authorization", basicAuth())
                            .param("scope", "${OidcScopes.OPENID} ${OidcScopes.PROFILE} ${OidcScopes.EMAIL}"),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        val userCode = response.get("user_code").asText()
        val deviceCode = response.get("device_code").asText()
        assertThat(userCode).isNotBlank
        assertThat(deviceCode).isNotBlank
        return userCode to deviceCode
    }

    private fun login(): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
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
}
