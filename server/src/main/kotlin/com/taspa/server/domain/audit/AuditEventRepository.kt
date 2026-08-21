package com.taspa.server.domain.audit

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AuditEventRepository : JpaRepository<AuditEvent, UUID> {
    // 관리 콘솔 감사 로그 조회 — type/userId 필터 조합별 파생 쿼리(동적 null 파라미터 회피).
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<AuditEvent>

    fun findByTypeOrderByCreatedAtDesc(
        type: String,
        pageable: Pageable,
    ): List<AuditEvent>

    fun findByUserIdOrderByCreatedAtDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<AuditEvent>

    fun findByTypeAndUserIdOrderByCreatedAtDesc(
        type: String,
        userId: UUID,
        pageable: Pageable,
    ): List<AuditEvent>

    /**
     * 조직 스코프 활동로그 — org_id 정확 일치(널·타 org 자동 제외). ORG_ADMIN 자율 콘솔이 자기 조직 감사만
     * 최신순으로 조회한다(idx_audit_events_org_time).
     */
    fun findByOrgIdOrderByCreatedAtDesc(
        orgId: UUID,
        pageable: Pageable,
    ): List<AuditEvent>

    /** HIGH 리스크 경고 메일 쿨다운 판정(RISK_ALERT_MAILED) — MfaAwareAuthenticationSuccessHandler. */
    fun existsByTypeAndUserIdAndCreatedAtAfter(
        type: String,
        userId: UUID,
        after: Instant,
    ): Boolean

    /** 정리 잡: 보존 기간을 넘긴 감사 이벤트 일괄 삭제. */
    @Modifying
    @Query("delete from AuditEvent e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
