package com.taspa.server.domain.mfa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BackupCodeRepository : JpaRepository<BackupCode, UUID> {
    fun findByUserIdAndUsedAtIsNull(userId: UUID): List<BackupCode>

    fun deleteAllByUserId(userId: UUID)
}
