package com.taspa.server.domain.calendar

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalendarFeedRepository : JpaRepository<CalendarFeed, UUID> {
    fun findByOrgId(orgId: UUID): List<CalendarFeed>

    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): CalendarFeed?

    fun findByEnabledTrueAndSourceUrlIsNotNull(): List<CalendarFeed>
}
