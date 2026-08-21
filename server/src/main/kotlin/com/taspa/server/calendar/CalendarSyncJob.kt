package com.taspa.server.calendar

import com.taspa.server.domain.calendar.CalendarFeedRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 구독 캘린더 피드 주기 동기화(Phase 0-E) — 기본 일 1회. enabled 이고 source_url 이 있는 피드만 대상.
 * 한 피드의 실패(네트워크·SSRF 거부·파싱)는 [CalendarService.syncSubscription] 안에서 흡수되므로
 * 다른 피드 동기화를 막지 않는다.
 */
@Component
class CalendarSyncJob(
    private val feedRepository: CalendarFeedRepository,
    private val calendarService: CalendarService,
) {
    private val log = LoggerFactory.getLogger(CalendarSyncJob::class.java)

    @Scheduled(cron = "\${taspa.calendar.sync-cron:0 15 3 * * *}")
    fun syncAll() {
        val feeds = feedRepository.findByEnabledTrueAndSourceUrlIsNotNull()
        if (feeds.isEmpty()) return
        var ok = 0
        var failed = 0
        feeds.forEach { feed ->
            val result = calendarService.syncSubscription(feed)
            if (result.status == "OK") ok++ else failed++
        }
        log.info("calendar sync job: feeds={}, ok={}, failed={}", feeds.size, ok, failed)
    }
}
