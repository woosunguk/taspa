package com.taspa.server.domain.login

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface LoginEventRepository : JpaRepository<LoginEvent, UUID> {
    fun existsByUserIdAndIpAndUaLabelAndCreatedAtAfter(
        userId: UUID,
        ip: String,
        uaLabel: String,
        after: Instant,
    ): Boolean

    /** 직전 성공 로그인(성공만 기록되므로 최신 행) — 리스크 신호 rapidIpChange 판정용. */
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: UUID): LoginEvent?

    /** 최근 로그인 활동(계정 페이지 읽기 전용 표시) — 최신순, Pageable 로 상한 N건. */
    fun findByUserIdOrderByCreatedAtDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<LoginEvent>

    /** 정리 잡: 보존 기간을 넘긴 로그인 이벤트 일괄 삭제. */
    @Modifying
    @Query("delete from LoginEvent e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
