-- Phase 0-E: iCalendar 연동(RFC 5545). 조직 캘린더(공휴일·근무·행사)를 표준 .ics 로 흡수한다.
--   calendar_feeds: 조직별 피드. source_url 있으면 구독형(주기 동기화), NULL 이면 업로드형.
--   calendar_events: VEVENT 를 정규화한 행. 반복(RRULE)은 조회 윈도우 내로만 확장해 개별 행으로 저장.
--     upsert 키 = (feed_id, uid, starts_at) — 반복 occurrence 는 uid 동일·starts_at 상이로 구분된다.
CREATE TABLE calendar_feeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    type VARCHAR(16) NOT NULL,          -- HOLIDAY | WORK | EVENT
    source_url VARCHAR(1024),           -- 구독형(NULL 이면 업로드형)
    enabled BOOLEAN NOT NULL DEFAULT true,
    last_synced_at TIMESTAMP,
    last_sync_status VARCHAR(16),       -- OK | ERROR
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    feed_id UUID REFERENCES calendar_feeds(id) ON DELETE CASCADE,
    uid VARCHAR(512) NOT NULL,          -- VEVENT UID (feed 내 유니크 upsert 키)
    summary VARCHAR(500),
    category VARCHAR(64),               -- CATEGORIES 또는 feed.type 파생
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP,
    all_day BOOLEAN NOT NULL DEFAULT false,
    source VARCHAR(16) NOT NULL,        -- UPLOAD | FEED
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (feed_id, uid, starts_at)
);

CREATE INDEX idx_calendar_events_org_time ON calendar_events(org_id, starts_at);
CREATE INDEX idx_calendar_feeds_org ON calendar_feeds(org_id);
