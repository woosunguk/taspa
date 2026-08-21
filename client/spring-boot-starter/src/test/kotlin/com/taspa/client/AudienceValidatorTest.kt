package com.taspa.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * `taspa.client.audience` 가 **실제로 검증하는지** 고정한다.
 *
 * ★이 테스트가 존재하는 이유는 그 반대 상태가 한동안 배포돼 있었기 때문이다: 프로퍼티는 선언돼 있고
 * 연동 문서 예시에도 있었지만 코드 어디에서도 읽히지 않아, 같은 IdP 가 발급한 **다른 서비스의 토큰**이
 * 그대로 통과했다. 연동 개발자는 검증된다고 믿으므로 직접 붙였을 검사도 붙이지 않는다 —
 * 설정이 있는데 안 도는 것이 없는 것보다 위험한 이유다.
 */
class AudienceValidatorTest {
    private val validator = TaspaResourceServerAutoConfiguration.audienceValidator("orders-api")

    private fun jwt(vararg audience: String): Jwt =
        Jwt
            .withTokenValue("t")
            .header("alg", "RS256")
            .claim("sub", "u")
            .audience(audience.toList())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()

    @Test
    fun `내 aud 가 들어 있으면 통과한다`() {
        assertThat(validator.validate(jwt("orders-api")).hasErrors()).isFalse()
    }

    @Test
    fun `여러 aud 중 하나라도 내 것이면 통과한다`() {
        assertThat(validator.validate(jwt("billing-api", "orders-api")).hasErrors()).isFalse()
    }

    @Test
    fun `다른 서비스용 토큰은 거부한다`() {
        val result = validator.validate(jwt("billing-api"))
        assertThat(result.hasErrors()).isTrue()
        assertThat(result.errors.first().description).contains("orders-api")
    }

    @Test
    fun `aud 가 없는 토큰은 거부한다(fail-closed)`() {
        val bare =
            Jwt
                .withTokenValue("t")
                .header("alg", "RS256")
                .claim("sub", "u")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()
        assertThat(validator.validate(bare).hasErrors()).isTrue()
    }
}
