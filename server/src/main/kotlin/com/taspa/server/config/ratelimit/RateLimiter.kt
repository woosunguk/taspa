package com.taspa.server.config.ratelimit

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 경량 인메모리 토큰 버킷 rate limiter. 키(예: "ip|group")별 버킷을 유지한다.
 *
 * 외부 의존성 없이 동작하는 보완재다 — 프로덕션에서는 게이트웨이/리버스 프록시 rate limit 이
 * 1차 방어이고, 이 필터는 앱단 최후 방어선(단일 인스턴스 기준)이다. 다중 인스턴스에서는
 * 인스턴스별 카운팅이므로 실효 한도가 인스턴스 수만큼 커진다는 점을 감안한다(문서화).
 *
 * nanoTime 을 주입 가능하게 해 단위 테스트에서 시간을 결정적으로 제어한다.
 */
class RateLimiter(
    private val capacity: Long,
    private val refillTokens: Long,
    private val refillPeriod: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(refillTokens > 0) { "refillTokens must be > 0" }
        require(!refillPeriod.isZero && !refillPeriod.isNegative) { "refillPeriod must be positive" }
    }

    private class Bucket(
        var tokens: Double,
        var lastRefillNanos: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val tokensPerNano = refillTokens.toDouble() / refillPeriod.toNanos()

    /** 토큰 1개를 소비할 수 있으면 true(요청 허용), 없으면 false(429 로 차단). */
    fun tryAcquire(key: String): Boolean {
        val now = nanoTime()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), now) }
        synchronized(bucket) {
            refill(bucket, now)
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    private fun refill(
        bucket: Bucket,
        now: Long,
    ) {
        val elapsed = now - bucket.lastRefillNanos
        if (elapsed > 0) {
            bucket.tokens = min(capacity.toDouble(), bucket.tokens + elapsed * tokensPerNano)
            bucket.lastRefillNanos = now
        }
    }

    /** 가득 찬(오래 미사용) 버킷 제거 — 인메모리 누수 방지(스케줄러가 주기 호출). */
    fun evictFull() {
        val now = nanoTime()
        buckets.entries.removeIf { (_, bucket) ->
            synchronized(bucket) {
                refill(bucket, now)
                bucket.tokens >= capacity.toDouble()
            }
        }
    }

    /** 현재 추적 중인 키(버킷) 수 — 관측/테스트용. */
    fun trackedKeys(): Int = buckets.size
}
