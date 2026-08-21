package com.taspa.server.domain.iam

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** 사용자 그룹(IAM Group). 정책을 그룹에 붙이면 멤버 전원에 적용. orgId NULL = 플랫폼 그룹. */
@Entity
@Table(name = "iam_principal_groups")
class IamPrincipalGroup(
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
    @Column(name = "system_managed", nullable = false)
    val systemManaged: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

/** 그룹 멤버십 조인 행(복합 PK group_id+user_id). */
@Entity
@Table(name = "iam_group_members")
@IdClass(IamGroupMemberId::class)
class IamGroupMember(
    @Id
    @Column(name = "group_id")
    val groupId: UUID,
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

/** IamGroupMember 복합 키(no-arg 기본값 + Serializable 필수). */
data class IamGroupMemberId(
    val groupId: UUID = UUIDs.NIL,
    val userId: UUID = UUIDs.NIL,
) : Serializable

private object UUIDs {
    val NIL: UUID = UUID(0L, 0L)
}
