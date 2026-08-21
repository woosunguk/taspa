package com.taspa.server.iam

import com.taspa.server.domain.iam.IamGroupMemberRepository
import com.taspa.server.domain.iam.IamInlinePolicyRepository
import com.taspa.server.domain.iam.IamPolicyAttachmentRepository
import com.taspa.server.domain.iam.IamPolicyRepository
import com.taspa.server.domain.iam.IamPrincipalGroupRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * principal 의 **저장된** 유효 정책 집합을 조립한다: inline(직접) + managed(부착) + 소속 그룹의 inline·부착.
 * 레거시 브리지(LegacyPolicyBridge)와 합쳐 최종 평가 입력이 된다. M2M 클라이언트는 저장 정책이 없어
 * 브리지만 사용하므로 여기서 다루지 않는다(사용자 principal 전용).
 */
@Component
class PrincipalPolicyResolver(
    private val policyRepository: IamPolicyRepository,
    private val attachmentRepository: IamPolicyAttachmentRepository,
    private val inlineRepository: IamInlinePolicyRepository,
    private val groupMemberRepository: IamGroupMemberRepository,
    private val groupRepository: IamPrincipalGroupRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val organizationRepository: OrganizationRepository,
    private val parser: PolicyParser,
) {
    private val log = LoggerFactory.getLogger(PrincipalPolicyResolver::class.java)

    fun resolveUserPolicies(userId: UUID): List<PolicyDocument> {
        val documents = mutableListOf<PolicyDocument>()
        val policyIds = mutableSetOf<UUID>()

        // 사용자 직접: inline + managed 부착
        inlineRepository
            .findByPrincipalTypeAndPrincipalId(IamPrincipalType.USER, userId)
            .forEach { documents += parseOrFail(it.document, "inline:user:${it.name}") }
        attachmentRepository
            .findByPrincipalTypeAndPrincipalId(IamPrincipalType.USER, userId)
            .forEach { policyIds += it.policyId }

        // 소속 그룹: inline + managed 부착. 그룹이 org 스코프면 그 그룹에서 온 문서는 **그 org 로 가둔다**.
        val groupIds = groupMemberRepository.findByUserId(userId).map { it.groupId }
        val groupOrgById =
            if (groupIds.isEmpty()) {
                emptyMap()
            } else {
                groupRepository.findAllById(groupIds).associate { it.id!! to it.orgId }
            }
        val liveOrgs = activeOrgIdsOf(userId, groupOrgById.values.filterNotNull().toSet())

        groupIds.forEach { groupId ->
            val groupOrg = groupOrgById[groupId]
            // ★org 스코프 그룹의 정책은 **지금도 그 조직 사람일 때만** 유효하다(아래 주석 참고).
            if (groupOrg != null && groupOrg !in liveOrgs) return@forEach
            inlineRepository
                .findByPrincipalTypeAndPrincipalId(IamPrincipalType.GROUP, groupId)
                .forEach {
                    documents += confine(parseOrFail(it.document, "inline:group:${it.name}"), groupOrg)
                }
            attachmentRepository
                .findByPrincipalTypeAndPrincipalId(IamPrincipalType.GROUP, groupId)
                .forEach { policyIds += it.policyId }
        }

        if (policyIds.isNotEmpty()) {
            // managed 정책은 문서 자체가 org 스코프를 안다(iam_policies.org_id).
            val managed = policyRepository.findAllById(policyIds)
            val managedOrgs = activeOrgIdsOf(userId, managed.mapNotNull { it.orgId }.toSet())
            managed.forEach {
                // 사용자에게 **직접** 부착된 org 스코프 정책도 같은 이유로 멤버십을 확인한다.
                if (it.orgId != null && it.orgId !in managedOrgs) return@forEach
                documents += confine(parseOrFail(it.document, "managed:${it.name}"), it.orgId)
            }
        }
        return documents
    }

    /**
     * 주어진 org 들 중 **이 사용자가 지금도 활성 구성원인 조직**만 남긴다.
     *
     * ★이 게이트가 없으면 **퇴사자가 조직 권한을 그대로 유지한다.** org 스코프 그룹(커스텀 역할)의
     * 부여는 `iam_group_members` 행이고, 조직에서 제거해도(`OrganizationService.removeMember` 는
     * `org_memberships` 행만 지운다) 그 행은 남는다 — V27 의 CASCADE 는 users·groups 삭제에만 걸린다.
     * 멤버십 SUSPENDED(SCIM `active=false`)·조직 SUSPENDED 도 마찬가지로 저장 정책에는 닿지 않는다.
     *
     * ★**비대칭이 문제의 핵심이었다.** ORG_ADMIN 권한은 `OrgSessionAuthorizer` 가 `isOrgAdmin`
     * (org 상태·멤버십 상태를 접는 **라이브 질의**)으로 브리지를 만들어 즉시 회수되고, 토큰 `roles`
     * 클레임조차 `activeMembershipsFor` 로 닫혀 있다. 정작 **인가 권위인 저장 정책 경로만** 열려 있었다.
     * 부여 시점 검사(`OrgRoleService.assign`)는 그대로 두되, 그것을 유일한 방어선으로 삼지 않는다.
     *
     * 질의는 org 수와 무관하게 2회다(멤버십 1 + 조직 1). org 스코프 그룹이 없으면 0회.
     */
    private fun activeOrgIdsOf(
        userId: UUID,
        candidateOrgs: Set<UUID>,
    ): Set<UUID> {
        if (candidateOrgs.isEmpty()) return emptySet()
        val memberOf =
            membershipRepository
                .findByUserId(userId)
                .filter { it.orgId in candidateOrgs && it.statusEnum() == MembershipStatus.ACTIVE }
                .map { it.orgId }
                .toSet()
        if (memberOf.isEmpty()) return emptySet()
        return organizationRepository
            .findAllById(memberOf)
            .filter { it.statusEnum() == OrgStatus.ACTIVE }
            .mapNotNull { it.id }
            .toSet()
    }

    /**
     * **org 스코프 정책을 그 org 안에 가둔다** — 심층 방어.
     *
     * ★평가기는 정책의 `org_id` 를 보지 않는다. 지금까지는 정책을 플랫폼 ADMIN 만 만들 수 있어서
     * 문제가 없었지만(org 경계는 쓰기 시점 `requireOrgConsistency` 로만 지켜졌다), 조직관리자에게
     * 역할 정의를 열어 주는 순간 **org 스코프 정책에 `trn:taspa:platform::*` 를 써 넣으면 평가기가
     * 그대로 인정**한다. 그래서 여기서 org 스코프 문서의 모든 **Allow** 문장에 `taspa:ResourceOrg`
     * 정확일치 조건을 덧붙여, 문서가 무엇을 주장하든 그 org 자원 밖으로 나가지 못하게 한다.
     *
     * Deny 문장은 건드리지 않는다 — 조건을 덧붙이면 조건 미평가 시 **Deny 가 약해질** 수 있고
     * (fail-safe 편향은 Deny 를 적용하는 쪽이다), 남의 org 를 향한 Deny 는 권한을 넓히지 않는다.
     *
     * 플랫폼 전역 정책(orgId=null)은 그대로 둔다 — 그건 정의상 org 경계가 없는 문서다.
     */
    private fun confine(
        document: PolicyDocument,
        orgId: UUID?,
    ): PolicyDocument {
        if (orgId == null) return document
        val pin = Condition("StringEquals", PolicyEvaluator.RESOURCE_ORG_KEY, listOf(orgId.toString()))
        return document.copy(
            statements =
                document.statements.map { statement ->
                    if (statement.effect != Effect.ALLOW) {
                        statement
                    } else {
                        statement.copy(conditions = statement.conditions + pin)
                    }
                },
        )
    }

    /**
     * 저장 정책 파싱. **실패 시 스킵하지 않고 예외를 던진다(fail-closed)** — 손상된 문서를 조용히 버리면
     * 그것이 Deny 가드였을 때 가드가 사라져 권한상승이 된다. 호출자(shadow/집행)는 예외를 거부로 처리한다.
     * 저장 시점 검증을 통과했어야 하므로 여기 도달 자체가 데이터 오염 신호다.
     */
    private fun parseOrFail(
        document: String,
        source: String,
    ): PolicyDocument =
        try {
            parser.parse(document)
        } catch (e: Exception) {
            log.error("stored policy parse failed [{}] — failing closed: {}", source, e.message)
            throw IllegalStateException("저장된 정책 문서가 손상됨 ($source)", e)
        }
}
