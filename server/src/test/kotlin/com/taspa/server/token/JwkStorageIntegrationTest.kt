package com.taspa.server.token

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.taspa.server.common.crypto.AesEncryptionService
import com.taspa.server.domain.jwk.JwkKeyRepository
import com.taspa.server.domain.jwk.JwkKeyStatus
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtEncodingException
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit

class JwkStorageIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Autowired lateinit var jwkKeyRepository: JwkKeyRepository

    @Autowired lateinit var jwkProperties: JwkProperties

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwtTokenCustomizer: OAuth2TokenCustomizer<JwtEncodingContext>

    @Autowired
    @Qualifier("jwkEncryptionService")
    lateinit var jwkEncryptionService: AesEncryptionService

    /** 재기동 시뮬: 같은 DB 를 보는 새 저장소 인스턴스(인메모리 캐시 없음 상태). */
    private fun restartedStorage() = JwkStorageService(jwkKeyRepository, jwkEncryptionService, jwkProperties)

    private fun claims(subject: String): JwtClaimsSet {
        val now = Instant.now()
        return JwtClaimsSet
            .builder()
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plus(5, ChronoUnit.MINUTES))
            .build()
    }

    @Test
    fun `부트스트랩은 멱등이다 - 재기동해도 같은 활성 키를 로드하고 ACTIVE 는 1개다`() {
        val kid = jwkStorageService.activeKid()

        assertThat(restartedStorage().activeKid()).isEqualTo(kid)
        assertThat(jwkKeyRepository.findAll().count { it.status == JwkKeyStatus.ACTIVE }).isEqualTo(1)
    }

    @Test
    fun `재시작 후에도 기존 토큰이 검증된다`() {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims("restart@example.com")))

        val storage = restartedStorage()
        val restartedSource = JWKSource<SecurityContext> { selector, _ -> selector.select(JWKSet(storage.currentKeys())) }
        val decoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(restartedSource)

        assertThat(decoder.decode(token.tokenValue).subject).isEqualTo("restart@example.com")
    }

    @Test
    fun `회전 - 두 키 공존, 신규 토큰은 새 kid, 구 키 토큰은 유예 동안 검증되며 grace 경과 purge 후 소멸한다`() {
        val oldKid = jwkStorageService.activeKid()
        val encoder = NimbusJwtEncoder(jwkSource)
        val oldToken =
            encoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(oldKid).build(),
                    claims("rotate@example.com"),
                ),
            )

        val newKid = jwkStorageService.rotate()
        assertThat(newKid).isNotEqualTo(oldKid)
        assertThat(jwkStorageService.activeKid()).isEqualTo(newKid)
        assertThat(jwkStorageService.currentKeys().map { it.keyID }).contains(oldKid, newKid)

        // JWKS 게시에 두 키 공존 + 개인키 파라미터 미노출(publicOnly)
        val jwks =
            mockMvc
                .perform(get("/oauth2/jwks"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(jwks).contains(oldKid).contains(newKid)
        assertThat(jwks).doesNotContain("\"d\":")

        // 신규 토큰은 새 active kid 로 서명된다
        val newToken =
            encoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build(),
                    claims("new@example.com"),
                ),
            )
        assertThat(newToken.headers["kid"]).isEqualTo(newKid)

        // 구 키로 서명된 토큰은 유예 구간 동안 여전히 검증된다 (kid 매칭)
        val decoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)
        assertThat(decoder.decode(oldToken.tokenValue).subject).isEqualTo("rotate@example.com")

        // grace 경과 → purge → 구 키 소멸
        val retired = jwkKeyRepository.findById(oldKid).orElseThrow()
        retired.retiredAt = Instant.now().minus(jwkProperties.retirementGrace).minusSeconds(60)
        jwkKeyRepository.save(retired)

        assertThat(jwkStorageService.purgeExpired()).isEqualTo(1)
        assertThat(jwkKeyRepository.findById(oldKid)).isEmpty
        assertThat(jwkStorageService.currentKeys().map { it.keyID }).doesNotContain(oldKid)
    }

    @Test
    fun `회전 유예 구간에서도 kid 고정으로 토큰 발급이 성공한다`() {
        jwkStorageService.rotate() // ACTIVE + RETIRED 공존 상태
        val encoder = NimbusJwtEncoder(jwkSource)

        // kid 미지정이면 서명 키 다중 매칭으로 예외 — kid 고정이 필수인 이유(회귀 방지 기준선)
        assertThatThrownBy {
            encoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(),
                    claims("no-kid@example.com"),
                ),
            )
        }.isInstanceOf(JwtEncodingException::class.java)

        // 실제 토큰 커스터마이저가 jwsHeader 에 active kid 를 고정해 발급이 성공한다
        val headersBuilder = JwsHeader.with(SignatureAlgorithm.RS256)
        val claimsBuilder =
            JwtClaimsSet
                .builder()
                .subject("grace@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
        val registeredClient =
            RegisteredClient
                .withId("jwk-test-client")
                .clientId("jwk-test-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/void")
                .build()
        val context =
            JwtEncodingContext
                .with(headersBuilder, claimsBuilder)
                .registeredClient(registeredClient)
                .principal(UsernamePasswordAuthenticationToken("grace@example.com", "n/a"))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizedScopes(emptySet())
                .build()

        jwtTokenCustomizer.customize(context)

        val token = encoder.encode(JwtEncoderParameters.from(headersBuilder.build(), claimsBuilder.build()))
        assertThat(token.headers["kid"]).isEqualTo(jwkStorageService.activeKid())
    }
}
