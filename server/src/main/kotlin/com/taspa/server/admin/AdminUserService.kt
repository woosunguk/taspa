package com.taspa.server.admin

import com.taspa.server.admin.dto.AdminUserDetail
import com.taspa.server.admin.dto.AdminUserSummary
import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.federation.FederationService
import com.taspa.server.passkey.PasskeyService
import com.taspa.server.session.SessionManagementService
import org.springframework.data.domain.PageRequest
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AdminUserService(
    private val userRepository: UserRepository,
    private val sessionManagementService: SessionManagementService,
    private val trustedDeviceService: TrustedDeviceService,
    private val passkeyService: PasskeyService,
    private val federationService: FederationService,
    private val sessionRepository: FindByIndexNameSessionRepository<out Session>,
    private val adminAuditService: AdminAuditService,
    private val auditEventService: AuditEventService,
) {
    /** 이메일 부분일치 검색(최대 50). 검색어가 없으면 최근 가입 순 50명. */
    fun search(query: String?): List<AdminUserSummary> {
        val pageable = PageRequest.of(0, SEARCH_LIMIT)
        val users =
            query
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { userRepository.searchByEmailContains(escapeLike(it), pageable) }
                ?: userRepository.findAllByOrderByCreatedAtDesc(pageable)
        return users.map { AdminUserSummary.from(it) }
    }

    /**
     * LIKE 와일드카드 이스케이프(쿼리는 ESCAPE '!') — 검색어의 '_'(임의 1문자)·'%'(임의 문자열)가
     * 패턴으로 해석되면 부분일치 결과가 틀어진다(예: '_' 한 글자로 전 계정 매칭).
     */
    private fun escapeLike(term: String): String = term.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    fun detail(userId: UUID): AdminUserDetail {
        val user = findUser(userId)
        return AdminUserDetail(
            user = AdminUserSummary.from(user),
            passkeyCount = passkeyService.listPasskeys(userId).size,
            federatedProviders = federationService.list(userId).map { it.providerLabel },
            activeSessionCount =
                sessionRepository
                    .findByPrincipalName(user.email)
                    .values
                    .count { !it.isExpired },
            recentAuditEvents = adminAuditService.recentForUser(userId, RECENT_AUDIT_LIMIT),
        )
    }

    /**
     * 계정 정지 — 세션 전부 + 신뢰 기기 즉시 폐기(정지 즉시 효력). 이후 로그인은 기존 SUSPENDED
     * 경로(UserDetails.disabled / completeAuthentication 백스톱)가 차단한다.
     */
    fun suspend(
        actor: User,
        targetId: UUID,
    ) {
        // 자기 보호 가드: 마지막 관리자가 스스로를 잠그는 사고 방지.
        if (actor.id == targetId) {
            throw AuthException(ErrorCode.ADMIN_SELF_ACTION)
        }
        val target = findUser(targetId)
        target.status = UserStatus.SUSPENDED.name
        userRepository.save(target)
        sessionManagementService.revokeAll(target.id!!, target.email)
        trustedDeviceService.revokeAll(target.id)
        auditEventService.record(
            "ADMIN_USER_SUSPENDED",
            target.id,
            mapOf("actorEmail" to actor.email, "targetEmail" to target.email),
        )
    }

    fun unsuspend(
        actor: User,
        targetId: UUID,
    ) {
        val target = findUser(targetId)
        target.status = UserStatus.ACTIVE.name
        userRepository.save(target)
        auditEventService.record(
            "ADMIN_USER_UNSUSPENDED",
            target.id,
            mapOf("actorEmail" to actor.email, "targetEmail" to target.email),
        )
    }

    fun revokeSessions(
        actor: User,
        targetId: UUID,
    ): Int {
        val target = findUser(targetId)
        val count = sessionManagementService.revokeAll(target.id!!, target.email)
        auditEventService.record(
            "ADMIN_USER_SESSIONS_REVOKED",
            target.id,
            mapOf("actorEmail" to actor.email, "targetEmail" to target.email, "count" to count),
        )
        return count
    }

    /**
     * 역할 변경(USER↔ADMIN). 세션의 authorities 는 로그인 시점에 굳으므로 승격은 다음 로그인부터
     * 유효하고, 강등은 세션에 남은 ROLE_ADMIN 을 걷어내기 위해 전 세션을 즉시 폐기한다.
     */
    fun changeRole(
        actor: User,
        targetId: UUID,
        role: String,
    ) {
        val newRole =
            UserRole.entries.firstOrNull { it.name == role }
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR)
        // 자기 보호 가드: 자신의 ADMIN 해제 금지(관리자 0명 잠금 방지).
        if (actor.id == targetId && newRole != UserRole.ADMIN) {
            throw AuthException(ErrorCode.ADMIN_SELF_ACTION)
        }
        val target = findUser(targetId)
        if (target.role == newRole.name) {
            return
        }
        val demotedFromAdmin = target.role == UserRole.ADMIN.name
        target.role = newRole.name
        userRepository.save(target)
        if (demotedFromAdmin) {
            sessionManagementService.revokeAll(target.id!!, target.email)
        }
        auditEventService.record(
            "ADMIN_USER_ROLE_CHANGED",
            target.id,
            mapOf("actorEmail" to actor.email, "targetEmail" to target.email, "role" to newRole.name),
        )
    }

    private fun findUser(userId: UUID): User = userRepository.findById(userId).orElse(null) ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

    companion object {
        private const val SEARCH_LIMIT = 50
        private const val RECENT_AUDIT_LIMIT = 20
    }
}
