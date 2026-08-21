package com.taspa.server.domain.device

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TrustedDeviceRepository : JpaRepository<TrustedDevice, UUID> {
    fun findByTokenHash(tokenHash: String): TrustedDevice?

    fun findByUserId(userId: UUID): List<TrustedDevice>

    fun deleteAllByUserId(userId: UUID)

    /** 정리 잡: 만료된 신뢰 기기 일괄 삭제. */
    @Modifying
    @Query("delete from TrustedDevice d where d.expiresAt < :now")
    fun deleteByExpiresAtBefore(
        @Param("now") now: Instant,
    ): Int
}
