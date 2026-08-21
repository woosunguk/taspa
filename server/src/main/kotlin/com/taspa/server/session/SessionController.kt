package com.taspa.server.session

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.session.dto.SessionView
import com.taspa.server.stepup.RequireRecentAuth
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class SessionController(
    private val sessionManagementService: SessionManagementService,
    private val userRepository: UserRepository,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        request: HttpServletRequest,
    ): ResponseEntity<List<SessionView>> =
        ResponseEntity.ok(sessionManagementService.listSessions(authentication.name, currentSessionId(request)))

    /** 개별 원격 로그아웃 — step-up(최근 재인증) 대상. 본인 목록 미매칭 publicId 는 404. */
    @RequireRecentAuth
    @DeleteMapping("/{publicId}")
    fun revoke(
        authentication: Authentication,
        @PathVariable publicId: String,
    ): ResponseEntity<Void> {
        sessionManagementService.revoke(currentUserId(authentication), authentication.name, publicId)
        return ResponseEntity.noContent().build()
    }

    /** 현재 세션 제외 전체 로그아웃 — step-up(최근 재인증) 대상. */
    @RequireRecentAuth
    @PostMapping("/revoke-others")
    fun revokeOthers(
        authentication: Authentication,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        sessionManagementService.revokeOthers(
            currentUserId(authentication),
            authentication.name,
            currentSessionId(request),
        )
        return ResponseEntity.noContent().build()
    }

    private fun currentSessionId(request: HttpServletRequest): String? = request.getSession(false)?.id

    private fun currentUserId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
