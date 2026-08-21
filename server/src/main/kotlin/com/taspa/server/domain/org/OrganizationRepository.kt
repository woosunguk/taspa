package com.taspa.server.domain.org

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OrganizationRepository : JpaRepository<Organization, UUID> {
    fun findBySlug(slug: String): Organization?

    fun existsBySlug(slug: String): Boolean

    fun findAllByOrderByCreatedAtDesc(): List<Organization>

    /**
     * Postgres 가 실제로 수용하는 타임존인지 검증한다. java.time.ZoneId 검증만으로는 AT TIME ZONE 이
     * 거부하는 값이 통과해 소비 집계 쿼리(V18)가 런타임에 500 날 수 있으므로, 저장 전 pg 수용집합으로 확인한다.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name = :tz)", nativeQuery = true)
    fun existsPgTimezone(
        @Param("tz") tz: String,
    ): Boolean
}
