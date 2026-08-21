package com.taspa.server.enterprise

import com.taspa.server.federation.SocialClientRegistrations
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * ClientRegistrationRepository 빈을 소셜 + DB 조직 OIDC 통합(CompositeClientRegistrationRepository)으로 노출한다.
 *
 * 조건은 기존과 동일하게 "소셜 공급자가 하나라도 설정됨"이다(AnySocialClientConfigured). 즉:
 *  - 소셜만 있고 조직 OIDC 0건이어도 동작한다.
 *  - 소셜·조직 모두 0이면 빈을 만들지 않아(oauth2Login 미적용) 기존 조건부 동작을 그대로 유지한다.
 *
 * 조직 OIDC 커넥션은 소셜이 설정된 배포에서 findByRegistrationId 의 DB 폴백으로 재기동 없이 즉시 동작한다.
 * (소셜이 전무한 배포에서 첫 OIDC 커넥션을 활성화하려면 재기동이 필요 — docs/enterprise-sso-setup.md.)
 */
@Configuration
class EnterpriseClientRegistrationConfig {
    @Bean
    @Conditional(SocialClientRegistrations.AnySocialClientConfigured::class)
    fun clientRegistrationRepository(
        environment: Environment,
        ssoConnectionService: SsoConnectionService,
    ): ClientRegistrationRepository =
        CompositeClientRegistrationRepository(
            socialRegistrations = SocialClientRegistrations.buildRegistrations(environment),
            ssoConnectionService = ssoConnectionService,
        )
}
