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
 * 조직(테넌트). ADR 0002 대안 C — 결제·예측이 공유하는 테넌시 경계.
 * slug 는 URL/식별용 정규화 키([a-z0-9-], 유니크). status 로 정지(SUSPENDED)를 표현한다.
 */
@Entity
@Table(name = "organizations")
class Organization(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "slug", nullable = false, unique = true, length = 64)
    var slug: String,
    @Column(name = "name", nullable = false, length = 200)
    var name: String,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = OrgStatus.ACTIVE.name,
    /**
     * org-로컬 타임존(IANA/Postgres 존 이름, 예: Asia/Seoul). 소비 이벤트 집계의 date 버킷을 이 존의 로컬
     * 달력으로 앵커링한다(UTC 절단 오귀속 방지). 기본 UTC. 유효한 존 이름만 저장한다(OrganizationService 검증).
     */
    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String = "UTC",
    /**
     * 이메일 도메인 자동 가입 opt-in(V24, 기본 OFF). 검증된 org_domains 가 있어도 이 플래그가 꺼져 있으면
     * 자동 가입은 동작하지 않는다 — ORG_ADMIN 이 명시적으로 켠 조직만(전용 엔드포인트, updateProfile 불변).
     */
    @Column(name = "auto_join_enabled", nullable = false)
    var autoJoinEnabled: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    fun statusEnum(): OrgStatus = OrgStatus.valueOf(status)
}
