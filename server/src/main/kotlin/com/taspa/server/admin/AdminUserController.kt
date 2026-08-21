package com.taspa.server.admin

import com.taspa.server.admin.dto.AdminUserDetail
import com.taspa.server.admin.dto.AdminUserSummary
import com.taspa.server.admin.dto.RoleChangeRequest
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 사용자 관리 API — ADMIN 전용(SecurityConfig), 변경 작업은 step-up + CSRF 헤더 필수. */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(
    private val adminUserService: AdminUserService,
    private val userRepository: UserRepository,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_SEARCH_USERS,
        resource = "trn:taspa:platform::user/*",
    )
    @GetMapping
    fun search(
        @RequestParam(required = false) query: String?,
    ): ResponseEntity<List<AdminUserSummary>> = ResponseEntity.ok(adminUserService.search(query))

    @PlatformAction(
        action = IamActions.PLATFORM_READ_USER,
        resource = "trn:taspa:platform::user/{id}",
    )
    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: UUID,
    ): ResponseEntity<AdminUserDetail> = ResponseEntity.ok(adminUserService.detail(id))

    @PlatformAction(
        action = IamActions.PLATFORM_SUSPEND_USER,
        resource = "trn:taspa:platform::user/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/suspend")
    fun suspend(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        adminUserService.suspend(actor(authentication), id)
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.PLATFORM_UNSUSPEND_USER,
        resource = "trn:taspa:platform::user/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/unsuspend")
    fun unsuspend(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        adminUserService.unsuspend(actor(authentication), id)
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.PLATFORM_REVOKE_USER_SESSIONS,
        resource = "trn:taspa:platform::user/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/sessions/revoke")
    fun revokeSessions(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        adminUserService.revokeSessions(actor(authentication), id)
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.PLATFORM_CHANGE_USER_ROLE,
        resource = "trn:taspa:platform::user/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/role")
    fun changeRole(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: RoleChangeRequest,
    ): ResponseEntity<Void> {
        adminUserService.changeRole(actor(authentication), id, request.role)
        return ResponseEntity.noContent().build()
    }

    private fun actor(authentication: Authentication): User =
        userRepository.findByEmail(authentication.name)
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
