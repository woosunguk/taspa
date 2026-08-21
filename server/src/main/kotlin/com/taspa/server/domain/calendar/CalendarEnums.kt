package com.taspa.server.domain.calendar

/** 피드 유형 — 이벤트 category 파생 기본값. */
enum class CalendarFeedType {
    HOLIDAY,
    WORK,
    EVENT,
}

/** 이벤트 수집 경로. UPLOAD = .ics 본문 업로드, FEED = source_url 구독 동기화. */
enum class CalendarEventSource {
    UPLOAD,
    FEED,
}

/** 피드 동기화 결과. */
enum class CalendarSyncStatus {
    OK,
    ERROR,
}
