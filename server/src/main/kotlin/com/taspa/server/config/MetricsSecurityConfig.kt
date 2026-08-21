package com.taspa.server.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.SecurityFilterChain
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * `/actuator/prometheus` 전용 스크레이프 체인 — **taspa.metrics.scrape-password 가 설정된 경우에만 등록**된다.
 *
 * 이유: 기본 체인은 이 엔드포인트를 hasRole("ADMIN") 으로 막는데 앱에 httpBasic 이 없어 인증 수단이 폼 로그인
 * 세션뿐이다. Prometheus/Datadog 에이전트는 세션 로그인을 할 수 없으므로 메트릭을 수집할 방법이 없다
 * (= 운영 중 상태를 볼 수단이 없음). 전용 Basic 자격증명으로 그 경로를 연다.
 *
 * 설계 선택:
 *  - **STATELESS**: 스크레이프는 15초 간격으로 반복되므로 세션을 만들면 세션 테이블이 폭증한다. 세션 미생성.
 *  - **전용 AuthenticationManager**: 앱 사용자 저장소(LoginUserDetailsService)와 완전히 분리해, 이 자격증명이
 *    로그인·관리 콘솔 등 다른 어떤 표면에도 통하지 않게 한다. 부여 권한도 ROLE_METRICS 하나뿐.
 *  - 비교는 MessageDigest.isEqual 로 수행(단순 == 의 조기 종료 타이밍 노출 회피).
 *  - @Order(-2): 기존 체인 번호(-1 merchant·0 SCIM·1 AS·2 orgApi·3 default)를 건드리지 않고 앞에 삽입한다.
 *    securityMatcher 가 단일 경로라 다른 체인과 겹치지 않는다(disjoint — 무손상 삽입).
 *  - 이 체인이 등록되면 해당 경로는 스크레이프 자격증명 전용이 된다(관리자 브라우저 열람 대신 Grafana 등
 *    대시보드를 쓴다). 미설정 배포는 이 체인 자체가 없어 기존 ADMIN 규칙이 그대로 유지된다.
 */
@Configuration
@ConditionalOnProperty(prefix = "taspa.metrics", name = ["scrape-password"])
class MetricsSecurityConfig {
    @Bean
    @Order(-2)
    fun metricsScrapeSecurityFilterChain(
        http: HttpSecurity,
        properties: MetricsProperties,
    ): SecurityFilterChain {
        http
            .securityMatcher("/actuator/prometheus")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().hasRole("METRICS") }
            .httpBasic { }
            .authenticationManager(scrapeAuthenticationManager(properties))
        return http.build()
    }

    /** 스크레이프 자격증명만 아는 격리된 인증 매니저 — 앱 사용자 계정과 교차하지 않는다. */
    private fun scrapeAuthenticationManager(properties: MetricsProperties): AuthenticationManager =
        AuthenticationManager { authentication ->
            val username = authentication.name.orEmpty()
            val password = authentication.credentials?.toString().orEmpty()
            val expectedPassword = properties.scrapePassword
            // 설정이 비어 있으면(조건부 등록을 우회한 경우) 어떤 자격증명도 받아들이지 않는다.
            if (expectedPassword.isBlank() ||
                !constantTimeEquals(username, properties.scrapeUsername) ||
                !constantTimeEquals(password, expectedPassword)
            ) {
                throw BadCredentialsException("invalid metrics scrape credentials")
            }
            UsernamePasswordAuthenticationToken(
                username,
                null,
                listOf(SimpleGrantedAuthority("ROLE_METRICS")),
            )
        }

    private fun constantTimeEquals(
        actual: String,
        expected: String,
    ): Boolean =
        MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
}
