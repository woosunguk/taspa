package com.taspa.server.consumption

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.consumption.dto.ConsumptionAggregateRow
import com.taspa.server.consumption.dto.ConsumptionEventWriteRequest
import com.taspa.server.consumption.dto.ConsumptionIngestResult
import com.taspa.server.domain.consumption.ConsumptionEvent
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.consumption.ConsumptionEventStatus
import com.taspa.server.domain.consumption.ConsumptionSource
import com.taspa.server.domain.consumption.MealWindow
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 소비 이벤트 수집/집계 오케스트레이션(Phase 0ب-C). 제네릭 append-only 로그 — 생산자(결제·POS·수동·임포트)는
 * (source, external_id) 멱등키로 적재하고, 예측 소비자는 집계로만 읽는다.
 *
 * 테넌시(캘린더와 동일 규칙): 모든 org-스코프 메서드는 orgId 를 강제한다. 적재는 경로 org 로만 귀속되며
 * 같은 멱등키가 다른 org 에 존재하면 거부한다(교차 테넌시 하이재킹 차단). 집계 조회는 SUSPENDED 조직을 막는다.
 * 참조 식별자도 경로 org 안에 갇힌다: site_id 는 여기서 소속을 검증하고, merchant_id 는 공개 API 로는 아예
 * 받지 않는다(컨트롤러 400 — 가맹 그레인 집계가 org 를 넘어 합산하므로 본문 값을 믿을 수 없다).
 * 집계 노출: date × meal_window[× menu] 카운트만 반환하고 개별 이벤트·user_sub 는 절대 노출하지 않는다.
 */
@Service
class ConsumptionEventService(
    private val eventRepository: ConsumptionEventRepository,
    private val organizationRepository: OrganizationRepository,
    private val siteRepository: SiteRepository,
    private val properties: ConsumptionProperties,
) {
    /**
     * 멱등 적재(단건/배치). (org_id, source, external_id) 로 upsert 한다: 없으면 insert, 있으면 갱신(no-op 포함).
     * 같은 배치 내 중복 멱등키는 한 엔티티로 접어 처리해 단일 트랜잭션 flush 시 UNIQUE 위반을 피한다.
     * 경로 org 로만 귀속한다. 멱등키가 org 범위라 다른 조직이 같은 external_id 를 써도 서로 간섭하지 않는다.
     *
     * 동시성: 같은 (org, source, external_id) 를 동시에 재전송하면 둘 다 null 을 읽고 insert 를 시도할 수 있다.
     *   한쪽은 UNIQUE 위반(DataIntegrityViolationException)이 되지만 GlobalExceptionHandler 가 이를 409(멱등
     *   충돌·재시도 안전)로 매핑한다 — 500 이 아니다. 생산자의 멱등 재시도는 이후 기존 행을 읽어 no-op 갱신으로 수렴한다.
     *
     * 갱신 규약(full-replace): 기존 행이 있으면 요청 본문으로 **전체 필드를 덮어쓴다**. 선택 필드(userSub·
     *   merchantId·menuRef)를 생략한 재전송은 해당 필드를 비운다 — 생산자는 같은 멱등키로 이벤트의 완전한
     *   현재 상태를 재전송한다는 계약이며, 부분 병합으로 저장 행의 의미가 모호해지는 것을 막는다.
     */
    @Transactional
    fun ingest(
        orgId: UUID,
        requests: List<ConsumptionEventWriteRequest>,
    ): ConsumptionIngestResult {
        requireOrg(orgId)
        if (requests.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "적재할 이벤트가 없습니다")
        }
        // 배치 크기 상한(taspa.consumption.max-batch-size) — 무제한 배치로 인한 자원고갈 방지. 운영 조정형.
        if (requests.size > properties.maxBatchSize) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "한 번에 ${properties.maxBatchSize}건까지 적재할 수 있습니다",
            )
        }
        var inserted = 0
        var updated = 0
        // 같은 배치 내에서 이미 만지거나 만든 엔티티를 (source, externalId) 로 추적한다.
        val touched = HashMap<Pair<String, String>, ConsumptionEvent>()
        // site 소속 검증 결과 캐시(배치 안에서 같은 site 를 반복 조회하지 않기 위해).
        val verifiedSites = HashMap<UUID, Boolean>()
        // 멱등 조회 preload(N+1 제거) — 요청의 멱등키를 source 별로 묶어 IN 조회로 기존 행을 한 번에 읽는다.
        // 정규화 불가(잘못된 source·빈/과장 externalId) 키는 preload 에서 건너뛴다 — 어차피 아래 루프의
        // 검증이 400 으로 배치 전체를 거부한다(@Transactional 롤백이라 관측 결과 동일).
        val preloaded = preloadExisting(orgId, requests)
        for (req in requests) {
            // 잘못된 enum(source/meal_window/status)은 raw IllegalArgumentException(→500) 대신 400 으로 조기 거부.
            val source = validateEnum { ConsumptionSource.parse(req.source).name.lowercase() }
            val externalId = req.externalId.trim()
            if (externalId.isEmpty()) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "external_id 를 입력하세요")
            }
            // 컬럼 상한(VARCHAR(128)) 초과는 flush 시점의 DataIntegrityViolation(→모호한 409)이 아니라 여기서
            // 명시적 400 으로 거부한다(OrgInvitationService.normalizeDepartment 와 동일 패턴 — 멱등 충돌 오인 방지).
            if (externalId.length > MAX_EXTERNAL_ID_LENGTH) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "external_id 는 ${MAX_EXTERNAL_ID_LENGTH}자를 넘을 수 없습니다")
            }
            val mealWindow = validateEnum { MealWindow.parse(req.mealWindow).name }
            val status =
                req.status?.let { s -> validateEnum { ConsumptionEventStatus.parse(s).name } }
                    ?: ConsumptionEventStatus.CONFIRMED.name
            val menuRef = req.menuRef?.trim()?.takeIf { it.isNotEmpty() }
            if (menuRef != null && menuRef.length > MAX_MENU_REF_LENGTH) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "menu_ref 는 ${MAX_MENU_REF_LENGTH}자를 넘을 수 없습니다")
            }
            if (req.quantity < 1) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "quantity 는 1 이상이어야 합니다")
            }
            val occurredAt =
                req.occurredAt
                    ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "occurred_at 을 입력하세요")
            // site 는 org 스코프다. 경로 org 에 없는 사업장 UUID 는 거부한다 — 검증하지 않으면 이 org 의
            // 이벤트에 타 조직 site 식별자가 스며들어 사업장 그레인 예측이 조용히 어긋난다(사업장별
            // 실측이 존재하지 않는 버킷으로 새거나, 남의 사업장 UUID 를 알아내는 존재 확인 신호가 된다).
            // 캐시: 같은 배치가 같은 site 를 반복해도 조회는 site 당 1회다.
            req.siteId?.let { siteId ->
                if (!verifiedSites.getOrPut(siteId) { siteRepository.findByIdAndOrgId(siteId, orgId) != null }) {
                    throw AuthException(ErrorCode.VALIDATION_ERROR, "사업장을 찾을 수 없습니다")
                }
            }

            val key = source to externalId
            // 멱등 조회는 org 스코프다 — 다른 조직의 동일 멱등키는 보이지 않으므로 하이재킹·오라클이 원천 차단된다.
            // touched 우선: 같은 배치의 선행 저장(방금 insert/갱신한 엔티티)이 preload 스냅샷보다 최신이다.
            val existing = touched[key] ?: preloaded[key]
            if (existing != null) {
                existing.userSub = req.userSub
                existing.merchantId = req.merchantId
                existing.siteId = req.siteId
                existing.mealWindow = mealWindow
                existing.menuRef = menuRef
                existing.quantity = req.quantity
                existing.status = status
                existing.occurredAt = occurredAt
                val saved = eventRepository.save(existing)
                if (touched.put(key, saved) == null) updated++ // 저장소에 있던 기존 건만 갱신으로 집계
            } else {
                val saved =
                    eventRepository.save(
                        ConsumptionEvent(
                            source = source,
                            externalId = externalId,
                            orgId = orgId,
                            userSub = req.userSub,
                            merchantId = req.merchantId,
                            siteId = req.siteId,
                            mealWindow = mealWindow,
                            menuRef = menuRef,
                            quantity = req.quantity,
                            status = status,
                            occurredAt = occurredAt,
                        ),
                    )
                touched[key] = saved
                inserted++
            }
        }
        return ConsumptionIngestResult(received = requests.size, inserted = inserted, updated = updated)
    }

    /**
     * 집계 조회 — date × meal_window[× menu] 카운트만 반환한다(개별 이벤트·user_sub 미노출). VOIDED 제외.
     * SUSPENDED 조직은 차단한다(캘린더와 동일 테넌시 규칙). date 버킷은 org-로컬 타임존으로 앵커링한다.
     *
     * 자원 상한(캘린더 listEvents 와 동일 사상): 결과 행 수를 [MAX_AGGREGATE_GROUPS] 로 상한해 고카디널리티
     *   menu_ref 로 인한 집계 폭발을 막는다. 조회 창 폭(from/to span) 상한은 컨트롤러가 적용한다.
     */
    @Transactional(readOnly = true)
    fun aggregate(
        orgId: UUID,
        from: Instant,
        to: Instant,
        includeMenu: Boolean,
    ): List<ConsumptionAggregateRow> {
        val org = requireActiveOrg(orgId)
        if (!to.isAfter(from)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "to 는 from 보다 뒤여야 합니다")
        }
        val tz = org.timezone
        return if (includeMenu) {
            eventRepository.aggregateByDateWindowMenu(orgId, from, to, tz, MAX_AGGREGATE_GROUPS).map {
                ConsumptionAggregateRow(
                    date = toLocalDate(it[0]),
                    mealWindow = it[1] as String,
                    menuRef = (it[2] as String).takeIf { m -> m.isNotEmpty() },
                    count = toLong(it[3]),
                    quantity = toLong(it[4]),
                )
            }
        } else {
            eventRepository.aggregateByDateWindow(orgId, from, to, tz, MAX_AGGREGATE_GROUPS).map {
                ConsumptionAggregateRow(
                    date = toLocalDate(it[0]),
                    mealWindow = it[1] as String,
                    menuRef = null,
                    count = toLong(it[2]),
                    quantity = toLong(it[3]),
                )
            }
        }
    }

    /**
     * ingest 멱등 조회 preload — 요청들의 (source, externalId) 를 정규화해 source 별 IN 조회로 기존 행을
     * 한 번에 읽어 맵으로 돌려준다(이벤트당 SELECT N+1 제거). 정규화에 실패하는 키(잘못된 source,
     * 빈/상한 초과 externalId)는 건너뛴다 — 본 루프의 검증이 어차피 400 으로 거부한다.
     */
    private fun preloadExisting(
        orgId: UUID,
        requests: List<ConsumptionEventWriteRequest>,
    ): Map<Pair<String, String>, ConsumptionEvent> {
        val keysBySource =
            requests
                .mapNotNull { req ->
                    val source =
                        runCatching { ConsumptionSource.parse(req.source).name.lowercase() }.getOrNull()
                            ?: return@mapNotNull null
                    val externalId =
                        req.externalId
                            .trim()
                            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTERNAL_ID_LENGTH }
                            ?: return@mapNotNull null
                    source to externalId
                }.distinct()
                .groupBy({ it.first }, { it.second })
        val preloaded = HashMap<Pair<String, String>, ConsumptionEvent>()
        for ((source, externalIds) in keysBySource) {
            eventRepository
                .findByOrgIdAndSourceAndExternalIdIn(orgId, source, externalIds)
                .forEach { preloaded[source to it.externalId] = it }
        }
        return preloaded
    }

    private fun requireOrg(orgId: UUID) {
        if (!organizationRepository.existsById(orgId)) {
            throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        }
    }

    private fun requireActiveOrg(orgId: UUID): Organization {
        val org =
            organizationRepository.findById(orgId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            throw AuthException(ErrorCode.FORBIDDEN, "정지된 조직입니다")
        }
        return org
    }

    private fun toLocalDate(value: Any): LocalDate =
        when (value) {
            is Date -> value.toLocalDate()
            is LocalDate -> value
            else -> LocalDate.parse(value.toString())
        }

    private fun toLong(value: Any): Long = (value as Number).toLong()

    /**
     * enum 파싱 실패(IllegalArgumentException)를 400 VALIDATION_ERROR 로 정규화한다. parse 는 사용자
     * 친화 메시지를 담아 던지므로 그대로 노출한다 — catch-all(→500) 로 새는 것을 막는다.
     */
    private fun <T> validateEnum(parse: () -> T): T =
        try {
            parse()
        } catch (ex: IllegalArgumentException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, ex.message ?: "잘못된 값입니다")
        }

    private companion object {
        /** 집계 결과 행(그룹) 수 상한 — 고카디널리티 menu_ref 로 인한 응답/메모리 폭증을 막는다(2차 방어). */
        const val MAX_AGGREGATE_GROUPS = 5000

        /** consumption_events.external_id 컬럼 상한(VARCHAR(128), V17). 초과 입력은 400 으로 조기 거부. */
        const val MAX_EXTERNAL_ID_LENGTH = 128

        /** consumption_events.menu_ref 컬럼 상한(VARCHAR(128), V17). 초과 입력은 400 으로 조기 거부. */
        const val MAX_MENU_REF_LENGTH = 128
    }
}
