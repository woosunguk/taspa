package com.taspa.server.session

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.session.dto.SessionView
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID

/**
 * 원격 세션 관리. Spring Session JDBC 의 principal 인덱스(=SPRING_SECURITY_CONTEXT 의
 * authentication.name, 이메일)로 사용자의 활성 세션을 조회·폐기한다. pending(부분 인증) 세션은
 * SecurityContext 가 없어 인덱싱되지 않으므로 목록에 잡히지 않는다(원하는 동작).
 *
 * deleteById 는 즉시 삭제이고 부활이 없다(spring-session 3.4.2 소스 실측 — UPDATE 0행 무시 +
 * 속성 INSERT FK 위반 무시). 폐기된 세션의 진행 중 요청만 그 요청 수명 동안 유효하다.
 */
@Service
class SessionManagementService(
    private val sessionRepository: FindByIndexNameSessionRepository<out Session>,
    private val auditEventService: AuditEventService,
) {
    fun listSessions(
        email: String,
        currentSessionId: String?,
    ): List<SessionView> =
        sessionRepository
            .findByPrincipalName(email)
            .values
            // findByPrincipalName 은 findById 와 달리 만료 필터가 없다(3.4.x) — 만료됐지만 아직
            // 정리 cron(기본 1분)이 지우지 않은 세션이 목록에 잡히지 않도록 걸러낸다.
            .filterNot { it.isExpired }
            .map { toView(it, currentSessionId) }
            .sortedWith(compareByDescending<SessionView> { it.current }.thenByDescending { it.lastActiveAt })

    /** 개별 폐기 — 본인 세션 목록에서 publicId 가 매칭된 것만 삭제한다. 미매칭(타 사용자 포함)은 404. */
    fun revoke(
        userId: UUID,
        email: String,
        publicId: String,
    ) {
        val target =
            sessionRepository
                .findByPrincipalName(email)
                .values
                .firstOrNull { publicId(it.id) == publicId }
                ?: throw AuthException(ErrorCode.NOT_FOUND)
        sessionRepository.deleteById(target.id)
        auditEventService.record("SESSION_REVOKED", userId, mapOf("sessionPublicId" to publicId))
    }

    /** 현재 세션을 제외한 전부 폐기. */
    fun revokeOthers(
        userId: UUID,
        email: String,
        currentSessionId: String?,
    ): Int {
        val others = sessionRepository.findByPrincipalName(email).keys.filter { it != currentSessionId }
        others.forEach(sessionRepository::deleteById)
        auditEventService.record("SESSIONS_REVOKED_OTHERS", userId, mapOf("count" to others.size))
        return others.size
    }

    /** 계정 탈취 대응(비밀번호 재설정 등): 이 사용자의 모든 세션 즉시 폐기. */
    fun revokeAll(
        userId: UUID,
        email: String,
    ): Int {
        val all = sessionRepository.findByPrincipalName(email).keys.toList()
        all.forEach(sessionRepository::deleteById)
        auditEventService.record("SESSIONS_REVOKED_ALL", userId, mapOf("count" to all.size))
        return all.size
    }

    private fun toView(
        session: Session,
        currentSessionId: String?,
    ) = SessionView(
        publicId = publicId(session.id),
        ip = session.getAttribute(SessionMetadata.CLIENT_IP_KEY),
        browser = session.getAttribute(SessionMetadata.USER_AGENT_KEY),
        createdAt = session.creationTime,
        lastActiveAt = session.lastAccessedTime,
        current = session.id == currentSessionId,
    )

    companion object {
        fun publicId(sessionId: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(sessionId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
    }
}
