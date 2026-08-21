package com.taspa.server.admin

import com.taspa.server.admin.dto.AdminAuditEventView
import com.taspa.server.domain.audit.AuditEvent
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.user.UserRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AdminAuditService(
    private val auditEventRepository: AuditEventRepository,
    private val userRepository: UserRepository,
) {
    /**
     * 감사 로그 조회 — type 정확 일치 + email(→userId 해석) 필터, limit/offset 페이징.
     * 이메일이 주어졌는데 계정이 없으면 빈 목록(존재 여부는 관리자 화면상 자연스럽게 드러난다).
     */
    fun search(
        type: String?,
        email: String?,
        limit: Int,
        offset: Int,
    ): List<AdminAuditEventView> {
        val normalizedType = type?.trim()?.takeIf { it.isNotEmpty() }
        val userId =
            email?.trim()?.takeIf { it.isNotEmpty() }?.let {
                userRepository.findByEmail(it)?.id ?: return emptyList()
            }
        val pageable = OffsetPageRequest(offset.coerceAtLeast(0), limit.coerceIn(1, MAX_LIMIT))
        val events =
            when {
                normalizedType != null && userId != null ->
                    auditEventRepository.findByTypeAndUserIdOrderByCreatedAtDesc(normalizedType, userId, pageable)
                normalizedType != null -> auditEventRepository.findByTypeOrderByCreatedAtDesc(normalizedType, pageable)
                userId != null -> auditEventRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                else -> auditEventRepository.findAllByOrderByCreatedAtDesc(pageable)
            }
        return toViews(events)
    }

    /** 사용자 상세 화면용: 해당 사용자의 최근 감사 이벤트. */
    fun recentForUser(
        userId: UUID,
        limit: Int,
    ): List<AdminAuditEventView> = toViews(auditEventRepository.findByUserIdOrderByCreatedAtDesc(userId, OffsetPageRequest(0, limit)))

    private fun toViews(events: List<AuditEvent>): List<AdminAuditEventView> {
        val emailsById =
            userRepository
                .findAllById(events.mapNotNull { it.userId }.toSet())
                .associate { it.id!! to it.email }
        return events.map {
            AdminAuditEventView(
                id = it.id!!,
                type = it.type,
                userId = it.userId,
                email = it.userId?.let(emailsById::get),
                detail = it.detail,
                createdAt = it.createdAt,
            )
        }
    }

    companion object {
        private const val MAX_LIMIT = 200
    }

    /**
     * 임의 offset 페이징용 Pageable. PageRequest 는 offset 이 항상 page*size 라
     * limit 배수가 아닌 offset 을 표현할 수 없다. 정렬은 파생 쿼리 이름(OrderByCreatedAtDesc)이 맡는다.
     */
    private class OffsetPageRequest(
        private val offset: Int,
        private val limit: Int,
    ) : Pageable {
        override fun getPageNumber(): Int = offset / limit

        override fun getPageSize(): Int = limit

        override fun getOffset(): Long = offset.toLong()

        override fun getSort(): Sort = Sort.unsorted()

        override fun next(): Pageable = OffsetPageRequest(offset + limit, limit)

        override fun previousOrFirst(): Pageable = OffsetPageRequest(maxOf(0, offset - limit), limit)

        override fun first(): Pageable = OffsetPageRequest(0, limit)

        override fun withPage(pageNumber: Int): Pageable = OffsetPageRequest(pageNumber * limit, limit)

        override fun hasPrevious(): Boolean = offset > 0
    }
}
