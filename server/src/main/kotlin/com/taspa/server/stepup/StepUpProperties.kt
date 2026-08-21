package com.taspa.server.stepup

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "taspa.step-up")
data class StepUpProperties(
    /** auth_time 으로부터 이 시간이 지나면 민감 작업 전 재인증을 요구한다. Duration 타입("10m", "30s" 등). */
    val maxAge: Duration = Duration.ofMinutes(10),
)
