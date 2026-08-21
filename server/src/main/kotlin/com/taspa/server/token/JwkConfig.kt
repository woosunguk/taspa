package com.taspa.server.token

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JwkConfig {
    /**
     * DB 기반 동적 JWKSource. NimbusJwtEncoder 와 `/oauth2/jwks` 필터는 매 호출마다 get() 을
     * 부르므로 키 회전이 재기동 없이 반영된다(신선도는 JwkStorageService 의 60초 캐시가 결정).
     * JWKS 응답은 publicOnly 직렬화라 개인키 포함 RSAKey 를 넘겨도 공개 부분만 노출된다.
     */
    @Bean
    fun jwkSource(jwkStorageService: JwkStorageService): JWKSource<SecurityContext> =
        JWKSource { jwkSelector, _ -> jwkSelector.select(JWKSet(jwkStorageService.currentKeys())) }
}
