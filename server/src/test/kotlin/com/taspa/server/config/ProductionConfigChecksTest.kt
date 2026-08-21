package com.taspa.server.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * prod 안전성 fail-fast 순수 단위 테스트(Spring 컨텍스트 불필요).
 * ProductionSafetyValidator 는 @Profile("prod") 라 test 컨텍스트에서 뜨지 않으므로 로직만 직접 검증한다.
 */
class ProductionConfigChecksTest {
    @Test
    fun `valid production config passes`() {
        assertThatCode {
            ProductionConfigChecks.validateAll(
                issuerUri = "https://id.example.com",
                datasourcePassword = "a-strong-db-secret",
                passwordResetBaseUrl = "https://id.example.com",
                magicLinkBaseUrl = "https://id.example.com",
                orgInvitationBaseUrl = "https://id.example.com",
                webAuthnRpId = "id.example.com",
                webAuthnAllowedOrigins = listOf("https://id.example.com"),
            )
        }.doesNotThrowAnyException()
    }

    /**
     * ★초대 base 가 검사 목록에서 빠져 있던 동안 prod 에서 **조직 초대·청구서 초안 알림 링크가
     * http://localhost:9100** 을 가리켰다(application.yml 의 dev 기본값에 prod 오버라이드가 없었다).
     * 받는 사람에겐 그냥 열리지 않는 링크라 온보딩이 통째로 막히는데 서버 로그에는 흔적이 없다.
     */
    @Test
    fun `dev default org invitation base url is rejected`() {
        assertThatThrownBy {
            ProductionConfigChecks.validateAll(
                issuerUri = "https://id.example.com",
                datasourcePassword = "a-strong-db-secret",
                passwordResetBaseUrl = "https://id.example.com",
                magicLinkBaseUrl = "https://id.example.com",
                orgInvitationBaseUrl = "http://localhost:9100",
                webAuthnRpId = "id.example.com",
                webAuthnAllowedOrigins = listOf("https://id.example.com"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("taspa.org-invitation.base-url")
    }

    @Test
    fun `http issuer is rejected`() {
        assertThatThrownBy { ProductionConfigChecks.checkPublicHttpsUrl("http://id.example.com", "issuer") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `localhost issuer is rejected even over https`() {
        assertThatThrownBy { ProductionConfigChecks.checkPublicHttpsUrl("https://localhost:9100", "issuer") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `dev default db password is rejected`() {
        assertThatThrownBy { ProductionConfigChecks.checkDatasourcePassword("taspa") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `blank db password is rejected`() {
        assertThatThrownBy { ProductionConfigChecks.checkDatasourcePassword("") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `strong distinct encryption keys pass`() {
        assertThatCode {
            ProductionConfigChecks.checkEncryptionKeys(
                mfaKey = "5Qk1Zt7cX9pR2vLdN8mYbA3sJfHwG6eUqT0iOxKrPzM=",
                jwkKey = "aB4nR7tYuI2oP9sD3fG6hJ1kL8zX5cV0bN7mQ4wE1rT=",
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `blank encryption key is rejected`() {
        assertThatThrownBy { ProductionConfigChecks.checkEncryptionKey("", "MFA_ENCRYPTION_KEY") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `dev default encryption key is rejected`() {
        assertThatThrownBy {
            ProductionConfigChecks.checkEncryptionKey(
                ProductionConfigChecks.DEV_DEFAULT_ENCRYPTION_KEY,
                "MFA_ENCRYPTION_KEY",
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `short encryption key is rejected`() {
        // 실제 prod 기동에서 경고 없이 통과하던 값들
        assertThatThrownBy { ProductionConfigChecks.checkEncryptionKey("x", "MFA_ENCRYPTION_KEY") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { ProductionConfigChecks.checkEncryptionKey("1234", "TASPA_JWK_ENCRYPTION_KEY") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `long but low entropy encryption key is rejected`() {
        assertThatThrownBy { ProductionConfigChecks.checkEncryptionKey("a".repeat(64), "MFA_ENCRYPTION_KEY") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { ProductionConfigChecks.checkEncryptionKey("abcd".repeat(16), "MFA_ENCRYPTION_KEY") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `identical mfa and jwk keys are rejected even when strong`() {
        val strong = "5Qk1Zt7cX9pR2vLdN8mYbA3sJfHwG6eUqT0iOxKrPzM="
        assertThatThrownBy { ProductionConfigChecks.checkEncryptionKeys(strong, strong) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `localhost webauthn rpId is rejected`() {
        assertThatThrownBy {
            ProductionConfigChecks.checkWebAuthn("localhost", listOf("https://id.example.com"))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `http webauthn origin is rejected`() {
        assertThatThrownBy {
            ProductionConfigChecks.checkWebAuthn("id.example.com", listOf("http://id.example.com"))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `empty webauthn origins are rejected`() {
        assertThatThrownBy {
            ProductionConfigChecks.checkWebAuthn("id.example.com", emptyList())
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
