package com.taspa.server.oidc

import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `/oauth2/authorize` 실패 처리 — **두 방향을 함께** 고정한다.
 *
 * `OAuth2ErrorPageHandler` 는 SAS 의 기본 실패 처리를 **통째로** 대체한다. 그래서 이 두 갈래가
 * 갈라지지 않으면 한쪽이 반드시 망가진다:
 *
 * 1. **리다이렉트 불가능한 오류**(미등록 client_id, redirect_uri 불일치) → 한국어 화면 + 서버 WARN.
 *    그전에는 Boot 기본 Whitelabel(영문·원인 무표시)로 끝나고 로그에도 흔적이 없어, 연동 담당자도
 *    플랫폼 관리자도 원인을 알 방법이 **존재하지 않았다**.
 * 2. ★**리다이렉트 가능한 오류**(사용자가 동의 화면에서 **거부**, 미등록 scope 등) → RP 로 되돌려
 *    보낸다(RFC 6749 §4.1.2.1). 이 갈래를 빠뜨렸다가 적대 리뷰에서 잡혔다 — 그 상태에서는 "거부"를
 *    누른 사용자가 앱으로 **돌아갈 길이 없고**, 연동 앱은 취소를 영영 통보받지 못한다.
 *    ★1번만 테스트하면 2번이 깨져도 초록불이다(정확히 그 상태로 배포될 뻔했다).
 */
class OAuth2AuthorizeErrorIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val clientId = "authorize-error-app"
    private val redirectUri = "http://localhost:18080/login/oauth2/code/taspa"
    private val email = "authorize-error@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        registerClient()
        if (userRepository.findByEmail(email) == null) {
            userRepository.save(
                User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        }
    }

    @Test
    fun `미등록 client_id 는 한국어 화면으로 끝난다(리다이렉트할 곳이 없다)`() {
        val body =
            mockMvc
                .perform(
                    get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", "no-such-client")
                        .param("redirect_uri", redirectUri)
                        .param("scope", OidcScopes.OPENID)
                        .header("Accept", "text/html"),
                ).andExpect(status().isBadRequest)
                .andReturn()
                .response.contentAsString

        assertThat(body).contains("로그인 요청을 처리할 수 없습니다")
        // 영문 Whitelabel 의 흔적이 남아 있으면 안 된다.
        assertThat(body).doesNotContain("Whitelabel")
    }

    @Test
    fun `redirect_uri 불일치도 되돌려 보내지 않는다(그 주소를 신뢰할 수 없다)`() {
        val body =
            mockMvc
                .perform(
                    get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", "http://evil.example.com/cb")
                        .param("scope", OidcScopes.OPENID)
                        .header("Accept", "text/html"),
                ).andExpect(status().isBadRequest)
                .andReturn()
                .response.contentAsString

        assertThat(body).contains("로그인 요청을 처리할 수 없습니다")
    }

    /**
     * ★이 테스트가 이 파일의 존재 이유다 — **등록된 redirect_uri 를 가진 오류는 앱으로 돌아가야 한다.**
     *
     * RFC 6749 §4.1.2.1: redirect_uri 와 client_id 가 검증된 뒤의 오류는 사용자 에이전트를 그 주소로
     * 돌려보내고 `error`·`error_description`·`state` 를 실어야 한다. 사용자가 동의를 거부했을 때,
     * 요청 scope 가 등록에 없을 때 등이 여기 해당한다.
     *
     * ★`OAuth2ErrorPageHandler` 는 SAS 의 기본 실패 처리를 통째로 대체하므로, 이 갈래를 직접 구현하지
     * 않으면 **전부 400 화면으로 삼켜진다** — 사용자는 앱으로 돌아갈 길이 없고 연동 앱은 실패를 영영
     * 통보받지 못한다. 실제로 그 상태를 만들었다가 적대 리뷰에서 잡혔다.
     *
     * 미등록 scope 로 검증하는 이유: 동의 화면을 흉내 내는 것보다 **결정적**이면서 같은 갈래를 지난다
     * (redirect_uri 는 이미 검증됐고 오류만 남은 상태).
     */
    @Test
    fun `되돌려 보낼 수 있는 오류는 RP 로 리다이렉트한다`() {
        // 쿼리는 원문 문자열로 — `.param()` 은 이 경로에서 사전 인코딩·이중 디코딩으로 유실된다.
        val authorizeUrl =
            "/oauth2/authorize?response_type=code&client_id=$clientId" +
                "&redirect_uri=$redirectUri&scope=not-a-registered-scope&state=xyz-state" +
                "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"

        val location =
            mockMvc
                .perform(get(authorizeUrl).header("Accept", "text/html"))
                .andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl ?: ""

        assertThat(location)
            .describedAs("검증된 redirect_uri 가 있는 오류는 RP 로 돌아가야 한다(화면으로 삼키면 앱으로 갈 길이 없다)")
            .startsWith(redirectUri)
        assertThat(location).contains("error=invalid_scope")
        // state 가 빠지면 RP 가 **어떤 요청의 실패인지** 짝지을 수 없다(CSRF 방어의 근거이기도 하다).
        assertThat(location).contains("state=xyz-state")
    }

    private fun registerClient() {
        registeredClientRepository.findByClientId(clientId)?.let { return }
        registeredClientRepository.save(
            RegisteredClient
                .withId("authorize-error-app-internal-id")
                .clientId(clientId)
                .clientName("Authorize Error App")
                .clientSecret(passwordEncoder.encode("secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(
                    ClientSettings
                        .builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build(),
                ).build(),
        )
    }
}
