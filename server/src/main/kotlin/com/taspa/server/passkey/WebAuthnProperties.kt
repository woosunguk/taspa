package com.taspa.server.passkey

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * WebAuthn Relying Party 설정. rpId 와 allowedOrigins 가 실제 접속 오리진과 불일치하면
 * 브라우저 단에서 조용히 실패하므로 dev 기본값을 localhost:9100 에 맞춘다.
 */
@ConfigurationProperties(prefix = "taspa.webauthn")
data class WebAuthnProperties(
    val rpId: String = "localhost",
    val rpName: String = "taspa",
    val allowedOrigins: List<String> = listOf("http://localhost:9100"),
)
