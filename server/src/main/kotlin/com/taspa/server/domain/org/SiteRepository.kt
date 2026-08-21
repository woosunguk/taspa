package com.taspa.server.domain.org

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SiteRepository : JpaRepository<Site, UUID> {
    fun findByOrgId(orgId: UUID): List<Site>

    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): Site?

    fun existsByOrgIdAndName(
        orgId: UUID,
        name: String,
    ): Boolean
}
