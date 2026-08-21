package com.taspa.server.calendar

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * iCal 구독 URL 의 SSRF 방어(Phase 0-E ★필수).
 *
 * 구독 source_url 은 관리자가 등록한 임의 외부 URL 이므로, 서버가 이를 fetch 하는 순간 내부망·클라우드
 * 메타데이터 엔드포인트로의 요청 위조(SSRF)가 가능하다. 다음을 강제한다:
 *  1) https 스킴만 허용(http·file·ftp·gopher 등 거부).
 *  2) 호스트가 해석되는 모든 IP 를 검사 — 루프백·사설·링크로컬·유니크로컬·CGNAT·멀티캐스트·
 *     any-local·클라우드 메타데이터(169.254.169.254)면 거부(하나라도 걸리면 거부).
 *  3) 리다이렉트도 매 홉 재검증한다(IcsSubscriptionFetcher 가 [validate] 를 다시 호출).
 *
 * 참고(잔여 위험): 해석-후-접속 사이의 DNS 리바인딩(TOCTOU)은 해석된 모든 IP 를 검사함으로써 창을 크게
 * 줄이지만 완전히 제거하진 못한다. IP 핀 접속으로의 강화는 후속 과제.
 */
@Component
class IcsUrlSecurity {
    /** URL 을 검증하고 정규화된 URI 를 반환한다. 위반 시 [AuthException]. */
    fun validate(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        val uri =
            runCatching { URI(trimmed) }.getOrNull()
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "잘못된 URL 형식입니다")
        if (!uri.isAbsolute || uri.scheme == null) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "절대 URL(https)이어야 합니다")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "https URL 만 허용됩니다 (scheme=${uri.scheme})")
        }
        val host =
            uri.host
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "호스트를 확인할 수 없습니다")

        val addresses =
            try {
                InetAddress.getAllByName(host)
            } catch (ex: UnknownHostException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "호스트를 해석할 수 없습니다: $host")
            }
        if (addresses.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "호스트를 해석할 수 없습니다: $host")
        }
        // 해석된 IP 중 하나라도 금지 대역이면 거부한다(리바인딩 창 최소화).
        addresses.forEach { addr ->
            if (isDisallowedAddress(addr)) {
                throw AuthException(
                    ErrorCode.VALIDATION_ERROR,
                    "허용되지 않는 대상 주소입니다: ${addr.hostAddress}",
                )
            }
        }
        return uri
    }

    /**
     * 금지 대상 IP 판정. 루프백·any-local·링크로컬·사설(site-local)·멀티캐스트, IPv6 유니크로컬(fc00::/7),
     * IPv4 CGNAT(100.64/10), 클라우드 메타데이터(169.254.169.254), IPv4-mapped IPv6 를 모두 걸러낸다.
     */
    fun isDisallowedAddress(address: InetAddress): Boolean {
        // IPv4-mapped IPv6(::ffff:a.b.c.d)는 내장 IPv4 로 재판정한다(우회 차단).
        val addr = unwrapV4Mapped(address)
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address
        when (bytes.size) {
            4 -> {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // 169.254.169.254 클라우드 메타데이터(링크로컬에 포함되지만 명시적으로 재확인).
                if (b0 == 169 && b1 == 254) return true
                // 100.64.0.0/10 CGNAT.
                if (b0 == 100 && b1 in 64..127) return true
                // 0.0.0.0/8 "this network".
                if (b0 == 0) return true
            }
            16 -> {
                // fc00::/7 IPv6 유니크 로컬 주소(isSiteLocalAddress 가 IPv6 ULA 를 못 잡는 케이스 보강).
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
            }
        }
        return false
    }

    private fun unwrapV4Mapped(address: InetAddress): InetAddress {
        val b = address.address
        if (b.size == 16) {
            val v4Mapped =
                (0..9).all { b[it].toInt() == 0 } &&
                    (b[10].toInt() and 0xFF) == 0xFF &&
                    (b[11].toInt() and 0xFF) == 0xFF
            if (v4Mapped) {
                return runCatching {
                    InetAddress.getByAddress(byteArrayOf(b[12], b[13], b[14], b[15]))
                }.getOrDefault(address)
            }
        }
        return address
    }
}
