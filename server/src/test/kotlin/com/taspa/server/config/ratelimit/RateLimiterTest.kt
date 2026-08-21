package com.taspa.server.config.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 토큰 버킷 rate limiter 순수 단위 테스트(Spring/Docker 불필요) — 주입된 nanoTime 으로 시간을 결정적으로 제어.
 */
class RateLimiterTest {
    @Test
    fun `allows up to capacity then blocks`() {
        var now = 0L
        val limiter = RateLimiter(capacity = 3, refillTokens = 3, refillPeriod = Duration.ofSeconds(60)) { now }

        assertThat(limiter.tryAcquire("k")).isTrue()
        assertThat(limiter.tryAcquire("k")).isTrue()
        assertThat(limiter.tryAcquire("k")).isTrue()
        assertThat(limiter.tryAcquire("k")).isFalse()
    }

    @Test
    fun `refills tokens over elapsed time`() {
        var now = 0L
        val limiter = RateLimiter(capacity = 3, refillTokens = 3, refillPeriod = Duration.ofSeconds(60)) { now }
        repeat(3) { limiter.tryAcquire("k") }
        assertThat(limiter.tryAcquire("k")).isFalse()

        // 3토큰/60초 = 0.05토큰/초. 20초 경과 → 1토큰 리필.
        now += Duration.ofSeconds(20).toNanos()
        assertThat(limiter.tryAcquire("k")).isTrue()
        assertThat(limiter.tryAcquire("k")).isFalse()
    }

    @Test
    fun `distinct keys have independent buckets`() {
        var now = 0L
        val limiter = RateLimiter(capacity = 1, refillTokens = 1, refillPeriod = Duration.ofSeconds(60)) { now }

        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("a")).isFalse()
        // 다른 키는 별도 버킷 → 영향 없음.
        assertThat(limiter.tryAcquire("b")).isTrue()
    }

    @Test
    fun `evictFull removes buckets that have refilled to capacity`() {
        var now = 0L
        val limiter = RateLimiter(capacity = 2, refillTokens = 2, refillPeriod = Duration.ofSeconds(60)) { now }
        limiter.tryAcquire("a")
        limiter.tryAcquire("b")
        assertThat(limiter.trackedKeys()).isEqualTo(2)

        // 충분히 경과 → 두 버킷 모두 capacity 로 리필 → 제거 대상.
        now += Duration.ofSeconds(300).toNanos()
        limiter.evictFull()
        assertThat(limiter.trackedKeys()).isEqualTo(0)
    }
}
