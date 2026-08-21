package com.taspa.server.admin

import com.taspa.server.admin.dto.MerchantMemberAddRequest
import com.taspa.server.admin.dto.MerchantMemberView
import com.taspa.server.admin.dto.MerchantUpsertRequest
import com.taspa.server.admin.dto.MerchantView
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

/**
 * 가맹 관리 API(식권 L1) — ADMIN 전용(/api/admin/ 경로 hasRole ADMIN), 변경 작업은
 * step-up(@RequireRecentAuth) + CSRF 헤더 필수(AdminOrgController 와 동일 표준). UI 는 다음 배치.
 */
@RestController
@RequestMapping("/api/admin/merchants")
class AdminMerchantController(
    private val adminMerchantService: AdminMerchantService,
    private val userRepository: UserRepository,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_MERCHANTS,
        resource = "trn:taspa:platform::merchant/*",
    )
    @GetMapping
    fun list(): ResponseEntity<List<MerchantView>> = ResponseEntity.ok(adminMerchantService.list())

    @PlatformAction(
        action = IamActions.PLATFORM_READ_MERCHANT,
        resource = "trn:taspa:platform::merchant/{id}",
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<MerchantView> = ResponseEntity.ok(adminMerchantService.get(id))

    @PlatformAction(
        action = IamActions.PLATFORM_CREATE_MERCHANT,
        resource = "trn:taspa:platform::merchant/*",
    )
    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @RequestBody request: MerchantUpsertRequest,
    ): ResponseEntity<MerchantView> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(adminMerchantService.create(request, actorId(authentication)))

    @PlatformAction(
        action = IamActions.PLATFORM_UPDATE_MERCHANT,
        resource = "trn:taspa:platform::merchant/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/{id}")
    fun update(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: MerchantUpsertRequest,
    ): ResponseEntity<MerchantView> = ResponseEntity.ok(adminMerchantService.update(id, request, actorId(authentication)))

    @PlatformAction(
        action = IamActions.PLATFORM_DELETE_MERCHANT,
        resource = "trn:taspa:platform::merchant/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/{id}")
    fun delete(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        adminMerchantService.delete(id, actorId(authentication))
        return ResponseEntity.noContent().build()
    }

    // ---- 가맹 직원(사람 신원, V29) ----

    /**
     * 가맹 직원 목록·부여·해제 — **부여 권한은 플랫폼 ADMIN 전용**(가맹 관리자는 조회만). 매장 신원이
     * 스스로 증식하지 못하게 발급을 플랫폼이 쥔다. 변경은 기존 admin 관례대로 step-up + CSRF + 감사.
     */
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_MERCHANT_MEMBERS,
        resource = "trn:taspa:platform::merchant/{merchantId}",
    )
    @GetMapping("/{merchantId}/members")
    fun listMembers(
        @PathVariable merchantId: UUID,
    ): ResponseEntity<List<MerchantMemberView>> = ResponseEntity.ok(adminMerchantService.listMembers(merchantId))

    @PlatformAction(
        action = IamActions.PLATFORM_GRANT_MERCHANT_MEMBER,
        resource = "trn:taspa:platform::merchant/{merchantId}",
    )
    @RequireRecentAuth
    @PostMapping("/{merchantId}/members")
    fun addMember(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @RequestBody request: MerchantMemberAddRequest,
    ): ResponseEntity<MerchantMemberView> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(adminMerchantService.addMember(merchantId, request, actorId(authentication)))

    @PlatformAction(
        action = IamActions.PLATFORM_REVOKE_MERCHANT_MEMBER,
        resource = "trn:taspa:platform::merchant/{merchantId}",
    )
    @RequireRecentAuth
    @DeleteMapping("/{merchantId}/members/{userId}")
    fun removeMember(
        authentication: Authentication,
        @PathVariable merchantId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Void> {
        adminMerchantService.removeMember(merchantId, userId, actorId(authentication))
        return ResponseEntity.noContent().build()
    }

    private fun actorId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
