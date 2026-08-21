package com.taspa.server.calendar

import com.taspa.server.calendar.dto.CalendarEventPage
import com.taspa.server.calendar.dto.CalendarEventView
import com.taspa.server.calendar.dto.FeedCreateRequest
import com.taspa.server.calendar.dto.FeedView
import com.taspa.server.calendar.dto.SyncResultView
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.calendar.CalendarEventRepository
import com.taspa.server.domain.calendar.CalendarEventSource
import com.taspa.server.domain.calendar.CalendarFeed
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.calendar.CalendarFeedType
import com.taspa.server.domain.calendar.CalendarSyncStatus
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 캘린더 피드/이벤트 오케스트레이션(Phase 0-E). .ics 파싱→업서트, 구독 동기화(SSRF 가드), org 스코프 조회.
 * 모든 org-스코프 메서드는 orgId 를 강제해 다른 조직 데이터에 접근하지 못하게 한다(org 격리).
 *
 * ★트랜잭션 경계: 느린 외부 fetch·파싱은 트랜잭션 **밖**에서 수행하고, DB 업서트/상태갱신만 [CalendarEventWriter]
 * 의 짧은 독립 트랜잭션으로 위임한다 — (1) fetch 동안 커넥션을 점유하지 않고(풀 고갈 방지), (2) 업서트 경합 시
 * 깨끗한 롤백 + 별도 트랜잭션 ERROR 기록으로 false OK·rollback-only 오염을 없앤다.
 */
@Service
class CalendarService(
    private val feedRepository: CalendarFeedRepository,
    private val eventRepository: CalendarEventRepository,
    private val organizationRepository: OrganizationRepository,
    private val writer: CalendarEventWriter,
    private val parser: IcalendarParser,
    private val fetcher: IcsSubscriptionFetcher,
    private val urlSecurity: IcsUrlSecurity,
    private val properties: CalendarProperties,
) {
    private val log = LoggerFactory.getLogger(CalendarService::class.java)

    // ---- 피드 ----

    @Transactional(readOnly = true)
    fun listFeeds(orgId: UUID): List<FeedView> {
        requireOrg(orgId)
        return feedRepository.findByOrgId(orgId).map { FeedView.from(it) }
    }

    @Transactional
    fun createFeed(
        orgId: UUID,
        request: FeedCreateRequest,
    ): FeedView {
        requireOrg(orgId)
        val name = request.name.trim()
        if (name.isEmpty()) throw AuthException(ErrorCode.VALIDATION_ERROR, "피드 이름을 입력하세요")
        val type = parseType(request.type)
        val sourceUrl = request.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        // 구독형이면 등록 시점에 ★SSRF 검증한다(사설/메타데이터/http 거부). 실패 시 저장 자체를 막는다.
        if (sourceUrl != null) {
            urlSecurity.validate(sourceUrl)
        }
        val feed =
            feedRepository.save(
                CalendarFeed(orgId = orgId, name = name, type = type.name, sourceUrl = sourceUrl),
            )
        return FeedView.from(feed)
    }

    /** 피드 활성 토글(관리 콘솔). 비활성 피드는 스케줄 동기화 대상에서 빠진다(findByEnabledTrueAndSourceUrlIsNotNull). */
    @Transactional
    fun setFeedEnabled(
        orgId: UUID,
        feedId: UUID,
        enabled: Boolean,
    ): FeedView {
        val feed = requireFeed(orgId, feedId)
        feed.enabled = enabled
        return FeedView.from(feedRepository.save(feed))
    }

    /** 피드 삭제(관리 콘솔). 소속 이벤트는 FK(ON DELETE CASCADE)로 함께 삭제된다. org 격리 — 타 org 피드는 못 지운다. */
    @Transactional
    fun deleteFeed(
        orgId: UUID,
        feedId: UUID,
    ) {
        val feed = requireFeed(orgId, feedId)
        feedRepository.delete(feed)
    }

    /**
     * 피드 단위 최근 이벤트 미리보기(관리 콘솔, 페이징). starts_at 내림차순. org 격리 — feed 소유를 orgId 로 검증한다.
     * SUSPENDED 조직도 관리자는 점검할 수 있어야 하므로 조회 서비스(listEvents)와 달리 active 를 요구하지 않는다.
     */
    @Transactional(readOnly = true)
    fun listFeedEvents(
        orgId: UUID,
        feedId: UUID,
        page: Int,
        size: Int?,
    ): CalendarEventPage {
        requireFeed(orgId, feedId)
        val pageIndex = page.coerceAtLeast(0)
        val pageSize = (size ?: properties.defaultPageSize).coerceIn(1, properties.maxPageSize)
        val pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "startsAt"))
        val result = eventRepository.findByFeedId(feedId, pageable)
        return CalendarEventPage(
            items = result.content.map { CalendarEventView.from(it) },
            page = pageIndex,
            size = pageSize,
            total = result.totalElements,
            hasNext = result.hasNext(),
        )
    }

    // ---- 수집: 업로드 ----

    /**
     * .ics 본문 업로드→업서트(feed 단위 대체 동기화). 파싱은 트랜잭션 밖, 업서트만 writer 의 짧은 트랜잭션.
     * 경합/제약 위반 시 예외가 전파돼 관리자에게 실패로 보고된다(거짓 성공 없음).
     */
    fun importUpload(
        orgId: UUID,
        feedId: UUID,
        icsText: String,
    ): SyncResultView {
        val feed = requireFeed(orgId, feedId)
        val feedId0 = feed.id!!
        val parsed = parser.parse(icsText, feed.typeEnum().name)
        val imported = writer.upsertFeedEvents(feedId0, feed.orgId, CalendarEventSource.UPLOAD, parsed)
        return SyncResultView(feedId0, CalendarSyncStatus.OK.name, imported)
    }

    // ---- 수집: 구독 동기화 ----

    /** 관리자 수동 동기화(org 스코프). 피드가 구독형이 아니면 거부. fetch 는 트랜잭션 밖에서 수행한다. */
    fun syncFeed(
        orgId: UUID,
        feedId: UUID,
    ): SyncResultView {
        val feed = requireFeed(orgId, feedId)
        if (!feed.isSubscription()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "구독형 피드가 아닙니다(source_url 없음)")
        }
        return doSync(feed.id!!, feed.orgId, feed.sourceUrl!!, feed.typeEnum().name)
    }

    /**
     * 구독 피드 1건 동기화(스케줄 잡 진입점) — fetch(SSRF 가드)→파싱→업서트. 실패해도 예외를 던지지 않고
     * 상태만 ERROR 로 기록한다(스케줄 잡이 한 피드 실패로 멈추지 않게). 반환: 결과 요약.
     */
    fun syncSubscription(feed: CalendarFeed): SyncResultView {
        val url = feed.sourceUrl
        if (url.isNullOrBlank()) {
            return SyncResultView(feed.id!!, CalendarSyncStatus.ERROR.name, 0)
        }
        return doSync(feed.id!!, feed.orgId, url, feed.typeEnum().name)
    }

    /**
     * fetch(트랜잭션 밖)→파싱(트랜잭션 밖)→업서트(writer 의 독립 트랜잭션). 어느 단계 실패든 예외를 흡수하고
     * ERROR 상태를 별도 트랜잭션으로 기록한다. 업서트 트랜잭션이 자기완결적이라 상위 흐름을 오염시키지 않는다.
     */
    private fun doSync(
        feedId: UUID,
        orgId: UUID,
        sourceUrl: String,
        defaultCategory: String,
    ): SyncResultView {
        val icsText =
            try {
                fetcher.fetch(sourceUrl)
            } catch (ex: Exception) {
                log.warn("캘린더 피드 fetch 실패 feedId={} org={}", feedId, orgId, ex)
                writer.markStatus(feedId, CalendarSyncStatus.ERROR)
                return SyncResultView(feedId, CalendarSyncStatus.ERROR.name, 0)
            }
        val parsed = parser.parse(icsText, defaultCategory)
        return try {
            val imported = writer.upsertFeedEvents(feedId, orgId, CalendarEventSource.FEED, parsed)
            SyncResultView(feedId, CalendarSyncStatus.OK.name, imported)
        } catch (ex: Exception) {
            log.warn("캘린더 피드 업서트 실패 feedId={} org={}", feedId, orgId, ex)
            writer.markStatus(feedId, CalendarSyncStatus.ERROR)
            SyncResultView(feedId, CalendarSyncStatus.ERROR.name, 0)
        }
    }

    // ---- 조회(org 스코프) ----

    /**
     * org 스코프 이벤트 조회([from, to)). from/to 미지정 시 기본 [now, now+30d]. 윈도우 폭은
     * expansionWindowDays 로 상한(과도한 스캔 방지)하고, 행 수는 page/size 로 페이징 상한한다.
     * org 격리 — orgId 로만 필터한다. 정지(SUSPENDED) 조직은 조회를 차단한다.
     */
    @Transactional(readOnly = true)
    fun listEvents(
        orgId: UUID,
        from: Instant?,
        to: Instant?,
        page: Int,
        size: Int?,
    ): CalendarEventPage {
        requireActiveOrg(orgId)
        val start = from ?: Instant.now()
        val endRaw = to ?: start.plus(Duration.ofDays(30))
        if (!endRaw.isAfter(start)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "to 는 from 보다 뒤여야 합니다")
        }
        val maxSpan = Duration.ofDays(properties.expansionWindowDays)
        val end = if (Duration.between(start, endRaw) > maxSpan) start.plus(maxSpan) else endRaw
        val pageIndex = page.coerceAtLeast(0)
        val pageSize = (size ?: properties.defaultPageSize).coerceIn(1, properties.maxPageSize)
        val pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.ASC, "startsAt"))
        val result = eventRepository.findWindow(orgId, start, end, pageable)
        return CalendarEventPage(
            items = result.content.map { CalendarEventView.from(it) },
            page = pageIndex,
            size = pageSize,
            total = result.totalElements,
            hasNext = result.hasNext(),
        )
    }

    // ---- 내부 ----

    private fun requireOrg(orgId: UUID) {
        if (!organizationRepository.existsById(orgId)) {
            throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        }
    }

    /** 조회 인가용 — 조직이 존재하고 ACTIVE 여야 한다. SUSPENDED 조직의 캘린더는 어떤 호출자도 읽지 못한다. */
    private fun requireActiveOrg(orgId: UUID) {
        val org =
            organizationRepository.findById(orgId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            throw AuthException(ErrorCode.FORBIDDEN, "정지된 조직입니다")
        }
    }

    /** 피드를 org 스코프로 조회 — 다른 조직 피드 id 를 줘도 못 찾는다(org 격리 핵심). */
    private fun requireFeed(
        orgId: UUID,
        feedId: UUID,
    ): CalendarFeed =
        feedRepository.findByIdAndOrgId(feedId, orgId)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "피드를 찾을 수 없습니다")

    private fun parseType(value: String): CalendarFeedType =
        CalendarFeedType.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "type 은 HOLIDAY·WORK·EVENT 중 하나여야 합니다")
}
