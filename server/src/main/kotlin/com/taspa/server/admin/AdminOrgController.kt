package com.taspa.server.admin

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.sso.SsoConnectionRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import com.taspa.server.org.OrganizationService
import com.taspa.server.org.dto.MembershipRequest
import com.taspa.server.org.dto.MembershipRoleRequest
import com.taspa.server.org.dto.MembershipView
import com.taspa.server.org.dto.OrgCreateRequest
import com.taspa.server.org.dto.OrgUpdateRequest
import com.taspa.server.org.dto.OrgView
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 테넌시 관리 API(Phase 0-A) — ADMIN 전용(SecurityConfig 의 /api/admin/ 경로 hasRole ADMIN),
 * 변경 작업은 step-up(@RequireRecentAuth) + CSRF 헤더 필수. 조직 생성/상태, 멤버십 관리,
 * sso_connection ↔ org 연결을 제공한다.
 */
@RestController
@RequestMapping("/api/admin/orgs")
class AdminOrgController(
    private val organizationService: OrganizationService,
    private val ssoConnectionRepository: SsoConnectionRepository,
    private val auditEventService: AuditEventService,
    private val userRepository: UserRepository,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_ORGS,
        resource = "trn:taspa:platform::organization/*",
    )
    @GetMapping
    fun list(): ResponseEntity<List<OrgView>> = ResponseEntity.ok(organizationService.list())

    @PlatformAction(
        action = IamActions.PLATFORM_READ_ORG,
        resource = "trn:taspa:platform:{id}:organization/{id}",
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<OrgView> = ResponseEntity.ok(organizationService.get(id))

    @PlatformAction(
        action = IamActions.PLATFORM_CREATE_ORG,
        resource = "trn:taspa:platform::organization/*",
    )
    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @RequestBody request: OrgCreateRequest,
    ): ResponseEntity<OrgView> {
        val view = organizationService.create(request)
        auditEventService.record(
            "ADMIN_ORG_CREATED",
            actorId(authentication),
            view.id,
            mapOf("orgId" to view.id.toString(), "slug" to view.slug),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    // status(ACTIVE↔SUSPENDED)까지 바꾸는 경로라 org 콘솔의 ORG_UPDATE_PROFILE 을 재사용하지 않는다.
    @PlatformAction(
        action = IamActions.PLATFORM_ADMINISTER_ORG,
        resource = "trn:taspa:platform:{id}:organization/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/{id}")
    fun update(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: OrgUpdateRequest,
    ): ResponseEntity<OrgView> {
        val view = organizationService.update(id, request)
        auditEventService.record(
            "ADMIN_ORG_UPDATED",
            actorId(authentication),
            view.id,
            // timezone 은 소비 집계 date 버킷 앵커(V18)라 변경 추적이 중요하다 — name 과 함께 기록해
            // 언제 무엇으로 바뀌었는지 감사에서 재구성 가능하게 한다.
            mapOf(
                "orgId" to view.id.toString(),
                "status" to view.status,
                "timezone" to view.timezone,
                "name" to view.name,
            ),
        )
        return ResponseEntity.ok(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_LIST_ORG_MEMBERS,
        resource = "trn:taspa:platform:{id}:member/*",
    )
    @GetMapping("/{id}/members")
    fun listMembers(
        @PathVariable id: UUID,
    ): ResponseEntity<List<MembershipView>> = ResponseEntity.ok(organizationService.listMembers(id))

    @PlatformAction(
        action = IamActions.PLATFORM_ADD_ORG_MEMBER,
        resource = "trn:taspa:platform:{id}:member/*",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/members")
    fun upsertMember(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: MembershipRequest,
    ): ResponseEntity<MembershipView> {
        val actor = actorId(authentication)
        val view = organizationService.upsertMember(id, request, actor)
        auditEventService.record(
            "ADMIN_ORG_MEMBER_UPSERTED",
            actor,
            id,
            mapOf("orgId" to id.toString(), "userId" to view.userId.toString(), "role" to view.role),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_CHANGE_ORG_MEMBER_ROLE,
        resource = "trn:taspa:platform:{id}:member/{userId}",
    )
    @RequireRecentAuth
    @PutMapping("/{id}/members/{userId}/role")
    fun changeRole(
        authentication: Authentication,
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MembershipRoleRequest,
    ): ResponseEntity<MembershipView> {
        val actor = actorId(authentication)
        val view = organizationService.changeRole(id, userId, request.role, actor)
        auditEventService.record(
            "ADMIN_ORG_MEMBER_ROLE_CHANGED",
            actor,
            id,
            mapOf("orgId" to id.toString(), "userId" to userId.toString(), "role" to view.role),
        )
        return ResponseEntity.ok(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_REMOVE_ORG_MEMBER,
        resource = "trn:taspa:platform:{id}:member/{userId}",
    )
    @RequireRecentAuth
    @DeleteMapping("/{id}/members/{userId}")
    fun removeMember(
        authentication: Authentication,
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Void> {
        val actor = actorId(authentication)
        organizationService.removeMember(id, userId, actor)
        auditEventService.record(
            "ADMIN_ORG_MEMBER_REMOVED",
            actor,
            id,
            mapOf("orgId" to id.toString(), "userId" to userId.toString()),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * sso_connection ↔ org 연결(JIT 활성화). org_id 를 세팅해야 조직 IdP 로그인 성공 시 JIT 멤버십이
     * 만들어진다. 존재하는 조직·커넥션만 허용한다.
     */
    @PlatformAction(
        action = IamActions.PLATFORM_LINK_ORG_SSO,
        resource = "trn:taspa:platform:{id}:organization/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/{id}/sso/{connectionId}")
    @Transactional
    fun linkSsoConnection(
        authentication: Authentication,
        @PathVariable id: UUID,
        @PathVariable connectionId: UUID,
    ): ResponseEntity<Void> {
        organizationService.get(id) // 조직 존재 검증(없으면 404)
        val connection =
            ssoConnectionRepository.findById(connectionId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "SSO 커넥션을 찾을 수 없습니다")
        connection.orgId = id
        ssoConnectionRepository.save(connection)
        auditEventService.record(
            "ADMIN_ORG_SSO_LINKED",
            actorId(authentication),
            id,
            mapOf("orgId" to id.toString(), "registrationId" to connection.registrationId),
        )
        return ResponseEntity.noContent().build()
    }

    private fun actorId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
