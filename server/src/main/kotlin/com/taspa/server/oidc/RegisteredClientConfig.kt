package com.taspa.server.oidc

import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import java.time.Duration
import java.util.UUID

@Configuration
class RegisteredClientConfig {
    private companion object {
        // demo-client 홈(= RP-Initiated Logout 복귀 지점). 시더 upsert 조건과 등록값의 단일 소스.
        const val DEMO_POST_LOGOUT_REDIRECT_URI = "http://localhost:8080/"

        /** 8080 이 다른 프로세스에 점유된 로컬용 대체 포트(demo-client 8081) redirect — healthy 판정에도 포함. */
        const val DEMO_REDIRECT_URI_ALT_PORT = "http://localhost:8081/login/oauth2/code/taspa"

        /** 대체 포트의 RP-Initiated Logout 복귀 지점 — 8081 demo-client 의 SLO 왕복에 필요(정확 일치 검증). */
        const val DEMO_POST_LOGOUT_REDIRECT_URI_ALT_PORT = "http://localhost:8081/"
    }

    @Bean
    fun registeredClientRepository(jdbcTemplate: JdbcTemplate): RegisteredClientRepository = JdbcRegisteredClientRepository(jdbcTemplate)

    @Bean
    @ConditionalOnProperty(prefix = "taspa.registered-clients", name = ["seed-demo-client"], havingValue = "true")
    fun demoClientSeeder(
        registeredClientRepository: RegisteredClientRepository,
        passwordEncoder: PasswordEncoder,
    ): ApplicationRunner =
        ApplicationRunner {
            val existing = registeredClientRepository.findByClientId("demo-app")
            // clientName 미설정 시 SAS 는 id(UUID)를 기본값으로 채운다 — 동의 화면에 UUID 가 노출되는 기존 결함.
            // secret 은 공유 BCryptPasswordEncoder 로 접두사 없이 저장해야 SAS 토큰 엔드포인트 인증이 통과한다.
            // 인코더 접두사({bcrypt} 등)가 붙은 구형/오염 행은 매칭이 영영 실패하므로(invalid_client)
            // 비정상으로 보고 upsert 로 자가 복구한다. 이름·secret·post-logout URI 모두 정상이면 건드리지 않는다.
            // post-logout URI 는 RP-Initiated Logout(demo-client 의 SLO 버튼 → /connect/logout)에서
            // taspa 가 post_logout_redirect_uri 를 등록값과 정확 일치로 검증하는 데 필요하다 — 과거에 이
            // URI 없이 시딩된 행도 아래 조건이 잡아내 재시딩으로 보강한다.
            // Stage 5: demo-app 에 Device Authorization Grant 를 데모용으로 허용한다. 과거에 device_code
            // 없이 시딩된 기존 행도 아래 조건이 잡아내 재시딩으로 보강한다(healthy 판정에 grant 포함).
            // 8081 redirect 도 healthy 조건에 포함한다 — 로컬에서 8080 이 다른 프로세스에 점유된 환경은
            // demo-client 를 8081 로 띄우는데(e2e sso-flow 의 DEMO_BASE 오버라이드), 과거 self-heal 재시딩이
            // 8080 전용으로 덮어써 8081 redirect 가 소실되면 /oauth2/authorize 가 400 으로 깨진다.
            val healthy =
                existing != null &&
                    existing.clientName != existing.id &&
                    existing.clientSecret?.startsWith("{") == false &&
                    existing.postLogoutRedirectUris.contains(DEMO_POST_LOGOUT_REDIRECT_URI) &&
                    existing.postLogoutRedirectUris.contains(DEMO_POST_LOGOUT_REDIRECT_URI_ALT_PORT) &&
                    existing.redirectUris.contains(DEMO_REDIRECT_URI_ALT_PORT) &&
                    existing.authorizationGrantTypes.contains(AuthorizationGrantType.DEVICE_CODE)
            if (healthy) {
                return@ApplicationRunner
            }
            val demoClient =
                RegisteredClient
                    .withId(existing?.id ?: UUID.randomUUID().toString())
                    .clientId("demo-app")
                    .clientName("Demo App")
                    // 접두사 없이 BCrypt 해시만 저장한다 — SAS 클라이언트 인증은 컨텍스트의 PasswordEncoder
                    // 빈(BCryptPasswordEncoder)을 공유하므로 {bcrypt} 접두사를 붙이면 matches 가 실패한다.
                    .clientSecret(passwordEncoder.encode("demo-secret"))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    // Device Authorization Grant(Stage 5) 데모: demo-app 으로 /oauth2/device_authorization →
                    // /activate → 동의 → device_code 토큰 교환 왕복을 시연할 수 있다.
                    .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
                    .redirectUri("http://localhost:8080/login/oauth2/code/taspa")
                    // 8080 점유 환경용 대체 포트 — demo-client 를 8081 로 띄우는 로컬(e2e DEMO_BASE 오버라이드) 지원.
                    .redirectUri(DEMO_REDIRECT_URI_ALT_PORT)
                    // RP-Initiated Logout 복귀 지점 — demo-client 의 OidcClientInitiatedLogoutSuccessHandler 가
                    // post_logout_redirect_uri={baseUrl}/(= http://localhost:8080/)로 보내며, taspa 는 이 값을
                    // 등록 목록과 정확 일치로 검증한다(미등록 시 로그아웃 거부).
                    .postLogoutRedirectUri(DEMO_POST_LOGOUT_REDIRECT_URI)
                    .postLogoutRedirectUri(DEMO_POST_LOGOUT_REDIRECT_URI_ALT_PORT)
                    .scope(OidcScopes.OPENID)
                    .scope(OidcScopes.PROFILE)
                    .scope(OidcScopes.EMAIL)
                    .clientSettings(
                        ClientSettings
                            .builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(true)
                            .build(),
                    ).tokenSettings(
                        TokenSettings
                            .builder()
                            .accessTokenTimeToLive(Duration.ofMinutes(15))
                            .refreshTokenTimeToLive(Duration.ofDays(30))
                            .reuseRefreshTokens(false)
                            .build(),
                    ).build()
            registeredClientRepository.save(demoClient)
        }
}
