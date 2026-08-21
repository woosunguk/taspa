package com.taspa.server.domain.passkey

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PasskeyUserEntityRepository : JpaRepository<PasskeyUserEntity, UUID> {
    fun findByExternalId(externalId: String): PasskeyUserEntity?

    fun findByName(name: String): PasskeyUserEntity?

    fun deleteByExternalId(externalId: String)
}
