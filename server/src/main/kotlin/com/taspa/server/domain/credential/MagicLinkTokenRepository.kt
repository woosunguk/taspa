package com.taspa.server.domain.credential

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MagicLinkTokenRepository : JpaRepository<MagicLinkToken, UUID> {
    fun findByTokenHash(tokenHash: String): MagicLinkToken?

    fun findFirstByUserIdOrderByCreatedAtDesc(userId: UUID): MagicLinkToken?

    /**
     * 단일 사용 마킹의 원자적 버전 — used_at IS NULL 조건부 UPDATE.
     * 동시 소비 경쟁에서 정확히 한 트랜잭션만 1 을 돌려받는다(read-then-write 경쟁 방지).
     */
    @Modifying(clearAutomatically = true)
    @Query("update MagicLinkToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    fun markUsed(
        @Param("id") id: UUID,
        @Param("now") now: Instant,
    ): Int

    /** 정리 잡: 소진됐거나 만료된 토큰 일괄 삭제. */
    @Modifying
    @Query("delete from MagicLinkToken t where t.usedAt is not null or t.expiresAt < :now")
    fun deleteConsumedOrExpired(
        @Param("now") now: Instant,
    ): Int
}
