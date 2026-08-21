package com.taspa.server.config

import com.taspa.server.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * prod 보안 헤더 검증 — taspa.security.headers.enabled=true 일 때 로그인 UI 에 CSP·frame-options·
 * Referrer-Policy 가 실린다. (HSTS 는 secure 요청에서만 방출되므로 http MockMvc 에서는 검증하지 않는다.)
 */
@TestPropertySource(properties = ["taspa.security.headers.enabled=true"])
class SecurityHeadersIntegrationTest : IntegrationTestBase() {
    @Test
    fun `login page carries hardening headers when enabled`() {
        mockMvc
            .perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
            .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
    }
}
