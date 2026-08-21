package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 사업장(구내식당). org 스코프 — org 내 이름 유일(V21 유니크). timezone 은 IANA/Postgres 존 이름
 * (예측·정산 사이트 롤업의 date 버킷 앵커). 유효한 존만 저장한다(SiteService 가 OrganizationService.normalizeTimezone 재사용).
 */
@Entity
@Table(name = "sites")
class Site(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "name", nullable = false, length = 120)
    var name: String,
    @Column(name = "address", length = 255)
    var address: String? = null,
    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String = "UTC",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}
