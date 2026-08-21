package com.taspa.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 메트릭 스크레이핑 자격증명. @ConfigurationPropertiesScan 으로 자동 등록된다(TaspaServerApplication).
 *
 * `/actuator/prometheus` 는 기본적으로 hasRole("ADMIN") 이라 폼 로그인 세션이 필요한데, Prometheus 같은
 * 스크레이퍼는 세션 로그인을 할 수 없다(= 메트릭이 있어도 못 가져간다). scrape-password 를 설정하면
 * 전용 HTTP Basic 체인(MetricsSecurityConfig)이 활성화돼 스크레이퍼가 인증할 수 있다.
 * 미설정이면 이 체인은 아예 등록되지 않고 기존 ADMIN 규칙이 그대로 적용된다(무손상 기본값).
 */
@ConfigurationProperties(prefix = "taspa.metrics")
data class MetricsProperties(
    val scrapeUsername: String = "metrics",
    /** 빈 문자열이면 스크레이프 체인 비활성(기본). 운영에서는 환경변수로 주입한다. */
    val scrapePassword: String = "",
)
