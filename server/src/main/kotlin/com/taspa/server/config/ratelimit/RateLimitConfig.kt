package com.taspa.server.config.ratelimit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.servlet.LocaleResolver
import java.time.Duration

/**
 * rate limit 배선 — taspa.rate-limit.enabled=true 일 때만 존재한다(기본 off).
 * 필터는 스프링 시큐리티(DEFAULT_FILTER_ORDER=-100)보다 앞에 등록한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "taspa.rate-limit", name = ["enabled"], havingValue = "true")
class RateLimitConfig(
    private val properties: RateLimitProperties,
    private val messageSource: MessageSource,
    private val localeResolver: LocaleResolver,
) {
    private val rateLimiter =
        RateLimiter(
            capacity = properties.capacity,
            refillTokens = properties.refillTokens,
            refillPeriod = Duration.ofSeconds(properties.refillPeriodSeconds),
        )

    @Bean
    fun rateLimitFilterRegistration(): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(RateLimitFilter(rateLimiter, messageSource, localeResolver)).apply {
            // 스프링 시큐리티 필터체인(-100)보다 앞 → 인증/CSRF 처리 전에 차단.
            // prod 는 forward-headers-strategy=native → RemoteIpValve 가 서블릿 컨테이너 레벨(모든 서블릿
            // 필터보다 앞)에서 remoteAddr 를 재작성하므로, 이 필터가 IP 를 읽을 때는 이미 실 클라이언트 IP 다.
            order = SecurityProperties.DEFAULT_FILTER_ORDER - 1
        }

    /** 가득 찬(미사용) 버킷 정리 — 인메모리 누수 방지. */
    @Scheduled(fixedDelayString = "\${taspa.rate-limit.eviction-delay-ms:600000}")
    fun evictStaleBuckets() = rateLimiter.evictFull()
}
