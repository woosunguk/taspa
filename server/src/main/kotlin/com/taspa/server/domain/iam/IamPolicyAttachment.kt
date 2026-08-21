package com.taspa.server.domain.iam

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** managed 정책 부착: 정책 → principal(USER|GROUP). principalId 는 users.id 또는 iam_principal_groups.id. */
@Entity
@Table(name = "iam_policy_attachments")
class IamPolicyAttachment(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "policy_id", nullable = false)
    val policyId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 16)
    val principalType: IamPrincipalType,
    @Column(name = "principal_id", nullable = false)
    val principalId: UUID,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

/** inline 정책: principal 에 직접 임베드된 문서(재사용 불가, 그 principal 소유). */
@Entity
@Table(name = "iam_inline_policies")
class IamInlinePolicy(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 16)
    val principalType: IamPrincipalType,
    @Column(name = "principal_id", nullable = false)
    val principalId: UUID,
    @Column(name = "name", nullable = false, length = 128)
    var name: String,
    @Column(name = "document", columnDefinition = "TEXT", nullable = false)
    var document: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
