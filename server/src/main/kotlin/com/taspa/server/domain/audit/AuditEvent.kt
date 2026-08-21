package com.taspa.server.domain.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 보안 감사 이벤트 1건당 1행. detail 은 JSON 직렬화 문자열(스키마 자유). */
@Entity
@Table(name = "audit_events")
class AuditEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "type", nullable = false, length = 64)
    val type: String,
    @Column(name = "user_id")
    val userId: UUID? = null,
    /** 조직 결속 감사 이벤트에만 채워진다(멤버/초대/조직 변경). 전역 이벤트(로그인·MFA 등)는 null. */
    @Column(name = "org_id")
    val orgId: UUID? = null,
    @Column(name = "detail", columnDefinition = "TEXT")
    val detail: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
