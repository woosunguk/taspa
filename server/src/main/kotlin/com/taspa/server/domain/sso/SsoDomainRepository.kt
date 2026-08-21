package com.taspa.server.domain.sso

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SsoDomainRepository : JpaRepository<SsoDomain, String> {
    fun findByConnectionId(connectionId: UUID): List<SsoDomain>

    fun findByDomainAndVerifiedTrue(domain: String): SsoDomain?
}
