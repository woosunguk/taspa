package com.taspa.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.enterprise.DbRelyingPartyRegistrationRepository
import com.taspa.server.enterprise.Saml2FederatedLoginSuccessHandler
import com.taspa.server.federation.FederatedLoginSuccessHandler
import com.taspa.server.federation.FederatedOAuth2UserService
import com.taspa.server.federation.FederatedOidcUserService
import com.taspa.server.iam.AdminSurface
import com.taspa.server.login.LoginFailureHandler
import com.taspa.server.login.LoginUserDetailsService
import com.taspa.server.login.MfaAwareAuthenticationSuccessHandler
import com.taspa.server.oidc.OAuth2ErrorPageHandler
import com.taspa.server.passkey.AccountStatusCheckingAuthenticationProvider
import com.taspa.server.passkey.JdbcCreationOptionsRepository
import com.taspa.server.passkey.PasskeyAuthenticationSuccessHandler
import com.taspa.server.passkey.PasskeyCreationOptionsFilter
import com.taspa.server.passkey.WebAuthnProperties
import com.taspa.server.stepup.StepUpEnforcementFilter
import com.taspa.server.stepup.StepUpProperties
import jakarta.servlet.Filter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.access.ExceptionTranslationFilter
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.savedrequest.RequestCache
import org.springframework.security.web.util.matcher.AndRequestMatcher
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher
import org.springframework.security.web.util.matcher.NegatedRequestMatcher
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationProvider
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsFilter
import org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    // prod 전용: CSP·HSTS·Referrer-Policy·frame-options 방출 여부(application-prod.yml 에서 true).
    // dev/test 는 기본 false 라 기존 응답 헤더가 바뀌지 않는다.
    @Value("\${taspa.security.headers.enabled:false}")
    private val securityHeadersEnabled: Boolean,
    /** 리다이렉트 불가 인가 오류의 한국어 화면 + WARN 로그. 근거는 그 클래스 KDoc. */
    private val oAuth2ErrorPageHandler: OAuth2ErrorPageHandler,
) {
    companion object {
        /**
         * 로그인 성공 후 "돌아갈 곳"이 될 수 없는 경로 — 인증 플로우 자신의 화면들.
         * 근거는 [requestCache] KDoc.
         */
        private val AUTH_FLOW_PATHS = setOf("/login", "/logout", "/reauth", "/signup", "/error")

        /**
         * IdP 로그인/동의/관리 UI 용 CSP. 템플릿이 first-party 인라인 <script>/style= 를 쓰므로
         * script-src/style-src 에 'unsafe-inline' 을 허용한다(외부 오리진 스크립트·플러그인·프레이밍은
         * 차단). nonce 기반으로의 강화는 향후 과제.
         */
        private const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; " +
                "font-src 'self'; " +
                "connect-src 'self'; " +
                "object-src 'none'; " +
                "base-uri 'self'; " +
                "frame-ancestors 'none'; " +
                "form-action 'self'"

        private const val HSTS_MAX_AGE_SECONDS = 31_536_000L // 1년
    }

    /**
     * prod 보안 응답 헤더. 플래그가 꺼져 있으면(dev/test) 아무 것도 적용하지 않아 기존 동작을 보존한다.
     * HSTS 는 secure 요청에서만 방출되므로 forward-headers-strategy(request.isSecure 정상화)와 함께 유효.
     */
    private fun applySecurityHeaders(http: HttpSecurity) {
        if (!securityHeadersEnabled) return
        http.headers { headers ->
            headers
                .contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                .httpStrictTransportSecurity {
                    it.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                }.referrerPolicy {
                    it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                }.frameOptions { it.deny() }
        }
    }

    @Bean
    @Order(1)
    fun authorizationServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val authorizationServerConfigurer = OAuth2AuthorizationServerConfigurer.authorizationServer()
        http
            .securityMatcher(authorizationServerConfigurer.endpointsMatcher)
            .with(authorizationServerConfigurer) { configurer ->
                configurer
                    .oidc(Customizer.withDefaults())
                    .authorizationEndpoint {
                        it.consentPage("/oauth2/consent")
                        /*
                         * ★리다이렉트로 돌려보낼 수 없는 오류(미등록 client_id·redirect_uri 불일치)의
                         * 화면과 로그. 이 한 줄이 없으면 Boot 기본 Whitelabel(영문, 원인 무표시)로 끝나고
                         * 서버 로그·감사로그 어디에도 흔적이 남지 않는다 — 연동 담당자도 플랫폼 관리자도
                         * 원인을 알 방법이 없었다. 근거는 OAuth2ErrorPageHandler KDoc.
                         */
                        it.errorResponseHandler(oAuth2ErrorPageHandler)
                    }
                    // Device Authorization Grant(Stage 5) — SAS 공식 1.4.2 device-flow 샘플 배선.
                    // (1) device_authorization 응답의 verification_uri 를 커스텀 사용자 화면(/activate)로
                    //     지정한다(기본값은 /oauth2/device_verification 로, 브라우저 UX 가 없다).
                    //     verification_uri_complete 는 SAS 가 /activate?user_code=XXX 로 자동 생성한다.
                    // (2) device_verification 의 동의 화면을 기존 커스텀 동의 페이지(/oauth2/consent)로
                    //     재사용한다 — ConsentController 가 user_code 존재 시 device 문맥으로 렌더링하고
                    //     폼을 /oauth2/device_verification 로 제출한다(authorization_code 는 /oauth2/authorize).
                    .deviceAuthorizationEndpoint { it.verificationUri("/activate") }
                    .deviceVerificationEndpoint {
                        it.consentPage("/oauth2/consent")
                        /*
                         * ★승인 완료 후 착지 URL 을 **의미 있는 경로**로 고정한다.
                         *
                         * SAS 기본값은 `/?success` 인데, 공개 도메인이 웹 티어(SPA)면 `/` 는 SPA 소유라
                         * 기기를 승인한 사용자가 **확인 화면 대신 SPA 홈**에 떨어진다 — 승인이 됐는지
                         * 알 길이 없어 TV 앞에서 코드를 다시 입력하게 된다.
                         *
                         * 프록시 규칙으로 `/?success` 를 잡으려 했지만 Next 의 쿼리 매칭(`has`)은
                         * **값 없는 파라미터**(`?success`)를 매칭하지 않아 규칙이 조용히 무시됐다(실측).
                         * 매직 쿼리에 의존하는 대신 URL 자체를 `/activated` 로 바꾸는 편이 옳다 —
                         * 그 경로는 이미 서버가 소유하고 프록시 목록에도 있다.
                         */
                        it.deviceVerificationResponseHandler { _, response, _ ->
                            response.sendRedirect("/activated")
                        }
                    }
            }.authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling {
                it.defaultAuthenticationEntryPointFor(
                    LoginUrlAuthenticationEntryPoint("/login"),
                    MediaTypeRequestMatcher(MediaType.TEXT_HTML),
                )
            }
        applySecurityHeaders(http)
        return http.build()
    }

    /**
     * SCIM 프로비저닝(/scim/v2 이하) 전용 리소스 서버 체인(조직 3c) — **베어러 JWT 전용**.
     *  - 세션 없음(STATELESS): securityContextRepository 를 세션에 연결하지 않으므로 로그인 세션 쿠키로는
     *    절대 인증되지 않는다(사용자 세션 거부는 체인 수준에서 보장, 위임 토큰 거부는 ScimAuthorization).
     *  - csrf disable: Authorization 헤더 기반이라 ambient 쿠키가 없어 CSRF 위조가 성립하지 않는다
     *    (소비 이벤트 적재 면제와 동일 근거 — orgApi 체인 주석 참고).
     *  - @Order(0): 기존 체인 번호(1 AS·2 orgApi·3 default)를 바꾸지 않고 새 번호를 삽입한다.
     *    securityMatcher(/scim/v2 이하)가 AS endpointsMatcher·/api/orgs 이하와 겹치지 않아 상대 순서는
     *    동작에 영향이 없다(어느 위치든 이 체인만 매칭) — 무손상 삽입.
     *  - 세부 인가(org.scim scope·org_id 결속·사용자 토큰 거부)는 ScimAuthorization 이 수행한다.
     */
    @Bean
    @Order(0)
    fun scimApiSecurityFilterChain(
        http: HttpSecurity,
        jwkSource: JWKSource<SecurityContext>,
        @Value("\${taspa.issuer-uri}") issuerUri: String,
    ): SecurityFilterChain {
        http
            .securityMatcher("/scim/v2/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { rs ->
                rs.jwt { jwt -> jwt.decoder(orgApiJwtDecoder(jwkSource, issuerUri)) }
            }
        applySecurityHeaders(http)
        return http.build()
    }

    /**
     * 가맹 redeem API(/api/merchant 이하) 전용 리소스 서버 체인(식권 L1) — **베어러 JWT 전용**(SCIM 패턴).
     *  - 세션 없음(STATELESS): 로그인 세션 쿠키로는 절대 인증되지 않는다 — POS(M2M client_credentials)만.
     *  - csrf disable: Authorization 헤더 기반이라 ambient 쿠키가 없어 CSRF 위조가 성립하지 않는다.
     *  - @Order(-1): 기존 체인 번호(0 SCIM·1 AS·2 orgApi·3 default)를 바꾸지 않고 새 번호를 삽입한다.
     *    securityMatcher(/api/merchant 이하)가 다른 어떤 체인 매처와도 겹치지 않아 상대 순서는 무의미
     *    (disjoint — 무손상 삽입).
     *  - 세부 인가(meal.redeem scope·merchant_id 클레임 결속·가맹 ACTIVE)는 MerchantRedeemController 가 수행한다.
     */
    @Bean
    @Order(-1)
    fun merchantApiSecurityFilterChain(
        http: HttpSecurity,
        jwkSource: JWKSource<SecurityContext>,
        @Value("\${taspa.issuer-uri}") issuerUri: String,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/merchant/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { rs ->
                rs.jwt { jwt -> jwt.decoder(orgApiJwtDecoder(jwkSource, issuerUri)) }
            }
        applySecurityHeaders(http)
        return http.build()
    }

    /**
     * 조직 스코프 API(/api/orgs/ 이하) 전용 리소스 서버 체인(Phase 0-E). 두 방식의 인증을 함께 지원한다:
     *  - M2M 베어러 JWT(client_credentials + calendar.read) — BearerTokenAuthenticationFilter 가 검증한다.
     *  - 로그인 세션(org 멤버/ADMIN) — 기존 세션 SecurityContext 를 그대로 읽는다(베어러 없을 때).
     * 컨트롤러(CalendarEventController)가 org 격리·scope 를 세밀하게 인가한다. 이 체인은 GET 조회뿐 아니라
     * 세션 인증 상태변경(멤버 역할/제거·초대 생성/재발송/취소)도 호스팅하므로, 기본 체인의 /api/sessions·
     * /api/admin 과 동일한 2계층 표준대로 CSRF 를 강제한다(SameSite=Lax 단일 계층 의존 금지). 단, M2M 베어러
     * 전용 쓰기(소비 이벤트 적재)만 CSRF 에서 면제한다 — Authorization 헤더 기반이라 ambient 쿠키가 없어 CSRF
     * 위조가 성립하지 않고, 프로그램적 생산자는 CSRF 토큰을 가질 수 없다.
     * JwtDecoder 는 AS 와 동일한 JWKSource 로 서명을 검증하고 issuer 를 강제한다(자체 발급 토큰만 수용).
     * 이 체인은 /api/orgs/ 이하에만 매칭되어 기존 default 체인·AS 체인과 독립적이다(무손상).
     */
    @Bean
    @Order(2)
    fun orgApiSecurityFilterChain(
        http: HttpSecurity,
        jwkSource: JWKSource<SecurityContext>,
        securityContextRepository: HttpSessionSecurityContextRepository,
        @Value("\${taspa.issuer-uri}") issuerUri: String,
        apiEntryPoint: ApiAuthenticationEntryPoint,
        apiAccessDeniedHandler: ApiAccessDeniedHandler,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/orgs/**")
            .cors(Customizer.withDefaults())
            .csrf {
                // 세션 인증 상태변경(멤버/초대 관리)은 앱 표준(2계층)대로 CSRF 를 강제한다. M2M 베어러
                // 전용 쓰기(소비 이벤트 적재, POST /api/orgs/*/consumption-events)만 면제한다 — 콘솔 JS 는
                // 이미 X-CSRF-TOKEN 헤더를 실어 보낸다(templates/console/orgs.html). GET 조회는 CsrfFilter
                // 기본 제외라 영향 없다.
                it.ignoringRequestMatchers(AntPathRequestMatcher("/api/orgs/*/consumption-events"))
            }.securityContext { it.securityContextRepository(securityContextRepository) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { rs ->
                rs.jwt { jwt -> jwt.decoder(orgApiJwtDecoder(jwkSource, issuerUri)) }
            }
            /*
             * ★**이 체인에도 같은 401/403 계약을 건다.**
             *
             * 이 경로는 **별도 체인**이라 기본 체인(@Order 3)에 건 진입점·거부 핸들러가 닿지
             * 않는다. 그런데 SPA 상태변경의 대부분(구성원·초대·부서·식대정책·역할)이 바로 이 경로다 —
             * 여기를 빠뜨리면 "세션 만료 후 저장 → 영문 Forbidden(403), 로그인 이동 없음"이 **가장 많이
             * 쓰는 화면들에서만** 그대로 남는다. 적대 리뷰가 정확히 이 누락을 잡았다.
             *
             * 리소스 서버가 붙어 있어 기본 진입점이 베어러 401(WWW-Authenticate)인데, 이 경로의 실사용은
             * **세션 쿠키**다. 우리 스키마의 401 JSON 으로 통일해야 프런트가 한 갈래로 처리한다.
             */
            .exceptionHandling {
                it.authenticationEntryPoint(apiEntryPoint).accessDeniedHandler(apiAccessDeniedHandler)
            }
        applySecurityHeaders(http)
        return http.build()
    }

    /**
     * 조직 API 리소스 서버용 JwtDecoder. AS 가 발급한 self-contained JWT access_token 을 같은 JWKSource(RS256)로
     * 검증하고, issuer 를 taspa.issuer-uri 로 강제해 외부 토큰을 배제한다. nimbus 기본 클레임 검증기는 끄고
     * Spring 의 OAuth2TokenValidator(exp·nbf·iss)로 일원화한다.
     */
    private fun orgApiJwtDecoder(
        jwkSource: JWKSource<SecurityContext>,
        issuerUri: String,
    ): JwtDecoder {
        val processor = DefaultJWTProcessor<SecurityContext>()
        processor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
        processor.setJWTClaimsSetVerifier { _, _ -> }
        val decoder = NimbusJwtDecoder(processor)
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri))
        return decoder
    }

    @Bean
    @Order(3)
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        securityContextRepository: HttpSessionSecurityContextRepository,
        successHandler: MfaAwareAuthenticationSuccessHandler,
        failureHandler: LoginFailureHandler,
        webAuthnProperties: WebAuthnProperties,
        relyingPartyOperations: WebAuthnRelyingPartyOperations,
        loginUserDetailsService: LoginUserDetailsService,
        userRepository: UserRepository,
        accountLockoutService: AccountLockoutService,
        auditEventService: AuditEventService,
        passkeySuccessHandler: PasskeyAuthenticationSuccessHandler,
        clientRegistrationRepositoryProvider: ObjectProvider<ClientRegistrationRepository>,
        federatedLoginSuccessHandler: FederatedLoginSuccessHandler,
        federatedOAuth2UserService: FederatedOAuth2UserService,
        federatedOidcUserService: FederatedOidcUserService,
        authorizationCodeTokenResponseClient: OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>,
        stepUpProperties: StepUpProperties,
        objectMapper: ObjectMapper,
        jdbcCreationOptionsRepository: JdbcCreationOptionsRepository,
        relyingPartyRegistrationRepository: DbRelyingPartyRegistrationRepository,
        saml2SuccessHandler: Saml2FederatedLoginSuccessHandler,
        apiEntryPoint: ApiAuthenticationEntryPoint,
        apiAccessDeniedHandler: ApiAccessDeniedHandler,
    ): SecurityFilterChain {
        http
            .cors(Customizer.withDefaults())
            .csrf {
                // /api/** 는 기존대로 CSRF 면제하되, 파괴적 세션 API(/api/sessions/**)는 보호를
                // 유지한다 — 원격 로그아웃이 SameSite=Lax 단일 계층에만 의존하지 않게 한다
                // (동일 등록 도메인의 서브도메인發 same-site 요청은 Lax 가 막지 못한다).
                // /api/admin/** 도 같은 이유로 면제에서 제외한다 — 관리자 변경 작업(정지·클라이언트
                // 삭제 등)이 CSRF 단일 계층 없이 노출되면 안 된다.
                // 계정/관리 페이지는 meta 태그의 토큰을 X-CSRF-TOKEN 헤더로 보낸다(webauthn 등록과 동일 패턴).
                it.ignoringRequestMatchers(
                    AndRequestMatcher(
                        AntPathRequestMatcher("/api/**"),
                        NegatedRequestMatcher(AntPathRequestMatcher("/api/sessions/**")),
                        NegatedRequestMatcher(AntPathRequestMatcher("/api/admin/**")),
                    ),
                    // SAML ACS 는 조직 IdP 가 다른 오리진에서 SAMLResponse 를 form POST 하는 지점이라
                    // CSRF 토큰이 없다. 응답 서명(OpenSaml4AuthenticationProvider) 검증이 진위를 보장한다.
                    AntPathRequestMatcher("/login/saml2/sso/**"),
                )
            }.authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/api/accounts/signup",
                        // health(및 liveness/readiness 프로브)·info 는 익명 허용. prometheus 는 여기 없고
                        // 아래에서 ADMIN 전용으로 제한한다(prod 에서는 관리 포트/네트워크 정책으로 추가 격리).
                        "/actuator/health/**",
                        "/actuator/info",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/login",
                        "/login/identifier",
                        "/login/password",
                        "/login/mfa",
                        "/login/verify-email",
                        "/login/verify-email/resend",
                        // 리스크 챌린지: 게이트 페이지(부분 인증 상태 — SecurityContext 없음).
                        "/login/risk-challenge",
                        "/login/risk-challenge/resend",
                        // 패스키: 인증 옵션/어서션 엔드포인트는 익명 접근이 전제(usernameless 로그인).
                        // /webauthn/register* 는 anyRequest().authenticated() 로 보호된다.
                        "/login/passkey",
                        "/login/webauthn",
                        "/webauthn/authenticate/options",
                        // 소셜 로그인: 버튼 진입점(/oauth2/authorization/**)과 공급자 콜백(/login/oauth2/code/**),
                        // 소셜 게이트 페이지(부분 인증 상태 — SecurityContext 없음).
                        "/oauth2/authorization/**",
                        "/login/oauth2/code/**",
                        "/login/link-confirm",
                        "/login/link-confirm/resend",
                        "/login/social-email",
                        "/login/social-email/verify",
                        "/login/social-email/resend",
                        // 기업 SSO(Stage E) — SAML: 진입(SP-initiated), ACS(IdP form POST → 부분 인증 없음),
                        // SP 메타데이터(상대 IdP 등록용). 조직 OIDC 는 위 /oauth2/** 경로를 공유한다.
                        "/saml2/authenticate/**",
                        "/login/saml2/sso/**",
                        "/saml2/service-provider-metadata/**",
                        // 매직 링크: 요청은 LOGIN_HINT 세션 기반(미인증), 랜딩/확정은 메일 링크로 진입(미인증).
                        "/login/magic",
                        "/login/magic/request",
                        "/signup",
                        "/password-reset/**",
                        "/css/**",
                        "/js/**",
                        "/error",
                    ).permitAll()
                    .requestMatchers("/account", "/api/mfa/**")
                    .authenticated()
                    // 관리 콘솔: 인증만으로는 부족 — ADMIN 역할 필수(일반 사용자는 403).
                    // 관리 콘솔. **판정 권위는 IAM 엔진**(PlatformAuthorizationInterceptor)으로 옮겼고,
                    // 이 체인 가드는 (1) 엔진 배선이 빠진 경로의 백스톱과 (2) 미인증 진입점 결정
                    // (화면 302 / API 401)을 맡는 심층 방어로 남는다. 경로 목록은 인터셉터 등록·기동
                    // 검증기와 같은 상수를 봐야 한다 — 어긋나면 그 차집합이 곧 인가 공백이다.
                    .requestMatchers(*AdminSurface.PATH_PATTERNS)
                    .hasRole("ADMIN")
                    // prometheus 메트릭: 익명 비허용에 더해 일반 인증 사용자도 차단 → 운영자(ADMIN) 전용.
                    // (URI 템플릿·요청 카운트·JVM 게이지 노출 최소화. 별도 관리 포트/네트워크 정책 격리도 권장.)
                    .requestMatchers("/actuator/prometheus")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated()
            }
            // 게이트(pending) 상태가 SecurityContext 로 승격되지 않도록 세션 저장소를 명시적으로 고정한다.
            .securityContext { it.securityContextRepository(securityContextRepository) }
            .exceptionHandling {
                // 미인증 응답을 **요청의 종류에 따라** 나눈다: API 는 401 JSON, 화면은 /login 리다이렉트.
                //
                // 왜 필요한가: 이 체인에는 리소스 서버가 없어 기본 진입점이 formLogin 의 302 하나뿐이었다.
                // 그래서 세션이 만료된 SPA 의 `fetch("/api/...")` 가 302 를 **투명하게 따라가** 로그인
                // 페이지 HTML 을 200 으로 받았고, `web/lib/api.ts` 는 `response.ok` 분기에서 그 HTML 을
                // JSON.parse 하다 SyntaxError 를 던졌다 — 로그인으로 유도하는 401 처리와 "본문이 HTML 이면
                // 미인증" 가드가 **둘 다 도달하지 못했다**. 사용자는 재로그인 안내 대신 영문 파서 오류를 봤고,
                // 서버 렌더링 admin 템플릿의 api() 헬퍼는 같은 이유로 실패한 변경 작업을 성공으로 보고했다.
                //
                // ★매핑 순서가 곧 의미다. DelegatingAuthenticationEntryPoint 는 **첫 번째 매핑을 폴백**으로
                // 삼으므로, `/api/**` 하나만 등록하면 그것이 폴백이 되어 매칭되지 않는 요청(/account·/admin
                // 화면)까지 401 이 된다. AnyRequestMatcher 매핑을 두 번째로 함께 등록해 화면 경로의 302 를
                // 지킨다(Accept 헤더를 보내지 않는 MockMvc 통합테스트 24건이 이 302 에 의존한다).
                it
                    .defaultAuthenticationEntryPointFor(apiEntryPoint, AntPathRequestMatcher("/api/**"))
                    .defaultAuthenticationEntryPointFor(
                        LoginUrlAuthenticationEntryPoint("/login"),
                        AnyRequestMatcher.INSTANCE,
                    )
                    /*
                     * ★403 쪽 절반. 401 만 우리 스키마로 답하던 동안, 세션이 만료된 뒤의 **상태변경**
                     * 요청은 CSRF 실패 403 + Boot 기본 본문으로 끝나 SPA 화면에 영문
                     * "Forbidden (403)" 이 떴다(로그인 이동도 일어나지 않는다). 근거는
                     * [ApiAccessDeniedHandler] KDoc.
                     *
                     * 화면 경로는 건드리지 않는다 — Spring Security 기본 403 처리가 그대로다.
                     */
                    .accessDeniedHandler { request, response, exception ->
                        if (request.requestURI.startsWith("/api/")) {
                            apiAccessDeniedHandler.handle(request, response, exception)
                        } else {
                            AccessDeniedHandlerImpl().handle(request, response, exception)
                        }
                    }
            }
            // IdP 브라우저 SSO 세션이 필요하므로 STATELESS 로 설정하지 않는다 (기본 세션 정책 유지).
            .formLogin {
                it
                    .loginPage("/login")
                    .loginProcessingUrl("/login/password")
                    .successHandler(successHandler)
                    .failureHandler(failureHandler)
                    .permitAll()
            }
            // 패스키(WebAuthn): 필터 기반 엔드포인트 4종을 등록한다. 커스텀 저장소(Jpa*Repository @Component)와
            // webAuthnRelyingPartyOperations 빈은 configurer 가 getSharedOrBean 으로 자동 사용한다.
            // 인증 필터의 세션 전략·계정 상태 검사·성공 핸들러는 빌드 후
            // customizeWebAuthnAuthenticationFilter() 에서 보정한다(사유는 해당 KDoc 참고).
            .webAuthn {
                it
                    .rpName(webAuthnProperties.rpName)
                    .rpId(webAuthnProperties.rpId)
                    .allowedOrigins(*webAuthnProperties.allowedOrigins.toTypedArray())
                    // 커스텀 로그인/계정 페이지를 쓰므로 기본 등록 페이지(와 기본 JS 서빙)를 끈다.
                    // 기본 JS 는 static/js/webauthn.js 로 벤더링했다.
                    .disableDefaultRegistrationPage(true)
            }
            // 패스키 등록(/webauthn/register*)은 필터 기반이라 @RequireRecentAuth 인터셉터가 개입할 수
            // 없다 → step-up 강제 필터를 등록 필터들 앞에 배치한다(B-3).
            // WebAuthnRegistrationFilter 는 order 앵커로 등록돼 있지 않아 직접 지정할 수 없다(6.4.4 실측:
            // 옵션 필터는 AuthorizationFilter 직전, 등록 필터는 직후). ExceptionTranslationFilter 직후는
            // 두 필터 모두보다 앞이면서 SecurityContext/CSRF 처리 이후임이 보장된다.
            .addFilterAfter(
                StepUpEnforcementFilter(stepUpProperties, objectMapper),
                ExceptionTranslationFilter::class.java,
            )
            // GET /logout(확인 페이지)은 permitAll 목록에 없으므로 anyRequest().authenticated() 로 보호된다.
            // POST /logout 은 LogoutFilter 가 처리(CSRF 필요, /api/** 가 아니므로 ignoringRequestMatchers 미적용).
            .logout {
                it
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login")
                    .invalidateHttpSession(true)
                    // Spring Session 의 세션 쿠키명은 SESSION 이다(JSESSIONID 아님).
                    .deleteCookies("SESSION")
            }
        // 소셜 로그인(oauth2Login)은 ClientRegistrationRepository 빈이 있을 때만 활성화한다.
        // (공급자 환경변수 미설정이면 빈이 생성되지 않고, 버튼도 렌더링되지 않는다 — 기동은 정상.)
        // 성공 핸들러가 항상 OAuth2AuthenticationToken 을 로컬 principal 로 승격/제거하므로
        // 세션에는 폼 로그인과 동일한 UsernamePasswordAuthenticationToken 만 남는다.
        clientRegistrationRepositoryProvider.ifAvailable { _ ->
            http.oauth2Login {
                it.loginPage("/login")
                // 토큰 교환(authorization_code → access_token)에 5초 상한을 건다. 기본 클라이언트는
                // connect/read 무한이라 공급자 지연 시 콜백 워커가 고갈된다(FederatedTokenClient 참조).
                // userinfo 방어(아래)와 짝을 이뤄 콜백 경로 전체가 상한 안에 있게 한다.
                it.tokenEndpoint { endpoint ->
                    endpoint.accessTokenResponseClient(authorizationCodeTokenResponseClient)
                }
                it.userInfoEndpoint { endpoint ->
                    endpoint.userService(federatedOAuth2UserService)
                    endpoint.oidcUserService(federatedOidcUserService)
                }
                it.successHandler(federatedLoginSuccessHandler)
                it.failureHandler { request, response, _ ->
                    response.sendRedirect(request.contextPath + "/login?error=social")
                }
            }
        }
        // 기업 SSO(Stage E) SAML. DbRelyingPartyRegistrationRepository 는 항상 노출되고 SAML 커넥션이
        // 0건이면 어떤 registrationId 조회도 null 을 반환하므로, saml2Login 필터를 상시 등록해도 SAML
        // 엔드포인트는 무등록 상태에선 사실상 비활성(조회 실패 → 일반 실패)이다. 상시 등록으로 관리자가
        // 첫 SAML 커넥션을 추가하면 재기동 없이 즉시 동작한다(필터는 /saml2/**·/login/saml2/** 에만
        // 매칭되어 소셜 oauth2Login·게이트·AS 체인과 독립적). saml2 엔드포인트 미사용 시 부작용 없음.
        http.saml2Login {
            it.loginPage("/login")
            it.relyingPartyRegistrationRepository(relyingPartyRegistrationRepository)
            // OpenSAML 4(6.4.4 기본 클래스패스) provider — 기본 ResponseAuthenticationConverter 로
            // Saml2Authentication(DefaultSaml2AuthenticatedPrincipal + 어트리뷰트)을 만든다.
            // 보안 한계(후순위, docs/enterprise-sso-setup.md 명시): 기본 provider 는 IdP-initiated
            // (unsolicited) 응답을 InResponseTo 상관 없이 수용하고 SP AuthnRequest 서명을 하지 않아,
            // 유효 서명 어서션을 form POST 하는 로그인 CSRF(세션 스와핑)가 가능하다. 어서션 서명 검증은
            // 정상이라 타인 계정 탈취는 불가하며 영향은 세션 스와핑에 한정된다.
            it.authenticationManager(ProviderManager(OpenSaml4AuthenticationProvider()))
            // 성공 시 Saml2Authentication 을 로컬 UserDetails 완전 인증으로 승격(미러링).
            it.successHandler(saml2SuccessHandler)
            it.failureHandler { request, response, _ ->
                response.sendRedirect(request.contextPath + "/login?error=sso")
            }
        }
        // SP 메타데이터 엔드포인트(/saml2/service-provider-metadata/{regId}) — 상대 IdP 등록용.
        http.saml2Metadata(Customizer.withDefaults())
        applySecurityHeaders(http)
        val chain = http.build()
        customizeWebAuthnAuthenticationFilter(
            chain,
            securityContextRepository,
            relyingPartyOperations,
            loginUserDetailsService,
            userRepository,
            accountLockoutService,
            auditEventService,
            passkeySuccessHandler,
        )
        replaceCreationOptionsRepository(chain, relyingPartyOperations, jdbcCreationOptionsRepository)
        return chain
    }

    /**
     * 패스키 등록 옵션 저장소 교체(JDBC 세션 대응). PublicKeyCredentialCreationOptions 는
     * Serializable 이 아니라(직렬화 전수 실측의 유일한 블로커) 세션에 직접 저장할 수 없다.
     * 6.4 DSL 에는 저장소 주입 지점이 없어(7.x 와 혼동 금지):
     *  1) 옵션 필터는 setter 가 없으므로 동작 동일한 PasskeyCreationOptionsFilter 로 in-place 교체
     *     (chain.filters 는 가변 ArrayList — 실측)하고,
     *  2) 등록 필터는 setCreationOptionsRepository 로 같은 저장소를 주입한다.
     */
    private fun replaceCreationOptionsRepository(
        chain: SecurityFilterChain,
        relyingPartyOperations: WebAuthnRelyingPartyOperations,
        creationOptionsRepository: JdbcCreationOptionsRepository,
    ) {
        @Suppress("UNCHECKED_CAST")
        val filters = chain.filters as MutableList<Filter>
        val index = filters.indexOfFirst { it is PublicKeyCredentialCreationOptionsFilter }
        check(index >= 0) { "PublicKeyCredentialCreationOptionsFilter not found in the filter chain" }
        filters[index] = PasskeyCreationOptionsFilter(relyingPartyOperations, creationOptionsRepository)
        filters
            .filterIsInstance<WebAuthnRegistrationFilter>()
            .single()
            .setCreationOptionsRepository(creationOptionsRepository)
    }

    /**
     * SS 6.4.4 의 WebAuthnConfigurer 는 WebAuthnAuthenticationFilter 를 postProcess 없이 직접
     * 생성·등록하므로(바이트코드 실측) ObjectPostProcessor 로는 개입할 수 없다. 빌드된 체인에서
     * 필터를 찾아 세 가지를 보정한다.
     *
     *  1) 세션 고정 방어: 필터 기본 sessionStrategy 는 Null 전략이라(6.4.4 실측 — 폼 로그인과 달리
     *     세션 ID 가 교체되지 않음) ChangeSessionIdAuthenticationStrategy 를 명시 주입한다.
     *     세션 전략은 successfulAuthentication(컨텍스트 저장) 이전에 실행된다.
     *  2) 계정 상태 검사: WebAuthnAuthenticationProvider 는 UserDetails 의 enabled/locked 를 검사하지
     *     않아 SUSPENDED·잠금 계정이 패스키로 로그인할 수 있다. 상태 검사 래퍼로 감싼 매니저로 교체한다.
     *     (configurer 와 동일하게 WebAuthnRelyingPartyOperations 빈 + UserDetailsService 로 구성 —
     *     configurer 도 이 빈을 getBeanOrNull 로 우선 사용하므로 어서션 검증 동작은 동일하다.)
     *  3) 성공 핸들러: 기본 핸들러는 saved request 가 없으면 "/" 로 보내고(앱에 "/" 뷰 없음 → 404),
     *     감사 로그·잠금 카운터 리셋도 없다. PasskeyAuthenticationSuccessHandler 로 교체한다.
     *
     * 실패 핸들러는 기본(401)을 유지한다 — 벤더링 JS 가 non-ok 응답 시 /login?error 로 이동시킨다.
     */
    private fun customizeWebAuthnAuthenticationFilter(
        chain: SecurityFilterChain,
        securityContextRepository: HttpSessionSecurityContextRepository,
        relyingPartyOperations: WebAuthnRelyingPartyOperations,
        loginUserDetailsService: LoginUserDetailsService,
        userRepository: UserRepository,
        accountLockoutService: AccountLockoutService,
        auditEventService: AuditEventService,
        passkeySuccessHandler: PasskeyAuthenticationSuccessHandler,
    ) {
        val filter = chain.filters.filterIsInstance<WebAuthnAuthenticationFilter>().single()
        filter.setSessionAuthenticationStrategy(ChangeSessionIdAuthenticationStrategy())
        filter.setSecurityContextRepository(securityContextRepository)
        filter.setAuthenticationManager(
            ProviderManager(
                AccountStatusCheckingAuthenticationProvider(
                    WebAuthnAuthenticationProvider(relyingPartyOperations, loginUserDetailsService),
                    userRepository,
                    accountLockoutService,
                    auditEventService,
                ),
            ),
        )
        filter.setAuthenticationSuccessHandler(passkeySuccessHandler)
    }

    /**
     * WebAuthn 어서션/등록 검증 오퍼레이션. 빈으로 노출하면 WebAuthnConfigurer 가 자체 생성 대신
     * 이 빈을 사용하므로(getBeanOrNull 실측), 위 customize 단계의 커스텀 AuthenticationProvider 와
     * 등록 필터들이 완전히 동일한 인스턴스를 공유한다. rp 설정값은 기존 DSL 값과 동일하다.
     */
    @Bean
    fun webAuthnRelyingPartyOperations(
        userEntityRepository: PublicKeyCredentialUserEntityRepository,
        userCredentialRepository: UserCredentialRepository,
        webAuthnProperties: WebAuthnProperties,
    ): WebAuthnRelyingPartyOperations =
        Webauthn4JRelyingPartyOperations(
            userEntityRepository,
            userCredentialRepository,
            PublicKeyCredentialRpEntity
                .builder()
                .id(webAuthnProperties.rpId)
                .name(webAuthnProperties.rpName)
                .build(),
            webAuthnProperties.allowedOrigins.toSet(),
        )

    @Bean
    fun securityContextRepository(): HttpSessionSecurityContextRepository = HttpSessionSecurityContextRepository()

    /**
     * 로그인 후 되돌아갈 요청 저장소.
     *
     * ★**화면(HTML) 요청만 저장한다.** 기본 HttpSessionRequestCache 는 미인증 요청을 종류 불문 저장하는데,
     * 그러면 페이지 JS·SPA 가 세션 만료 상태에서 보낸 `/api/` 이하 호출이 "로그인 후 돌아갈 곳"으로 기록되어
     * 로그인 직후 사용자가 **JSON 응답 화면에 착지한다**. 게다가 저장 URL 은 서버가 본 절대 주소라,
     * 프런트가 프록시 뒤에 있으면 포트/오리진까지 서버 쪽으로 튕겨 나간다.
     *
     * 판정 기준: GET + Accept 에 text/html + `/api/` 로 시작하지 않음 + **인증 플로우 자신의 경로가 아님**.
     * 넷 다 만족해야 "사람이 보던 화면"이다. 저장되지 않으면 성공 핸들러가 기본 목적지로 보내므로
     * 흐름은 끊기지 않는다.
     *
     * ★마지막 조건([AUTH_FLOW_PATHS])이 없던 동안 두 가지가 실제로 일어났다:
     * ① **로그아웃 후 재로그인이 로그아웃 화면에 착지한다.** 미인증 상태의 `GET /logout` 이 저장되어
     *    "로그인 후 돌아갈 곳"이 되고, 사용자는 방금 로그인했는데 "로그아웃하시겠습니까?"를 본다.
     * ② **재인증이 자기 자신으로 되돌아온다.** 미인증 `GET /reauth` 가 같은 방식으로 저장된다.
     * 이 화면들은 정의상 목적지가 아니라 **통과 지점**이라, 돌아갈 곳 후보에서 빠지는 것이 옳다.
     */
    @Bean
    fun requestCache(): RequestCache =
        HttpSessionRequestCache().apply {
            setRequestMatcher { request ->
                HttpMethod.GET.matches(request.method) &&
                    request.getHeader(HttpHeaders.ACCEPT)?.contains(MediaType.TEXT_HTML_VALUE) == true &&
                    !request.requestURI.startsWith("/api/") &&
                    request.requestURI !in AUTH_FLOW_PATHS
            }
        }
}
