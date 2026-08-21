package com.taspa.server.common.http

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * 요청 상관관계 ID 필터 회귀 — 로그 인젝션 차단과 MDC 누수 방지가 핵심 불변식이다.
 * (MDC 를 지우지 않으면 스레드풀 재사용 시 다음 요청 로그에 남의 ID 가 붙는다)
 */
class CorrelationIdFilterTest {
    private val filter = CorrelationIdFilter()
    private val chain = FilterChain { _, _ -> }

    private fun run(request: MockHttpServletRequest): Pair<MockHttpServletResponse, String?> {
        val response = MockHttpServletResponse()
        var seenInsideChain: String? = null
        filter.doFilter(request, response) { _, _ -> seenInsideChain = MDC.get(MDC_KEY) }
        return response to seenInsideChain
    }

    @Test fun `generates id when header absent`() {
        val (response, inside) = run(MockHttpServletRequest("GET", "/login"))
        assertNotNull(inside, "체인 실행 중에는 MDC 에 상관관계 ID 가 있어야 한다")
        assertEquals(inside, response.getHeader(RESPONSE_HEADER))
    }

    @Test fun `reuses valid inbound header`() {
        val request =
            MockHttpServletRequest("GET", "/login").apply {
                addHeader(RESPONSE_HEADER, "edge-abc_123")
            }
        val (response, inside) = run(request)
        assertEquals("edge-abc_123", inside)
        assertEquals("edge-abc_123", response.getHeader(RESPONSE_HEADER))
    }

    @Test fun `rejects log injection payloads and oversized values`() {
        val hostile =
            listOf(
                "abc\nWARN fake log line", // 개행 주입 → 가짜 로그 라인 위조
                "abc\r\nINFO spoofed",
                "has space",
                "semi;colon",
                "a".repeat(65), // 길이 상한 초과
                "",
            )
        hostile.forEach { value ->
            val request = MockHttpServletRequest("GET", "/login").apply { addHeader(RESPONSE_HEADER, value) }
            val (_, inside) = run(request)
            assertNotNull(inside)
            assertFalse(inside == value, "위험한 인바운드 값이 그대로 채택되면 안 된다: ${'$'}value")
            assertTrue(inside!!.all { it.isSafeIdChar() }, "생성된 ID 는 안전 문자만 포함해야 한다")
        }
    }

    @Test fun `removes MDC after request even when chain throws`() {
        val request = MockHttpServletRequest("GET", "/login")
        val response = MockHttpServletResponse()
        runCatching {
            filter.doFilter(request, response) { _, _ -> throw IllegalStateException("boom") }
        }
        assertNull(MDC.get(MDC_KEY), "체인이 예외를 던져도 MDC 는 정리되어야 한다(스레드풀 누수 방지)")
    }

    private fun Char.isSafeIdChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'

    private companion object {
        const val MDC_KEY = "correlationId"
        const val RESPONSE_HEADER = "X-Request-Id"
    }
}
