package com.taspa.server.credential

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.MessageDigest

/**
 * HIBP 유출 비밀번호 검사(Stage 7) 통합 테스트. range API 는 WireMock 으로 스텁한다.
 *
 * 이 테스트 클래스만 breach-check 를 enabled=true 로 켜고 api-url 을 WireMock 으로 돌린다
 * (@DynamicPropertySource → 별도 Spring 컨텍스트). 기본 컨텍스트는 enabled=false 이므로 나머지
 * 통합 테스트·dev 는 외부 호출 없이 그대로 동작한다.
 *
 * 시나리오: 유출 O→거부, 유출 X→통과, HIBP 장애(5xx)→fail-open 통과.
 */
class BreachedPasswordIntegrationTest : IntegrationTestBase() {
    companion object {
        @JvmStatic
        val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort()).apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun breachProperties(registry: DynamicPropertyRegistry) {
            registry.add("taspa.password-policy.breach-check.enabled") { "true" }
            registry.add("taspa.password-policy.breach-check.timeout") { "2s" }
            registry.add("taspa.password-policy.breach-check.api-url") { "${wireMock.baseUrl()}/range" }
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMock.stop()
        }
    }

    @Autowired lateinit var passwordPolicyService: PasswordPolicyService

    // 로컬 정책(12자↑·대소문자·숫자·특수문자)을 모두 통과해, 판정에 남는 유일한 변수가 유출 검사이도록 구성.
    private val password = "SecureBreach1!Pw"

    @BeforeEach
    fun setUp() {
        wireMock.resetAll()
    }

    @Test
    fun `breached password is rejected`() {
        stubRange(password, breached = true)
        // 로컬 정책은 전부 통과하므로 남는 위반은 유출 검사 1건뿐이어야 한다.
        assertThat(passwordPolicyService.validate(password)).hasSize(1)
    }

    @Test
    fun `non-breached password passes`() {
        stubRange(password, breached = false)
        assertThat(passwordPolicyService.validate(password)).isEmpty()
    }

    @Test
    fun `HIBP outage fails open and password passes`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/range/${prefixOf(password)}")).willReturn(aResponse().withStatus(500)),
        )
        assertThat(passwordPolicyService.validate(password)).isEmpty()
    }

    // ---- helpers ----

    private fun stubRange(
        password: String,
        breached: Boolean,
    ) {
        val sha1 = sha1UpperHex(password)
        val prefix = sha1.substring(0, 5)
        val suffix = sha1.substring(5)
        // 패딩(count 0)은 실제 유출이 아니어야 한다 — breached=true 여도 count 0 더미는 무시되어야 정상.
        val body =
            if (breached) {
                "$suffix:42\r\n0000000000000000000000000000000000A:0"
            } else {
                // 실제 suffix 는 목록에 없고 다른 더미 suffix 만 count>0 → 유출 아님.
                "0123456789ABCDEF0123456789ABCDEF012:7\r\nFEDCBA9876543210FEDCBA9876543210FED:3"
            }
        wireMock.stubFor(
            get(urlPathEqualTo("/range/$prefix")).willReturn(
                aResponse().withHeader("Content-Type", "text/plain").withBody(body),
            ),
        )
    }

    private fun prefixOf(password: String): String = sha1UpperHex(password).substring(0, 5)

    private fun sha1UpperHex(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
}
