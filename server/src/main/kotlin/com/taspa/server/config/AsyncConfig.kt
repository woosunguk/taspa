package com.taspa.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

/**
 * 로그인 경로 알림 메일(@Async)을 요청 스레드에서 분리하기 위한 실행기.
 *
 * new-login/high-risk 알림은 로그인 성공 경로에서 인라인 호출되므로, SMTP 지연이 로그인 응답
 * 지연으로 직결된다. 경계 있는 스레드풀로 디커플해 로그인은 즉시 반환한다(SMTP 타임아웃은
 * application-prod.yml 에서 별도로 상한을 건다 — 이중 방어).
 *
 * test 프로파일은 SyncTaskExecutor 로 동기 실행한다 — 기존 메일 검증 통합 테스트
 * (LoginNotificationIntegrationTest / RiskBasedAuthIntegrationTest)가 발송을 동기적으로 관찰하기 때문.
 */
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean("mailTaskExecutor")
    @Profile("!test")
    fun mailTaskExecutor(): TaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 5
            queueCapacity = 50
            setThreadNamePrefix("mail-async-")
            // 큐/풀 포화 시 호출 스레드에서 실행(무한 적체 방지) — 최악에도 SMTP 타임아웃 상한 내로 끝난다.
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }

    @Bean("mailTaskExecutor")
    @Profile("test")
    fun mailTaskExecutorSync(): TaskExecutor = SyncTaskExecutor()
}
