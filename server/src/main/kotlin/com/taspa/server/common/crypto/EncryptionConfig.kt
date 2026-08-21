package com.taspa.server.common.crypto

import com.taspa.server.config.ProductionConfigChecks
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

/**
 * 용도별 AES 암호화 빈. 키를 분리해 한쪽 키가 유출돼도 다른 저장물이 함께 뚫리지 않게 한다.
 * jwk 키가 미설정이면 mfa 키로 폴백한다(dev 단일 키 운영 허용 — prod 에서는 아래 검증이 거부한다).
 *
 * prod 프로필에서는 약한 키로 기동하지 않는다 — jwk 키는 JWT 서명 개인키(jwk_keys)의 암호화 키라,
 * 추측 가능한 키로 암호화하면 DB 유출이 곧 발급자 서명키 유출(전 연동 서비스 토큰 위조)이 된다.
 * misconfig 는 조용히 진행되지 않고 fail-fast 해야 한다.
 *
 * 검증 로직 자체는 다른 prod 검사와 함께 ProductionConfigChecks 에 두되, 호출은 여기서 한다 —
 * ProductionSafetyValidator 빈보다 이 설정 클래스가 먼저 생성돼 키를 소비할 수 있어서,
 * 그쪽에서 검사하면 이미 약한 키로 암호화가 시작된 뒤가 된다.
 */
@Configuration
class EncryptionConfig(
    @Value("\${taspa.mfa.encryption-key}") private val mfaKey: String,
    @Value("\${taspa.jwk.encryption-key:}") jwkKeyProperty: String,
    environment: Environment,
) {
    private val jwkKey: String = jwkKeyProperty.ifBlank { mfaKey }

    init {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            ProductionConfigChecks.checkEncryptionKeys(mfaKey, jwkKey)
        }
    }

    @Bean
    fun mfaEncryptionService(): AesEncryptionService = AesEncryptionService(mfaKey)

    @Bean
    fun jwkEncryptionService(): AesEncryptionService = AesEncryptionService(jwkKey)
}
