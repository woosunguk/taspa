package com.taspa.server.domain.verification

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface EmailVerificationCodeRepository : JpaRepository<EmailVerificationCode, UUID> {
    fun findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(userId: UUID): EmailVerificationCode?

    fun findFirstByUserIdOrderByCreatedAtDesc(userId: UUID): EmailVerificationCode?

    /** 정리 잡: 소진(consumed)됐거나 만료된 코드 일괄 삭제(무한 누적 방지). */
    @Modifying
    @Query("delete from EmailVerificationCode c where c.consumedAt is not null or c.expiresAt < :now")
    fun deleteConsumedOrExpired(
        @Param("now") now: Instant,
    ): Int
}
