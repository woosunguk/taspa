package com.taspa.server.domain.passkey

import org.springframework.data.jpa.repository.JpaRepository

interface PasskeyCredentialRepository : JpaRepository<PasskeyCredential, String> {
    fun findByUserEntityExternalId(userEntityExternalId: String): List<PasskeyCredential>

    fun existsByUserEntityExternalId(userEntityExternalId: String): Boolean
}
