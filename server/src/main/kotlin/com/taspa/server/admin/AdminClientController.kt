package com.taspa.server.admin

import com.taspa.server.admin.dto.AdminClientView
import com.taspa.server.admin.dto.ClientRegisterRequest
import com.taspa.server.admin.dto.ClientSecretResponse
import com.taspa.server.admin.dto.ClientUpdateRequest
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** OAuth2 클라이언트 관리 API — ADMIN 전용(SecurityConfig), 변경 작업은 step-up + CSRF 헤더 필수. */
@RestController
@RequestMapping("/api/admin/clients")
class AdminClientController(
    private val adminClientService: AdminClientService,
    private val userRepository: UserRepository,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_CLIENTS,
        resource = "trn:taspa:platform::client/*",
    )
    @GetMapping
    fun list(): ResponseEntity<List<AdminClientView>> = ResponseEntity.ok(adminClientService.list())

    @PlatformAction(
        action = IamActions.PLATFORM_REGISTER_CLIENT,
        resource = "trn:taspa:platform::client/*",
    )
    @RequireRecentAuth
    @PostMapping
    fun register(
        authentication: Authentication,
        @RequestBody request: ClientRegisterRequest,
    ): ResponseEntity<ClientSecretResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(adminClientService.register(request, actorId(authentication)))

    @PlatformAction(
        action = IamActions.PLATFORM_UPDATE_CLIENT,
        resource = "trn:taspa:platform::client/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/{id}")
    fun update(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: ClientUpdateRequest,
    ): ResponseEntity<AdminClientView> = ResponseEntity.ok(adminClientService.update(id, request, actorId(authentication)))

    @PlatformAction(
        action = IamActions.PLATFORM_DELETE_CLIENT,
        resource = "trn:taspa:platform::client/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/{id}")
    fun delete(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        adminClientService.delete(id, actorId(authentication))
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.PLATFORM_REGENERATE_CLIENT_SECRET,
        resource = "trn:taspa:platform::client/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/secret")
    fun regenerateSecret(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<ClientSecretResponse> = ResponseEntity.ok(adminClientService.regenerateSecret(id, actorId(authentication)))

    private fun actorId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
