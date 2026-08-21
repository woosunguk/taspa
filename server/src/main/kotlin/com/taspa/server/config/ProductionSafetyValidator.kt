package com.taspa.server.config

import com.taspa.server.passkey.WebAuthnProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.net.URI

/**
 * prod 프로파일 기동 시 외부화된 설정이 실제 프로덕션 안전값인지 검증하는 fail-fast.
 * EncryptionConfig(암호화 키)와 동일한 철학 — misconfig 는 조용히 뜨지 않고 기동을 실패시킨다.
 *
 * ${ENV} 미주입은 이미 플레이스홀더 해석 실패로 기동을 막지만, "http/localhost issuer" 나
 * "dev 기본 DB 비밀번호(taspa)" 처럼 값은 있으나 안전하지 않은 경우는 여기서 잡는다.
 *
 * 검증 로직은 순수 함수(ProductionConfigChecks)로 분리해 Spring 컨텍스트 없이 단위 테스트한다.
 * 이 빈은 @Profile("prod") 라 test/dev 컨텍스트에서는 생성되지 않는다(기존 테스트 영향 없음).
 */
@Configuration
@Profile("prod")
class ProductionSafetyValidator(
    @Value("\${taspa.issuer-uri}") issuerUri: String,
    @Value("\${spring.datasource.password}") datasourcePassword: String,
    @Value("\${taspa.password-reset.base-url}") passwordResetBaseUrl: String,
    @Value("\${taspa.magic-link.base-url}") magicLinkBaseUrl: String,
    // ★사용자에게 나가는 링크 base 는 **전부** 여기서 검사한다. 초대 base 가 빠져 있던 동안
    // prod 에서 조직 초대·청구서 초안 알림 링크가 dev 기본값(http://localhost:9100)을 가리켰다 —
    // 받는 사람에겐 그냥 열리지 않는 링크이고 서버 로그에는 아무 흔적도 남지 않는다.
    @Value("\${taspa.org-invitation.base-url}") orgInvitationBaseUrl: String,
    webAuthnProperties: WebAuthnProperties,
) {
    private val log = LoggerFactory.getLogger(ProductionSafetyValidator::class.java)

    init {
        ProductionConfigChecks.validateAll(
            issuerUri = issuerUri,
            datasourcePassword = datasourcePassword,
            passwordResetBaseUrl = passwordResetBaseUrl,
            magicLinkBaseUrl = magicLinkBaseUrl,
            orgInvitationBaseUrl = orgInvitationBaseUrl,
            webAuthnRpId = webAuthnProperties.rpId,
            webAuthnAllowedOrigins = webAuthnProperties.allowedOrigins,
        )
        log.info(
            "production safety validation passed (issuer={}, rpId={}, allowedOrigins={})",
            issuerUri,
            webAuthnProperties.rpId,
            webAuthnProperties.allowedOrigins,
        )
    }
}

/**
 * prod 안전성 순수 검증 로직. throw IllegalStateException 으로 위반을 알린다.
 * (Spring 없이 단위 테스트 — ProductionConfigChecksTest)
 */
object ProductionConfigChecks {
    /** 소스에 커밋된 dev DB 비밀번호(docker-compose·application.yml). prod 에서 이 값이면 기동 중단. */
    private const val DEV_DB_PASSWORD = "taspa"

    /** application.yml 의 dev 폴백 암호화 키 — 소스에 공개돼 있어 prod 에서 실제 키로 쓰이면 기동 중단. */
    const val DEV_DEFAULT_ENCRYPTION_KEY = "dev-only-key-do-not-use-in-production"

    /** 암호화 키 최소 길이. `openssl rand -base64 32`(44자)를 넉넉히 통과시키면서 패스프레이즈도 허용하는 선. */
    private const val MIN_KEY_LENGTH = 32

    /**
     * 최소 서로 다른 문자 수. 길이만 보면 'aaaa…a'(32자) 같은 반복 패턴이 통과하는데,
     * AesEncryptionService 는 salt 없는 SHA-256 1회로 키를 만들어 공격 비용이 곧 원문 추측 비용이다
     * — 길이가 아니라 원문 자체의 엔트로피가 유일한 방어선이다.
     * 무작위 32자라면 서로 다른 문자가 20종 이상이라 오탐 여지는 사실상 없다.
     */
    private const val MIN_KEY_DISTINCT_CHARS = 10

    private const val KEY_FIX_HINT = "openssl rand -base64 32 로 생성한 값을 주입하라"

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]")

    fun validateAll(
        issuerUri: String,
        datasourcePassword: String,
        passwordResetBaseUrl: String,
        magicLinkBaseUrl: String,
        orgInvitationBaseUrl: String,
        webAuthnRpId: String,
        webAuthnAllowedOrigins: List<String>,
    ) {
        checkPublicHttpsUrl(issuerUri, "taspa.issuer-uri(TASPA_ISSUER_URI)")
        checkPublicHttpsUrl(passwordResetBaseUrl, "taspa.password-reset.base-url")
        checkPublicHttpsUrl(magicLinkBaseUrl, "taspa.magic-link.base-url")
        checkPublicHttpsUrl(orgInvitationBaseUrl, "taspa.org-invitation.base-url")
        checkDatasourcePassword(datasourcePassword)
        checkWebAuthn(webAuthnRpId, webAuthnAllowedOrigins)
    }

    /**
     * 암호화 키(MFA·JWK) prod 강도 검증.
     *
     * validateAll 에 넣지 않은 이유는 순서다 — ProductionSafetyValidator 빈이 뜨기 전에
     * EncryptionConfig 의 암호화 빈이 이미 생성돼 키를 소비할 수 있어서, 이 검사만은
     * 키를 소비하는 EncryptionConfig 에서 직접 호출한다(로직은 다른 prod 검증과 같은 자리에 둔다).
     */
    fun checkEncryptionKeys(
        mfaKey: String,
        jwkKey: String,
    ) {
        checkEncryptionKey(mfaKey, "MFA_ENCRYPTION_KEY")
        checkEncryptionKey(jwkKey, "TASPA_JWK_ENCRYPTION_KEY")
        check(mfaKey != jwkKey) {
            "prod: MFA_ENCRYPTION_KEY 와 TASPA_JWK_ENCRYPTION_KEY 가 같은 값이다(또는 JWK 키가 비어 MFA 키로 폴백했다). " +
                "키를 나눈 목적은 한쪽 유출이 다른 저장물로 번지지 않게 하는 것인데 같은 값이면 그 격리가 사라진다 — " +
                "TOTP 시크릿 유출이 곧 JWT 서명 개인키(전체 토큰 위조) 유출이 된다. 각각 $KEY_FIX_HINT."
        }
    }

    fun checkEncryptionKey(
        key: String,
        name: String,
    ) {
        check(key.isNotBlank()) {
            "prod: $name 이 비어 있다 — $KEY_FIX_HINT."
        }
        check(key != DEV_DEFAULT_ENCRYPTION_KEY) {
            "prod: $name 이 소스에 공개된 dev 기본값이다(이 키로 암호화된 저장물은 누구나 복호화할 수 있다) — $KEY_FIX_HINT."
        }
        // base64 로 32바이트를 디코드하면 문자열이 43~44자라, 별도 base64 분기는 길이 규칙에 이미 포함된다.
        check(key.length >= MIN_KEY_LENGTH) {
            "prod: $name 이 너무 짧다(${key.length}자). " +
                "키는 salt 없는 SHA-256 1회로 AES 키가 되므로 원문 자체가 전수대입 대상이다 — " +
                "${MIN_KEY_LENGTH}자 이상이어야 한다($KEY_FIX_HINT — 44자)."
        }
        check(key.toSet().size >= MIN_KEY_DISTINCT_CHARS) {
            "prod: $name 이 길이만 채운 반복 패턴이다(서로 다른 문자 ${key.toSet().size}종). " +
                "길이가 아니라 무작위성이 방어선이다 — $KEY_FIX_HINT."
        }
    }

    fun checkPublicHttpsUrl(
        value: String,
        name: String,
    ) {
        val uri =
            try {
                URI(value)
            } catch (e: Exception) {
                throw IllegalStateException("prod: $name 이 유효한 URL 이 아니다: '$value'", e)
            }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        check(scheme == "https") {
            "prod: $name 은 https 여야 한다(현재 '$value'). 평문 http issuer/링크는 금지 — 발급 토큰·메일 링크가 안전하지 않다."
        }
        check(host != null && host !in LOCAL_HOSTS) {
            "prod: $name 의 호스트가 localhost 계열이면 안 된다(현재 '$value') — 공개 도메인을 주입하라."
        }
    }

    fun checkDatasourcePassword(password: String) {
        check(password.isNotBlank()) {
            "prod: spring.datasource.password 가 비어 있다 — DB_PASSWORD 를 설정하라."
        }
        check(password != DEV_DB_PASSWORD) {
            "prod: spring.datasource.password 가 소스에 커밋된 dev 기본값('$DEV_DB_PASSWORD')이다 — DB_PASSWORD 를 실제 시크릿으로 설정하라."
        }
    }

    fun checkWebAuthn(
        rpId: String,
        allowedOrigins: List<String>,
    ) {
        check(rpId.lowercase() !in LOCAL_HOSTS) {
            "prod: taspa.webauthn.rp-id 가 localhost 다 — TASPA_WEBAUTHN_RP_ID 를 실제 도메인으로 설정하라(불일치 시 패스키가 브라우저 단에서 조용히 실패)."
        }
        check(allowedOrigins.isNotEmpty()) {
            "prod: taspa.webauthn.allowed-origins 가 비어 있다 — TASPA_WEBAUTHN_ALLOWED_ORIGINS 를 설정하라."
        }
        allowedOrigins.forEach { origin ->
            check(origin.startsWith("https://")) {
                "prod: taspa.webauthn.allowed-origins 는 https 여야 한다(현재 '$origin')."
            }
            check(LOCAL_HOSTS.none { host -> origin.contains("://$host") }) {
                "prod: taspa.webauthn.allowed-origins 에 localhost 오리진이 있다: '$origin'."
            }
        }
    }
}
