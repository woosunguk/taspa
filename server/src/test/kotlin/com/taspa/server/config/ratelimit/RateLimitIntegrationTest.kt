package com.taspa.server.config.ratelimit

import com.taspa.server.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * rate limit 필터 통합 검증 — capacity=3 으로 낮춰, 같은 IP 에서 4번째 요청이 429 로 차단되는지 확인.
 * 필터는 스프링 시큐리티 앞에 등록돼(RateLimitConfig), 컨트롤러 진입 전에 흡수한다.
 */
@TestPropertySource(
    properties = [
        "taspa.rate-limit.enabled=true",
        "taspa.rate-limit.capacity=3",
        "taspa.rate-limit.refill-tokens=3",
        "taspa.rate-limit.refill-period-seconds=600",
    ],
)
class RateLimitIntegrationTest : IntegrationTestBase() {
    @Test
    fun `identifier endpoint blocks with 429 after capacity is exceeded`() {
        // 처음 3건은 버킷 용량 내 → 통과(컨트롤러 결과는 무관하므로 상태는 단언하지 않는다).
        repeat(3) {
            mockMvc.perform(post("/login/identifier").param("email", "rl@example.com").with(csrf()))
        }
        // 4번째는 토큰 소진 → 429.
        mockMvc
            .perform(post("/login/identifier").param("email", "rl@example.com").with(csrf()))
            .andExpect(status().isTooManyRequests)
    }
}
