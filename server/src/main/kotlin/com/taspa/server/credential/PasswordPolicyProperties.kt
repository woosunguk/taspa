package com.taspa.server.credential

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "taspa.password-policy")
data class PasswordPolicyProperties(
    val minLength: Int = 12,
    val maxLength: Int = 128,
    val requireUppercase: Boolean = true,
    val requireLowercase: Boolean = true,
    val requireDigit: Boolean = true,
    val requireSpecial: Boolean = true,
    val breachCheck: BreachCheck = BreachCheck(),
) {
    /**
     * HaveIBeenPwned(HIBP) 유출 비밀번호 검사 설정.
     *
     * - [enabled]=false(기본): 검사를 전혀 수행하지 않는다 → 기존 테스트·dev 무영향.
     * - [timeout]: range API 연결/읽기 타임아웃(기본 2s). 로그인·가입 지연을 상한한다.
     * - [failOpen]=true(기본): HIBP 장애(타임아웃·5xx·네트워크 오류) 시 "유출 아님"으로 간주해 통과시킨다.
     *   가용성 우선 — 외부 서비스 장애가 정상 사용자의 가입/변경을 막지 않게 한다. false 로 두면
     *   장애 시 안전 측(거부)으로 fail-closed 한다.
     * - [apiUrl]: range 엔드포인트 base URL(SHA-1 prefix 5자를 경로로 붙인다). 테스트가 WireMock 으로 대체.
     */
    data class BreachCheck(
        val enabled: Boolean = false,
        val timeout: Duration = Duration.ofSeconds(2),
        val failOpen: Boolean = true,
        val apiUrl: String = "https://api.pwnedpasswords.com/range",
    )
}
