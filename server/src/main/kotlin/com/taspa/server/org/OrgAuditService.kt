package com.taspa.server.org

import com.taspa.server.domain.audit.AuditEvent
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.org.dto.OrgAuditEventView
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 조직 스코프 활동로그 조회 — AdminAuditService 패턴을 org 격리로 좁힌 판. **org_id 정확 일치**로만
 * 조회하므로 타 org 이벤트·전역(org_id=null) 이벤트는 절대 반환하지 않는다(격리 불변식). 인가(플랫폼
 * ADMIN ∨ ORG_ADMIN, 베어러 거부)는 컨트롤러(OrgAuditController)가 담당한다.
 */
@Service
class OrgAuditService(
    private val auditEventRepository: AuditEventRepository,
    private val userRepository: UserRepository,
) {
    /** 해당 org 의 감사 이벤트를 최신순으로 limit/offset 페이징 조회한다. */
    fun listForOrg(
        orgId: UUID,
        limit: Int,
        offset: Int,
    ): List<OrgAuditEventView> {
        val pageable = OffsetPageRequest(offset.coerceAtLeast(0), limit.coerceIn(1, MAX_LIMIT))
        return toViews(auditEventRepository.findByOrgIdOrderByCreatedAtDesc(orgId, pageable))
    }

    private fun toViews(events: List<AuditEvent>): List<OrgAuditEventView> {
        // 행위자 일괄 해석(N+1 회피) — 탈퇴/미해석 userId 는 email=null.
        val usersById =
            userRepository
                .findAllById(events.mapNotNull { it.userId }.toSet())
                .associateBy { it.id!! }
        return events.map {
            val actor = it.userId?.let(usersById::get)
            // 플랫폼 운영자(role=ADMIN)가 행위자인 org 결속 이벤트(ADMIN_ORG_*)는 신원을 마스킹한다.
            // 이 행위자는 그 org 의 구성원이 아니라 내부 스태프이므로, 원문 이메일(및 userId)을 테넌트
            // ORG_ADMIN 에게 노출하면 안 된다 — 콘솔은 대신 역할 라벨만 표시한다.
            val platformActor = actor?.role == UserRole.ADMIN.name
            OrgAuditEventView(
                id = it.id!!,
                userId = if (platformActor) null else it.userId,
                email = if (platformActor) null else actor?.email,
                type = it.type,
                detail = it.detail,
                createdAt = it.createdAt,
                platformActor = platformActor,
            )
        }
    }

    companion object {
        /** 창 폭 상한 — 자원 고갈 방지(관리자 audit 과 동일한 규율). */
        private const val MAX_LIMIT = 100
    }

    /**
     * 임의 offset 페이징용 Pageable(AdminAuditService 와 동형). PageRequest 는 offset 이 항상 page*size 라
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
