package com.taspa.server.common.http

import jakarta.servlet.http.HttpServletRequest

/**
 * 요청의 클라이언트 식별 정보(IP, User-Agent 요약 라벨). 로그인 이벤트·신뢰 기기 표시에 쓰이고,
 * **리스크 판정(RiskEvaluationService 의 unseenDevice/rapidIpChange) 입력**이기도 하다.
 * ua 파싱은 라이브러리 없이 대표 패턴만 요약한다 — 정확한 핑거프린팅이 목적이 아니라
 * 사용자가 알아볼 수 있는 "Chrome / macOS" 수준의 라벨이 목적이다.
 */
object RequestClientInfo {
    const val UNKNOWN_DEVICE = "알 수 없는 기기"

    /**
     * X-Forwarded-For 는 **직접 읽지 않는다** — 클라이언트가 임의로 실을 수 있는 헤더라,
     * 이 값이 인증 게이트 판정(리스크 신호)에 들어가는 이상 직결 요청의 XFF 를 신뢰하면
     * 피해자의 과거 IP 를 헤더에 실어 unseenDevice/rapidIpChange 를 스푸핑으로 무력화할 수 있다.
     * 리버스 프록시 뒤에 배포할 때는 `server.forward-headers-strategy=native`
     * (+ `server.tomcat.remoteip.internal-proxies` 로 신뢰 프록시 지정)를 설정하라 —
     * 그러면 RemoteIpValve 가 신뢰 프록시가 붙인 XFF 만으로 remoteAddr 자체를 재작성하므로
     * 여기서는 항상 remoteAddr 만 보면 된다(architecture.md §8.10).
     */
    fun ip(request: HttpServletRequest): String = (request.remoteAddr ?: "unknown").take(64)

    fun uaLabel(request: HttpServletRequest): String {
        val userAgent = request.getHeader("User-Agent")?.takeIf { it.isNotBlank() } ?: return UNKNOWN_DEVICE
        val browser = browserOf(userAgent)
        val os = osOf(userAgent)
        return when {
            browser == null && os == null -> UNKNOWN_DEVICE
            browser == null -> os!!
            os == null -> browser
            else -> "$browser / $os"
        }.take(255)
    }

    private fun browserOf(ua: String): String? =
        when {
            ua.contains("Edg/") || ua.contains("Edge/") -> "Edge"
            ua.contains("OPR/") || ua.contains("Opera") -> "Opera"
            ua.contains("SamsungBrowser") -> "Samsung Internet"
            ua.contains("Whale/") -> "Whale"
            ua.contains("Chrome/") || ua.contains("CriOS/") -> "Chrome"
            ua.contains("Firefox/") || ua.contains("FxiOS/") -> "Firefox"
            ua.contains("Safari/") -> "Safari"
            else -> null
        }

    private fun osOf(ua: String): String? =
        when {
            ua.contains("Windows") -> "Windows"
            ua.contains("iPhone") || ua.contains("iPad") -> "iOS"
            ua.contains("Mac OS X") || ua.contains("Macintosh") -> "macOS"
            ua.contains("Android") -> "Android"
            ua.contains("Linux") -> "Linux"
            else -> null
        }
}
