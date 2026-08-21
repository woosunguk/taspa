package com.taspa.server.domain.iam

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 재사용 가능한 managed 정책(AWS managed/customer-managed policy 대응). document 는 정책 JSON 문자열.
 * orgId NULL = 플랫폼 전역 정책, non-null = 조직 관리 정책. systemManaged=true 는 시드된 불변 시스템 정책.
 */
@Entity
@Table(name = "iam_policies")
class IamPolicy(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "name", nullable = false, length = 128)
    var name: String,
    @Column(name = "org_id")
    val orgId: UUID? = null,
    @Column(name = "description", length = 512)
    var description: String? = null,
    @Column(name = "document", columnDefinition = "TEXT", nullable = false)
    var document: String,
    @Column(name = "system_managed", nullable = false)
    val systemManaged: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
