package com.taspa.server.selfservice

import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.selfservice.dto.LoginHistoryView
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 계정 페이지 "최근 로그인 활동" — login_events 를 사용자별 최신순 최근 N건만 읽어 표시한다(읽기 전용).
 * 활성 세션 목록(SessionManagementService)과 별개이며, 여기서는 어떤 폐기/철회도 하지 않는다.
 */
@Service
class LoginHistoryService(
    private val loginEventRepository: LoginEventRepository,
) {
    companion object {
        const val DEFAULT_LIMIT = 10
    }

    @Transactional(readOnly = true)
    fun recentHistory(
        userId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<LoginHistoryView> =
        loginEventRepository
            .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
            .map { LoginHistoryView(occurredAt = it.createdAt, method = it.method, ip = it.ip, device = it.uaLabel) }
}
