package com.taspa.server.domain.sso

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SsoConnectionRepository : JpaRepository<SsoConnection, UUID> {
    fun findByRegistrationId(registrationId: String): SsoConnection?

    fun existsByRegistrationId(registrationId: String): Boolean

    fun findAllByOrderByCreatedAtDesc(): List<SsoConnection>

    fun findByEnabledTrueAndProtocol(protocol: String): List<SsoConnection>
}
