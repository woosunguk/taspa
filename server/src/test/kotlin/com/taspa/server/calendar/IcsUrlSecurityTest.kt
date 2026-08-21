package com.taspa.server.calendar

import com.taspa.server.common.exception.AuthException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * ★SSRF 방어(Phase 0-E 필수) 순수 단위 테스트. 리터럴 IP·URL 만 사용하므로 DNS·네트워크·컨테이너가
 * 필요 없다(결정적). https 강제, 사설/루프백/링크로컬/유니크로컬/CGNAT/메타데이터 IP 거부를 검증한다.
 */
class IcsUrlSecurityTest {
    private val security = IcsUrlSecurity()

    @Test
    fun `http 스킴은 거부한다`() {
        assertThatThrownBy { security.validate("http://example.com/cal.ics") }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `비 https 스킴(ftp file)은 거부한다`() {
        assertThatThrownBy { security.validate("ftp://example.com/cal.ics") }
            .isInstanceOf(AuthException::class.java)
        assertThatThrownBy { security.validate("file:///etc/passwd") }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `루프백(127_0_0_1)은 거부한다`() {
        assertThatThrownBy { security.validate("https://127.0.0.1/cal.ics") }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `IPv6 루프백은 거부한다`() {
        assertThatThrownBy { security.validate("https://[::1]/cal.ics") }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `클라우드 메타데이터(169_254_169_254)는 거부한다`() {
        assertThatThrownBy { security.validate("https://169.254.169.254/latest/meta-data/") }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `사설 대역(10 172_16 192_168)은 거부한다`() {
        listOf("https://10.0.0.5/x", "https://172.16.0.1/x", "https://192.168.1.10/x").forEach {
            assertThatThrownBy { security.validate(it) }
                .describedAs(it)
                .isInstanceOf(AuthException::class.java)
        }
    }

    @Test
    fun `상대 URL 은 거부한다`() {
        assertThatThrownBy { security.validate("/relative/path.ics") }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `공인 IP 는 통과한다`() {
        val uri = security.validate("https://1.1.1.1/cal.ics")
        assertThat(uri.host).isEqualTo("1.1.1.1")
    }

    @Test
    fun `isDisallowedAddress 는 사설_루프백_링크로컬_CGNAT_ULA 를 모두 막는다`() {
        val disallowed =
            listOf(
                "127.0.0.1",
                "10.0.0.1",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.0.1",
                "169.254.169.254",
                "169.254.0.1",
                "100.64.0.1",
                "0.0.0.0",
                "::1",
                "fc00::1",
                "fd00::1",
                "fe80::1",
            )
        disallowed.forEach { ip ->
            assertThat(security.isDisallowedAddress(InetAddress.getByName(ip)))
                .describedAs("disallowed: $ip")
                .isTrue()
        }
    }

    @Test
    fun `isDisallowedAddress 는 공인 IP 를 허용한다`() {
        listOf("8.8.8.8", "1.1.1.1", "93.184.216.34").forEach { ip ->
            assertThat(security.isDisallowedAddress(InetAddress.getByName(ip)))
                .describedAs("allowed: $ip")
                .isFalse()
        }
    }
}
