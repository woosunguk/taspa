package com.taspa.server.federation

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

/**
 * 소셜 공급자 조건부 등록 소스.
 *
 * yaml(spring.security.oauth2.client.registration) 등록은 client-id 가 비면 기동이 실패하므로,
 * 환경변수 쌍(GOOGLE_CLIENT_ID/SECRET, KAKAO_CLIENT_ID/SECRET, NAVER_CLIENT_ID/SECRET)이 있는
 * 공급자만 프로그래매틱으로 등록한다.
 *
 * ClientRegistrationRepository 빈 자체는 enterprise/EnterpriseClientRegistrationConfig 가
 * CompositeClientRegistrationRepository(소셜 + DB 조직 OIDC)로 노출한다. 소셜 0건이면 빈을 만들지 않아
 * (조직 OIDC 는 소셜이 설정된 배포에서 재기동 없이 동작 — 제약은 docs/enterprise-sso-setup.md) 기존
 * 조건부 동작을 유지한다.
 *
 * 카카오·네이버 엔드포인트 URI 는 테스트(WireMock)에서 taspa.social.{provider}.* 프로퍼티로 교체할 수 있다.
 */
object SocialClientRegistrations {
    class AnySocialClientConfigured : Condition {
        override fun matches(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ): Boolean = SocialClientRegistrations.buildRegistrations(context.environment).isNotEmpty()
    }

    fun buildRegistrations(environment: Environment): List<ClientRegistration> =
        listOfNotNull(
            credentials(environment, "GOOGLE")?.let { (id, secret) -> google(id, secret) },
            credentials(environment, "KAKAO")?.let { (id, secret) -> kakao(environment, id, secret) },
            credentials(environment, "NAVER")?.let { (id, secret) -> naver(environment, id, secret) },
        )

    private fun credentials(
        environment: Environment,
        prefix: String,
    ): Pair<String, String>? {
        val clientId = environment.getProperty("${prefix}_CLIENT_ID")?.takeIf { it.isNotBlank() } ?: return null
        val clientSecret =
            environment.getProperty("${prefix}_CLIENT_SECRET")?.takeIf { it.isNotBlank() } ?: return null
        return clientId to clientSecret
    }

    /** 구글: OIDC 내장 프로바이더(CommonOAuth2Provider.GOOGLE) — provider 설정 불필요. */
    private fun google(
        clientId: String,
        clientSecret: String,
    ): ClientRegistration =
        CommonOAuth2Provider.GOOGLE
            .getBuilder(SocialProviders.GOOGLE)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build()

    /**
     * 카카오: 순수 OAuth2 방식(OIDC 미사용 — issuer 디스커버리 HTTP 호출 회피 +
     * email 검증 판단은 v2/user/me 응답이 명확). client_secret_post 필수.
     */
    private fun kakao(
        environment: Environment,
        clientId: String,
        clientSecret: String,
    ): ClientRegistration =
        ClientRegistration
            .withRegistrationId(SocialProviders.KAKAO)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("account_email", "profile_nickname", "profile_image")
            .authorizationUri(
                uri(environment, SocialProviders.KAKAO, "authorization-uri", "https://kauth.kakao.com/oauth/authorize"),
            ).tokenUri(uri(environment, SocialProviders.KAKAO, "token-uri", "https://kauth.kakao.com/oauth/token"))
            .userInfoUri(uri(environment, SocialProviders.KAKAO, "user-info-uri", "https://kapi.kakao.com/v2/user/me"))
            .userNameAttributeName("id")
            .clientName("Kakao")
            .build()

    /**
     * 네이버: OIDC 미지원. userinfo 프로필이 response 아래 중첩이라 user-name-attribute 는 일단
     * "response" 로 두고 FederatedOAuth2UserService 가 평탄화 후 id 로 재구성한다.
     */
    private fun naver(
        environment: Environment,
        clientId: String,
        clientSecret: String,
    ): ClientRegistration =
        ClientRegistration
            .withRegistrationId(SocialProviders.NAVER)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri(
                uri(environment, SocialProviders.NAVER, "authorization-uri", "https://nid.naver.com/oauth2.0/authorize"),
            ).tokenUri(uri(environment, SocialProviders.NAVER, "token-uri", "https://nid.naver.com/oauth2.0/token"))
            .userInfoUri(uri(environment, SocialProviders.NAVER, "user-info-uri", "https://openapi.naver.com/v1/nid/me"))
            .userNameAttributeName("response")
            .clientName("Naver")
            .build()

    private fun uri(
        environment: Environment,
        provider: String,
        key: String,
        default: String,
    ): String = environment.getProperty("taspa.social.$provider.$key", default)
}
