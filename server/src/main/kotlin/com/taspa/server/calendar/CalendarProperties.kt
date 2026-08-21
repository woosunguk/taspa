package com.taspa.server.calendar

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 캘린더 연동 설정(Phase 0-E).
 * - expansionWindowDays: RRULE 반복 확장 상한(무한 반복 폭발 방지). 조회/저장 윈도우도 이 값을 상한으로 쓴다.
 * - maxOccurrencesPerEvent: 단일 VEVENT 가 만들 수 있는 occurrence 행 수 하드 상한(2차 방어).
 * - fetchTimeout / maxFeedSizeBytes / maxRedirects: 구독 URL fetch 의 타임아웃·크기상한·리다이렉트 상한(SSRF·DoS 방어).
 * - defaultPageSize / maxPageSize: 조회 API 페이징 기본·최대 페이지 크기(무제한 응답·메모리 폭증 방지).
 */
@ConfigurationProperties(prefix = "taspa.calendar")
data class CalendarProperties(
    val expansionWindowDays: Long = 400,
    val maxOccurrencesPerEvent: Int = 1000,
    val fetchTimeout: Duration = Duration.ofSeconds(5),
    val maxFeedSizeBytes: Long = 5L * 1024 * 1024,
    val maxRedirects: Int = 5,
    val syncCron: String = "0 15 3 * * *",
    val defaultPageSize: Int = 200,
    val maxPageSize: Int = 1000,
)
