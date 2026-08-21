package com.taspa.server.selfservice

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 자기서비스 프로필 편집(현재는 표시 이름). 파괴적이지 않으므로 step-up 대상이 아니다. */
@Service
class ProfileService(
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
) {
    @Transactional
    fun updateDisplayName(
        userId: UUID,
        displayName: String?,
    ) {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        user.displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        userRepository.save(user)
        auditEventService.record("PROFILE_UPDATED", userId, mapOf("displayName" to user.displayName))
    }
}
