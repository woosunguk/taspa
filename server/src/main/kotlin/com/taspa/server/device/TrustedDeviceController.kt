package com.taspa.server.device

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.device.dto.TrustedDeviceResponse
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/trusted-devices")
class TrustedDeviceController(
    private val trustedDeviceService: TrustedDeviceService,
    private val userRepository: UserRepository,
) {
    @GetMapping
    fun list(authentication: Authentication): ResponseEntity<List<TrustedDeviceResponse>> =
        ResponseEntity.ok(trustedDeviceService.list(currentUserId(authentication)))

    /** 개별 해제 — step-up(최근 재인증) 대상. */
    @RequireRecentAuth
    @DeleteMapping("/{deviceId}")
    fun revoke(
        authentication: Authentication,
        @PathVariable deviceId: UUID,
    ): ResponseEntity<Void> {
        trustedDeviceService.revoke(currentUserId(authentication), deviceId)
        return ResponseEntity.noContent().build()
    }

    /** 전체 해제 — step-up(최근 재인증) 대상. */
    @RequireRecentAuth
    @DeleteMapping
    fun revokeAll(authentication: Authentication): ResponseEntity<Void> {
        trustedDeviceService.revokeAll(currentUserId(authentication))
        return ResponseEntity.noContent().build()
    }

    private fun currentUserId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
