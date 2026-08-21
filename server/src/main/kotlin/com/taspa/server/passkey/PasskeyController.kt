package com.taspa.server.passkey

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.passkey.dto.PasskeyRenameRequest
import com.taspa.server.passkey.dto.PasskeyResponse
import com.taspa.server.stepup.RequireRecentAuth
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/passkeys")
class PasskeyController(
    private val passkeyService: PasskeyService,
    private val userRepository: UserRepository,
) {
    @GetMapping
    fun list(authentication: Authentication): ResponseEntity<List<PasskeyResponse>> =
        ResponseEntity.ok(passkeyService.listPasskeys(currentUserId(authentication)))

    @RequireRecentAuth
    @PatchMapping("/{credentialId}")
    fun rename(
        authentication: Authentication,
        @PathVariable credentialId: String,
        @Valid @RequestBody request: PasskeyRenameRequest,
    ): ResponseEntity<Void> {
        passkeyService.rename(currentUserId(authentication), credentialId, request.label.trim())
        return ResponseEntity.noContent().build()
    }

    @RequireRecentAuth
    @DeleteMapping("/{credentialId}")
    fun delete(
        authentication: Authentication,
        @PathVariable credentialId: String,
    ): ResponseEntity<Void> {
        passkeyService.delete(currentUserId(authentication), credentialId)
        return ResponseEntity.noContent().build()
    }

    private fun currentUserId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
