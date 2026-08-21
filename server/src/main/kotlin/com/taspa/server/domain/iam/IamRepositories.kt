package com.taspa.server.domain.iam

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IamPolicyRepository : JpaRepository<IamPolicy, UUID> {
    fun findByOrgIdIsNull(): List<IamPolicy>

    fun findByOrgId(orgId: UUID): List<IamPolicy>

    fun findByNameAndOrgIdIsNull(name: String): IamPolicy?

    fun findByNameAndOrgId(
        name: String,
        orgId: UUID,
    ): IamPolicy?
}

interface IamPrincipalGroupRepository : JpaRepository<IamPrincipalGroup, UUID> {
    fun findByOrgIdIsNull(): List<IamPrincipalGroup>

    fun findByOrgId(orgId: UUID): List<IamPrincipalGroup>

    /** 조직 안 이름 유일성 검사(부분 유니크 인덱스 uq_iam_group_org_name 과 짝). */
    fun findByOrgIdAndName(
        orgId: UUID,
        name: String,
    ): IamPrincipalGroup?

    fun findByNameAndOrgIdIsNull(name: String): IamPrincipalGroup?

    fun findByNameAndOrgId(
        name: String,
        orgId: UUID,
    ): IamPrincipalGroup?
}

interface IamGroupMemberRepository : JpaRepository<IamGroupMember, IamGroupMemberId> {
    fun findByUserId(userId: UUID): List<IamGroupMember>

    fun findByGroupId(groupId: UUID): List<IamGroupMember>

    fun findByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    ): IamGroupMember?

    fun countByGroupId(groupId: UUID): Long

    fun existsByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    ): Boolean

    fun deleteByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    )
}

interface IamPolicyAttachmentRepository : JpaRepository<IamPolicyAttachment, UUID> {
    fun findByPrincipalTypeAndPrincipalId(
        principalType: IamPrincipalType,
        principalId: UUID,
    ): List<IamPolicyAttachment>

    fun findByPolicyId(policyId: UUID): List<IamPolicyAttachment>

    fun existsByPolicyIdAndPrincipalTypeAndPrincipalId(
        policyId: UUID,
        principalType: IamPrincipalType,
        principalId: UUID,
    ): Boolean

    fun deleteByPolicyIdAndPrincipalTypeAndPrincipalId(
        policyId: UUID,
        principalType: IamPrincipalType,
        principalId: UUID,
    )
}

interface IamInlinePolicyRepository : JpaRepository<IamInlinePolicy, UUID> {
    fun findByPrincipalTypeAndPrincipalId(
        principalType: IamPrincipalType,
        principalId: UUID,
    ): List<IamInlinePolicy>

    fun findByPrincipalTypeAndPrincipalIdAndName(
        principalType: IamPrincipalType,
        principalId: UUID,
        name: String,
    ): IamInlinePolicy?

    /**
     * 여러 principal 의 특정 이름 인라인 정책을 **한 번에** 가져온다.
     * 토큰 발급 경로(`org.roles` 클레임)가 사용자의 그룹 수만큼 질의하지 않도록 하는 배치 조회 —
     * 로그인 직후 토큰 발급은 사용자를 기다리게 하는 구간이라 N+1 이 그대로 지연이 된다.
     */
    fun findByPrincipalTypeAndNameAndPrincipalIdIn(
        principalType: IamPrincipalType,
        name: String,
        principalIds: Collection<UUID>,
    ): List<IamInlinePolicy>
}
