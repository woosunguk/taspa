package com.taspa.examples.democlient

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler

@Configuration
@EnableWebSecurity
class SecurityConfig {

    private companion object {
        // taspa 의 end_session_endpoint(= issuer + /connect/logout). application.yml 은 부팅 독립성을
        // 위해 issuer-uri 대신 엔드포인트를 명시하는 방식이라 OIDC 디스커버리로 end_session_endpoint 를
        // 받지 못한다. 그래서 RP-Initiated Logout 핸들러에만 이 값을 configurationMetadata 로 주입한
        // 보강 레지스트리를 따로 만든다(oauth2Login 은 원본 빈을 그대로 쓴다 — 디스커버리 호출 없음).
        const val TASPA_END_SESSION_ENDPOINT = "http://localhost:9100/connect/logout"
    }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        clientRegistrationRepository: ClientRegistrationRepository,
    ): SecurityFilterChain {
        // taspa 의 demo-app 은 requireProofKey(true) 로 시딩된다(RegisteredClientConfig).
        // Spring Security 는 공개(public) 클라이언트에만 PKCE 를 기본 적용하므로, 기밀 클라이언트인
        // demo-app 도 code_challenge 를 보내도록 인가 요청에 PKCE 를 명시적으로 붙인다 —
        // 없으면 taspa /oauth2/authorize 가 요청을 거부한다.
        val authorizationRequestResolver = DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI,
        ).apply {
            setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        }

        http
            .authorizeHttpRequests {
                it.requestMatchers("/", "/error").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2.authorizationEndpoint { it.authorizationRequestResolver(authorizationRequestResolver) }
            }
            // 로그아웃은 두 가지를 제공한다(me.html 의 두 버튼):
            //  (1) 로컬 로그아웃(기본, slo 파라미터 없음, action="/logout"): 이 앱의 세션만 종료 → "/".
            //      taspa(9100) 의 SSO 세션은 유지되어 다시 로그인하면 재인증 없이 즉시 복귀한다.
            //  (2) SLO(action="/logout?slo=true"): RP-Initiated Logout — taspa /connect/logout 으로
            //      id_token_hint + post_logout_redirect_uri 를 보내 OP SSO 세션까지 종료한 뒤 이 앱으로
            //      복귀한다. 두 버튼을 서로 다른 form action 으로 구분해 로컬 로그아웃 시나리오와
            //      SLO 시나리오를 각각 e2e(sso-flow.spec.ts)로 고정한다.
            //
            // 참고(비대칭 지원): OP→RP 로 logout_token 을 push 하는 Back-Channel Logout 은 Spring
            // Authorization Server 1.4.2 가 미지원이라 여기서 다루지 않는다(docs/integration-guide.md
            // "로그아웃" 절 참고). RP 수신부(http.oidcLogout().backChannel())는 기성이지만 OP 송신부가
            // 없어 실효가 없으므로 배선하지 않는다.
            .logout {
                it.logoutSuccessHandler(logoutSuccessHandler(clientRegistrationRepository))
            }
        return http.build()
    }

    /**
     * slo=true 요청은 RP-Initiated Logout(OP SSO 세션까지 종료)으로, 그 외는 로컬 로그아웃("/")으로
     * 분기한다. RP-Initiated 핸들러에는 end_session_endpoint 를 주입한 보강 레지스트리를 넘긴다 —
     * application.yml 이 issuer-uri 를 쓰지 않아(부팅 독립성) 원본 등록의 디스커버리 메타데이터가
     * 비어 있기 때문이다. LogoutFilter 는 로컬 세션을 무효화한 뒤 로그아웃 직전의 Authentication 을
     * 이 핸들러에 넘기므로 OidcUser 의 id_token(=id_token_hint)에 여전히 접근할 수 있다.
     */
    private fun logoutSuccessHandler(
        clientRegistrationRepository: ClientRegistrationRepository,
    ): LogoutSuccessHandler {
        val localLogout = SimpleUrlLogoutSuccessHandler().apply { setDefaultTargetUrl("/") }
        val taspa = clientRegistrationRepository.findByRegistrationId("taspa")
            ?: return localLogout // taspa 등록이 없으면 로컬 로그아웃만(방어적 — 데모에선 항상 존재)
        val logoutRepository = InMemoryClientRegistrationRepository(
            ClientRegistration.withClientRegistration(taspa)
                .providerConfigurationMetadata(mapOf("end_session_endpoint" to TASPA_END_SESSION_ENDPOINT))
                .build(),
        )
        val rpInitiatedLogout = OidcClientInitiatedLogoutSuccessHandler(logoutRepository).apply {
            // 종료 후 이 앱 홈으로 복귀. {baseUrl} 은 http://localhost:8080 으로 확장되며, taspa 에
            // demo-app 의 post_logout_redirect_uri(http://localhost:8080/)로 등록돼 있어야 한다.
            setPostLogoutRedirectUri("{baseUrl}/")
        }
        return LogoutSuccessHandler { request, response, authentication ->
            if (isSingleLogout(request) && authentication is OAuth2AuthenticationToken) {
                rpInitiatedLogout.onLogoutSuccess(request, response, authentication)
            } else {
                localLogout.onLogoutSuccess(request, response, authentication)
            }
        }
    }

    private fun isSingleLogout(request: HttpServletRequest): Boolean =
        request.getParameter("slo")?.equals("true", ignoreCase = true) == true
}
