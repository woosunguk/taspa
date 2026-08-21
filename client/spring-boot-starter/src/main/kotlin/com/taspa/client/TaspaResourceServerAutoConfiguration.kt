package com.taspa.client

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter

@AutoConfiguration
@EnableConfigurationProperties(TaspaClientProperties::class)
@ConditionalOnProperty(prefix = "taspa.client", name = ["issuer-uri"])
class TaspaResourceServerAutoConfiguration {

    /**
     * 발급자 디스커버리로 만든 디코더 + **`aud` 검증**(설정했을 때만).
     *
     * `JwtDecoders.fromIssuerLocation` 이 붙여 주는 기본 검증기는 iss·exp·nbf 까지다. 여기에 audience
     * 검증기를 **얹는다**(교체가 아니다 — 교체하면 만료 검사가 사라진다).
     *
     * ★검증기는 [audienceValidator] 로 떼어 두었다: 이 자리가 조용히 아무 일도 하지 않던 것이 결함의
     * 형태였으므로, 단위 테스트가 디코더 없이 그 판정만 직접 단언할 수 있어야 한다.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder::class)
    fun taspaJwtDecoder(properties: TaspaClientProperties): JwtDecoder {
        val issuer = properties.issuerUri!!
        val decoder = JwtDecoders.fromIssuerLocation(issuer) as NimbusJwtDecoder
        val audience = properties.audience?.trim()?.takeIf { it.isNotEmpty() }
        if (audience != null) {
            decoder.setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefaultWithIssuer(issuer),
                    audienceValidator(audience),
                ),
            )
        }
        return decoder
    }

    /**
     * 토큰의 조직 커스텀 역할을 권한으로 옮기는 변환기.
     *
     * `@ConditionalOnMissingBean` 이라 서비스가 자기 변환기를 등록하면 이 빈은 만들어지지 않는다 —
     * 스타터가 남의 인가 규칙을 덮어쓰지 않게. `taspa.client.roles-enabled=false` 로도 끌 수 있다.
     */
    @Bean
    @ConditionalOnMissingBean(JwtAuthenticationConverter::class)
    @ConditionalOnProperty(prefix = "taspa.client", name = ["roles-enabled"], matchIfMissing = true)
    fun taspaJwtAuthenticationConverter(properties: TaspaClientProperties): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(
                TaspaRolesJwtConverter(properties.roleAuthorityPrefix, properties.orgId),
            )
        }

    companion object {
        /**
         * `aud` 에 지정한 값이 들어 있는지.
         *
         * `aud` 는 배열이 표준이고 단일 문자열도 허용된다 — Spring 이 `List<String>` 으로 정규화해 준다.
         * **클레임이 아예 없으면 거부**한다(fail-closed): audience 를 설정했다는 것은 "내 것이 아닌
         * 토큰은 받지 않겠다"는 선언이고, 대상이 명시되지 않은 토큰은 내 것이라는 근거가 없다.
         */
        fun audienceValidator(audience: String): OAuth2TokenValidator<Jwt> =
            OAuth2TokenValidator { jwt ->
                if (audience in jwt.audience.orEmpty()) {
                    OAuth2TokenValidatorResult.success()
                } else {
                    OAuth2TokenValidatorResult.failure(
                        OAuth2Error(
                            "invalid_token",
                            "이 토큰의 aud 에 '" + audience + "' 가 없습니다(taspa.client.audience)",
                            null,
                        ),
                    )
                }
            }
    }
}
