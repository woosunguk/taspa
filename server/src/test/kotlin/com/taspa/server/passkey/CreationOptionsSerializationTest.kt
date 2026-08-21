package com.taspa.server.passkey

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity
import org.springframework.security.web.webauthn.jackson.WebauthnJackson2Module
import java.time.Duration

/**
 * `PublicKeyCredentialCreationOptions` 의 Jackson 지원 범위 — **직렬화만 되고 역직렬화는 안 된다**.
 *
 * 이 테스트는 "된다"가 아니라 **"안 된다"를 못박는다.** 그게 여기 있는 이유다.
 *
 * 배경: 이 클래스는 `Serializable` 이 아니라 세션(JDK 직렬화)에 담을 수 없고, 그래서 등록 옵션이
 * 인메모리 캐시에 살았다 — 다중 인스턴스에서 "A 가 발급했는데 자격증명 POST 가 B 로 가면 등록 실패"가
 * 된다. DB 로 옮기려고 가장 먼저 시도한 것이 `WebauthnJackson2Module` 로 JSON 왕복이었는데,
 * 그 모듈의 믹스인에는 **역직렬화 creator 가 없다**(직렬화 전용 — 서버가 브라우저로 내보내는 용도).
 *
 * 그래서 `JdbcCreationOptionsRepository` 는 필드를 컬럼으로 풀어 저장하고 로드 시 재구성한다.
 * 이 테스트가 없으면 다음 사람이 같은 막다른 길을 다시 걷는다("Jackson 모듈이 있는데 왜 안 썼지?").
 * 라이브러리가 역직렬화를 지원하게 되면 이 테스트가 실패하고, 그게 저장소를 단순화할 신호다.
 */
class CreationOptionsSerializationTest {
    private val mapper = ObjectMapper().registerModule(WebauthnJackson2Module())

    private fun sample(): PublicKeyCredentialCreationOptions =
        PublicKeyCredentialCreationOptions
            .builder()
            .rp(
                PublicKeyCredentialRpEntity
                    .builder()
                    .id("localhost")
                    .name("taspa")
                    .build(),
            ).user(
                ImmutablePublicKeyCredentialUserEntity
                    .builder()
                    .id(Bytes(ByteArray(32) { it.toByte() }))
                    .name("user@example.com")
                    .displayName("사용자")
                    .build(),
            ).challenge(Bytes(ByteArray(32) { (255 - it).toByte() }))
            .pubKeyCredParams(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256)
            .timeout(Duration.ofMinutes(5))
            .build()

    @Test
    fun `직렬화는 된다 — 브라우저로 내보내는 용도가 이 모듈의 목적이다`() {
        val json = mapper.writeValueAsString(sample())

        // 실제로 브라우저가 받는 필드들. 이 방향은 등록 옵션 응답에서 매일 쓰인다.
        assertThat(json).contains("\"challenge\"")
        assertThat(json).contains("\"user\"")
        assertThat(json).contains("\"rp\"")
        assertThat(json).contains("\"pubKeyCredParams\"")
    }

    @Test
    fun `★역직렬화는 지원되지 않는다 — 그래서 필드를 풀어 저장한다`() {
        val json = mapper.writeValueAsString(sample())

        val failure =
            runCatching {
                mapper.readValue(json, PublicKeyCredentialCreationOptions::class.java)
            }.exceptionOrNull()

        // creator 가 없어 Jackson 이 인스턴스를 만들지 못한다(6.4.4 실측).
        assertThat(failure).isInstanceOf(InvalidDefinitionException::class.java)
        assertThat(failure).hasMessageContaining("no Creators")
    }
}
