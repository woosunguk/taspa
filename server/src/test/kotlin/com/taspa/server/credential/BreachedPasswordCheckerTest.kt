package com.taspa.server.credential

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration

/**
 * [BreachedPasswordChecker] 장애 처리 분기 단위 테스트.
 *
 * 통합 테스트(BreachedPasswordIntegrationTest)는 fail-open(기본) 경로만 다룬다. 이 클래스는 나머지 절반 —
 * fail-closed(failOpen=false) 경로와 **실제 소켓 read 타임아웃** 경로 — 를 커버한다. Spring 컨텍스트 없이
 * checker 를 직접 생성하므로(각 테스트가 자기 failOpen/timeout 로 구성) 빠르고 분기별로 정확하다.
 */
class BreachedPasswordCheckerTest {
    companion object {
        @JvmStatic
        val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort()).apply { start() }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMock.stop()
        }
    }

    private val password = "SecureBreach1!Pw"

    @BeforeEach
    fun setUp() {
        wireMock.resetAll()
    }

    private fun checker(
        failOpen: Boolean,
        timeout: Duration = Duration.ofSeconds(2),
    ): BreachedPasswordChecker =
        BreachedPasswordChecker(
            PasswordPolicyProperties(
                breachCheck =
                    PasswordPolicyProperties.BreachCheck(
                        enabled = true,
                        timeout = timeout,
                        failOpen = failOpen,
                        apiUrl = "${wireMock.baseUrl()}/range",
                    ),
            ),
        )

    @Test
    fun `fail-closed rejects password on HIBP 5xx`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/range/${prefixOf(password)}")).willReturn(aResponse().withStatus(500)),
        )
        // failOpen=false → 장애를 "유출로 간주(거부)" = true.
        assertThat(checker(failOpen = false).isBreached(password)).isTrue()
    }

    @Test
    fun `fail-closed rejects password on socket read timeout`() {
        // read 타임아웃(300ms)보다 오래 지연 → SocketTimeoutException 유발(실제 소켓 타임아웃 경로).
        wireMock.stubFor(
            get(urlPathEqualTo("/range/${prefixOf(password)}"))
                .willReturn(aResponse().withFixedDelay(3_000).withBody("dummy")),
        )
        assertThat(checker(failOpen = false, timeout = Duration.ofMillis(300)).isBreached(password)).isTrue()
    }

    @Test
    fun `fail-open passes password on socket read timeout`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/range/${prefixOf(password)}"))
                .willReturn(aResponse().withFixedDelay(3_000).withBody("dummy")),
        )
        // failOpen=true → 소켓 타임아웃이라도 통과(가용성 우선) = false.
        assertThat(checker(failOpen = true, timeout = Duration.ofMillis(300)).isBreached(password)).isFalse()
    }

    // ---- helpers ----

    private fun prefixOf(password: String): String = sha1UpperHex(password).substring(0, 5)

    private fun sha1UpperHex(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
}
