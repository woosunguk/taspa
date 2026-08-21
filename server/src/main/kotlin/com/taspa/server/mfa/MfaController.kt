package com.taspa.server.mfa

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mfa.dto.BackupCodesResponse
import com.taspa.server.mfa.dto.MfaSetupResponse
import com.taspa.server.mfa.dto.MfaVerifyRequest
import com.taspa.server.stepup.RequireRecentAuth
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** MFA 등록/해제/백업 코드 재발급 — 전부 민감 작업이라 step-up(최근 재인증) 대상이다. */
@RequireRecentAuth
@RestController
@RequestMapping("/api/mfa")
class MfaController(
    private val mfaService: MfaService,
    private val userRepository: UserRepository,
) {
    @PostMapping("/setup")
    fun setup(authentication: Authentication): ResponseEntity<MfaSetupResponse> =
        ResponseEntity.ok(mfaService.setup(currentUserId(authentication)))

    @PostMapping("/activate")
    fun activate(
        authentication: Authentication,
        @Valid @RequestBody request: MfaVerifyRequest,
    ): ResponseEntity<BackupCodesResponse> {
        val codes = mfaService.activate(currentUserId(authentication), request.code)
        return ResponseEntity.ok(BackupCodesResponse(backupCodes = codes))
    }

    @PostMapping("/disable")
    fun disable(
        authentication: Authentication,
        @Valid @RequestBody request: MfaVerifyRequest,
    ): ResponseEntity<Map<String, Boolean>> {
        mfaService.disable(currentUserId(authentication), request.code)
        return ResponseEntity.ok(mapOf("mfaEnabled" to false))
    }

    @PostMapping("/backup-codes/regenerate")
    fun regenerateBackupCodes(authentication: Authentication): ResponseEntity<BackupCodesResponse> {
        val codes = mfaService.regenerateBackupCodes(currentUserId(authentication))
        return ResponseEntity.ok(BackupCodesResponse(backupCodes = codes))
    }

    private fun currentUserId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
