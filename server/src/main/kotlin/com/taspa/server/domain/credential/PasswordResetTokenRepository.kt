package com.taspa.server.domain.credential

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, UUID> {
    fun findByTokenHash(tokenHash: String): PasswordResetToken?

    /** 발급 스로틀 판정용: 사용자의 최신 토큰. */
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: UUID): PasswordResetToken?

    /** 정리 잡: 사용(used)됐거나 만료된 토큰 일괄 삭제(무한 누적 방지). */
    @Modifying
    @Query("delete from PasswordResetToken t where t.used = true or t.expiresAt < :now")
    fun deleteUsedOrExpired(
        @Param("now") now: Instant,
    ): Int
}
