package com.taspa.server.federation

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.federation.dto.FederationResponse
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
@RequestMapping("/api/federations")
class FederationController(
    private val federationService: FederationService,
    private val userRepository: UserRepository,
) {
    @GetMapping
    fun list(authentication: Authentication): ResponseEntity<List<FederationResponse>> =
        ResponseEntity.ok(federationService.list(currentUserId(authentication)))

    /** 연결 해제 — step-up(최근 재인증) 대상. 마지막 로그인 수단이면 409(LAST_LOGIN_METHOD). */
    @RequireRecentAuth
    @DeleteMapping("/{provider}")
    fun unlink(
        authentication: Authentication,
        @PathVariable provider: String,
    ): ResponseEntity<Void> {
        federationService.unlink(currentUserId(authentication), provider)
        return ResponseEntity.noContent().build()
    }

    private fun currentUserId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
