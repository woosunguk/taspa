package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.iam.IamGroupMember
import com.taspa.server.domain.iam.IamGroupMemberRepository
import com.taspa.server.domain.iam.IamInlinePolicy
import com.taspa.server.domain.iam.IamInlinePolicyRepository
import com.taspa.server.domain.iam.IamPrincipalGroup
import com.taspa.server.domain.iam.IamPrincipalGroupRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.Condition
import com.taspa.server.iam.Effect
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PolicyDocument
import com.taspa.server.iam.PolicyEvaluator
import com.taspa.server.iam.PolicyParser
import com.taspa.server.iam.Statement
import com.taspa.server.org.dto.OrgRoleDetailView
import com.taspa.server.org.dto.OrgRoleMemberView
import com.taspa.server.org.dto.OrgRoleView
import com.taspa.server.org.dto.SaveOrgRoleRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * **조직 커스텀 역할** — 조직관리자가 자기 조직 안에서 역할을 정의하고 구성원에게 부여한다.
 *
 * 그전까지 조직 역할은 `MEMBER`/`ORG_ADMIN` **둘뿐**이었다. "구성원 목록과 청구서는 보되 식대 정책은
 * 못 바꾸는 인사 담당" 같은 중간이 없어서, 그런 사람에게 권한을 주려면 **조직 전체 관리자**를 줘야 했다.
 *
 * ## 저장 구조 — 마이그레이션 없이 IAM 기존 테이블을 재사용한다
 *
 * 역할 하나 = **org 스코프 그룹**(`iam_principal_groups`, org_id 채움) + 그 그룹의 **인라인 정책 1개**
 * (`iam_inline_policies`, name=[ROLE_POLICY_NAME]). 부여는 `iam_group_members` 행이다.
 * 그래서 평가는 기존 `PrincipalPolicyResolver` 경로를 그대로 타고, 새 인가 코드가 필요 없다.
 *
 * 플랫폼이 만든 org 그룹과 구별해야 하므로, **인라인 정책 이름이 [ROLE_POLICY_NAME] 인 그룹만**
 * 이 서비스가 다룬다(그 외 그룹은 조직 콘솔에서 읽기 전용으로만 보인다).
 *
 * ## 권한상승을 막는 세 겹
 *
 * 1. ★**정책 문서를 서버가 생성한다.** 조직관리자는 능력 목록에서 고르기만 하고 원시 JSON 을 쓰지
 *    못한다. 리소스 TRN 과 `taspa:ResourceOrg` 조건은 서버가 박으므로 **남의 조직을 가리키는 정책이
 *    구조적으로 만들어질 수 없다**. (원시 편집을 열면 `trn:taspa:platform::*` 한 줄로 끝난다 —
 *    평가기는 정책의 org_id 를 보지 않기 때문이다.)
 * 2. **부여 가능 action ⊆ [IamActions.ORG_ROLE_GRANTABLE_ACTIONS]** = ORG_ADMIN 천장 − 자기증식 3종.
 * 3. `PrincipalPolicyResolver.confine` 이 org 스코프 문서를 한 번 더 그 org 로 가둔다(심층 방어).
 */
@Service
class OrgRoleService(
    private val groupRepository: IamPrincipalGroupRepository,
    private val groupMemberRepository: IamGroupMemberRepository,
    private val inlineRepository: IamInlinePolicyRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val userRepository: UserRepository,
    private val organizationService: OrganizationService,
    private val policyParser: PolicyParser,
    private val auditEventService: AuditEventService,
) {
    @Transactional(readOnly = true)
    fun list(orgId: UUID): List<OrgRoleView> {
        organizationService.requireOrg(orgId)
        return groupRepository
            .findByOrgId(orgId)
            .mapNotNull { group ->
                val policy = rolePolicyOf(group.id!!) ?: return@mapNotNull null
                OrgRoleView(
                    id = group.id,
                    name = group.name,
                    description = group.description,
                    actions = actionsOf(policy.document),
                    memberCount = groupMemberRepository.countByGroupId(group.id).toInt(),
                )
            }.sortedBy { it.name }
    }

    @Transactional(readOnly = true)
    fun detail(
        orgId: UUID,
        roleId: UUID,
    ): OrgRoleDetailView {
        val group = requireRole(orgId, roleId)
        val policy = rolePolicyOf(roleId)!!
        val members = groupMemberRepository.findByGroupId(roleId)
        val emails = userRepository.findAllById(members.map { it.userId }).associate { it.id!! to it.email }
        return OrgRoleDetailView(
            id = roleId,
            name = group.name,
            description = group.description,
            actions = actionsOf(policy.document),
            members = members.map { OrgRoleMemberView(it.userId, emails[it.userId]) }.sortedBy { it.email ?: "" },
        )
    }

    /** 부여 가능한 능력 목록 — 화면이 체크박스를 그릴 때 쓰는 **유일한 출처**. */
    fun grantableActions(): List<String> = IamActions.ORG_ROLE_GRANTABLE_ACTIONS

    /**
     * 사용자가 **실제로 보유한** 커스텀 역할 이름을 org 별로 모은다 — 토큰 `roles` 클레임 전용 조회.
     *
     * ★여기서도 인라인 정책 마커([ROLE_POLICY_NAME])로 거른다. 플랫폼 관리자가 만든 org 그룹은
     * 조직이 정의한 역할이 아니고, 그 이름이 연동 서비스의 인가 키가 되어서는 안 된다.
     *
     * ★**taspa 자신의 인가는 이 값을 절대 쓰지 않는다.** 인가는 항상 DB 의 정책을 읽는 IAM 엔진이
     * 판정한다. 이 메서드의 결과는 토큰에 실려 나가는 **연동 서비스용 힌트**일 뿐이라, 회수하면
     * 서버 쪽 권한은 즉시 사라지지만 이미 발급된 토큰의 클레임은 만료까지 남는다(문서에 명시).
     */
    @Transactional(readOnly = true)
    fun roleNamesByOrg(userId: UUID): Map<UUID, List<String>> {
        val groupIds = groupMemberRepository.findByUserId(userId).map { it.groupId }
        if (groupIds.isEmpty()) return emptyMap()
        val roleGroupIds =
            inlineRepository
                .findByPrincipalTypeAndNameAndPrincipalIdIn(IamPrincipalType.GROUP, ROLE_POLICY_NAME, groupIds)
                .map { it.principalId }
                .toSet()
        if (roleGroupIds.isEmpty()) return emptyMap()
        return groupRepository
            .findAllById(roleGroupIds)
            .mapNotNull { group -> group.orgId?.let { it to group.name } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, names) -> names.sorted() }
    }

    @Transactional
    fun create(
        orgId: UUID,
        request: SaveOrgRoleRequest,
        actorId: UUID,
    ): OrgRoleDetailView {
        organizationService.requireActiveOrg(orgId)
        val name = normalizeName(request.name)
        val actions = validateActions(request.actions)
        if (groupRepository.findByOrgIdAndName(orgId, name) != null) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "같은 이름의 역할이 이미 있습니다")
        }
        val group =
            groupRepository.save(
                IamPrincipalGroup(name = name, orgId = orgId, description = normalizeDescription(request.description)),
            )
        inlineRepository.save(
            IamInlinePolicy(
                principalType = IamPrincipalType.GROUP,
                principalId = group.id!!,
                name = ROLE_POLICY_NAME,
                document = policyParser.serialize(buildDocument(orgId, actions)),
            ),
        )
        audit(orgId, actorId, "ORG_ROLE_CREATED", group.id, name, actions)
        return detail(orgId, group.id)
    }

    @Transactional
    fun update(
        orgId: UUID,
        roleId: UUID,
        request: SaveOrgRoleRequest,
        actorId: UUID,
    ): OrgRoleDetailView {
        organizationService.requireActiveOrg(orgId)
        val group = requireRole(orgId, roleId)
        val name = normalizeName(request.name)
        val actions = validateActions(request.actions)
        groupRepository.findByOrgIdAndName(orgId, name)?.let {
            if (it.id != roleId) throw AuthException(ErrorCode.VALIDATION_ERROR, "같은 이름의 역할이 이미 있습니다")
        }
        group.name = name
        group.description = normalizeDescription(request.description)
        groupRepository.save(group)

        val policy = rolePolicyOf(roleId)!!
        policy.document = policyParser.serialize(buildDocument(orgId, actions))
        inlineRepository.save(policy)
        audit(orgId, actorId, "ORG_ROLE_UPDATED", roleId, name, actions)
        return detail(orgId, roleId)
    }

    @Transactional
    fun delete(
        orgId: UUID,
        roleId: UUID,
        actorId: UUID,
    ) {
        organizationService.requireActiveOrg(orgId)
        val group = requireRole(orgId, roleId)
        // ★그룹 멤버(`iam_group_members`)만 CASCADE 로 정리된다. `iam_inline_policies` 에는 principal 을
        //   가리키는 FK 가 없으므로(V27) 명시로 지우지 않으면 **고아 정책 행이 남는다**.
        rolePolicyOf(roleId)?.let { inlineRepository.delete(it) }
        groupRepository.delete(group)
        audit(orgId, actorId, "ORG_ROLE_DELETED", roleId, group.name, emptyList())
    }

    /**
     * 구성원에게 역할 부여/해제.
     *
     * ★**그 조직의 활성 구성원에게만** 부여할 수 있다. 이 검사가 없으면 조직관리자가 임의 userId 로
     * 남의 조직 사람에게 자기 조직 역할을 심을 수 있다 — 그 사람은 이 조직 자원에 접근하게 된다.
     */
    @Transactional
    fun assign(
        orgId: UUID,
        roleId: UUID,
        userId: UUID,
        actorId: UUID,
    ) {
        organizationService.requireActiveOrg(orgId)
        requireRole(orgId, roleId)
        if (!organizationService.isActiveMember(orgId, userId)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이 조직의 활성 구성원에게만 역할을 줄 수 있습니다")
        }
        if (groupMemberRepository.findByGroupIdAndUserId(roleId, userId) == null) {
            groupMemberRepository.save(IamGroupMember(groupId = roleId, userId = userId))
        }
        auditEventService.record(
            "ORG_ROLE_ASSIGNED",
            actorId,
            orgId,
            mapOf("orgId" to orgId.toString(), "roleId" to roleId.toString(), "userId" to userId.toString()),
        )
    }

    @Transactional
    fun unassign(
        orgId: UUID,
        roleId: UUID,
        userId: UUID,
        actorId: UUID,
    ) {
        organizationService.requireActiveOrg(orgId)
        requireRole(orgId, roleId)
        groupMemberRepository.deleteByGroupIdAndUserId(roleId, userId)
        auditEventService.record(
            "ORG_ROLE_UNASSIGNED",
            actorId,
            orgId,
            mapOf("orgId" to orgId.toString(), "roleId" to roleId.toString(), "userId" to userId.toString()),
        )
    }

    // ---- 내부 ----

    /**
     * 서버가 만드는 정책 문서. 조직관리자가 고른 것은 **action 목록뿐**이고, 리소스와 조건은 여기서 박는다.
     *
     * 리소스 글롭 `trn:taspa:*:{org}:*` 과 `taspa:ResourceOrg` 정확일치는 `LegacyPolicyBridge.orgScoped`
     * 와 **같은 형태**다 — 브리지가 만드는 ORG_ADMIN 정책과 같은 방식으로 격리된다는 뜻이고,
     * 그래서 테넌시 동작을 따로 추론할 필요가 없다.
     */
    private fun buildDocument(
        orgId: UUID,
        actions: List<String>,
    ) = PolicyDocument(
        version = POLICY_VERSION,
        statements =
            listOf(
                Statement(
                    sid = "OrgCustomRole",
                    effect = Effect.ALLOW,
                    actions = actions,
                    resources = listOf("trn:taspa:*:$orgId:*"),
                    conditions =
                        listOf(
                            Condition("StringEquals", PolicyEvaluator.RESOURCE_ORG_KEY, listOf(orgId.toString())),
                        ),
                ),
            ),
    )

    /**
     * 고른 action 검증 — **화이트리스트 교집합이 아니라 거부**다.
     *
     * 모르는 action 을 조용히 버리면 화면은 "권한을 줬다"고 믿는데 실제로는 안 준 상태가 되고,
     * 그 차이를 아무도 모른다. 그래서 목록 밖 값이 하나라도 있으면 요청 전체를 400 으로 거절한다.
     */
    private fun validateActions(actions: List<String>): List<String> {
        val cleaned = actions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "역할에는 능력을 하나 이상 선택해야 합니다")
        }
        val grantable = IamActions.ORG_ROLE_GRANTABLE_ACTIONS.toSet()
        val rejected = cleaned.filterNot { it in grantable }
        if (rejected.isNotEmpty()) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "조직 역할에 부여할 수 없는 권한입니다: ${rejected.joinToString(", ")}",
            )
        }
        return cleaned
    }

    /**
     * 설명 길이 검증. 없으면 DB 컬럼(VARCHAR(512)) 상한을 넘겼을 때 400 이 아니라
     * **409 '요청이 기존 데이터와 충돌합니다'** 라는 무관한 오류가 나가 원인을 짐작할 수 없다.
     */
    private fun normalizeDescription(raw: String?): String? {
        val value = raw?.trim()?.ifBlank { null } ?: return null
        if (value.length > MAX_DESCRIPTION) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "설명은 ${MAX_DESCRIPTION}자 이하여야 합니다")
        }
        return value
    }

    private fun normalizeName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 128) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "역할 이름은 1~128자여야 합니다")
        }
        // ★쉼표 금지 — 연동 클라이언트의 선언 목록이 쉼표 구분 문자열이라(TokenCustomizerConfig),
        //   쉼표가 든 이름은 조용히 여러 선언으로 쪼개져 **영영 매칭되지 않거나 남의 이름과 겹친다**.
        //   "만들 수는 있는데 선언할 수 없는 역할"이 생기지 않도록 만드는 쪽에서 막는다.
        if (name.contains(',')) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "역할 이름에는 쉼표를 쓸 수 없습니다")
        }
        return name
    }

    /** org 소속 + **이 서비스가 만든 역할**(인라인 정책 마커)일 때만 통과. 그 외는 404 로 수렴한다. */
    private fun requireRole(
        orgId: UUID,
        roleId: UUID,
    ): IamPrincipalGroup {
        organizationService.requireOrg(orgId)
        val group =
            groupRepository.findById(roleId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "역할을 찾을 수 없습니다")
        if (group.orgId != orgId || rolePolicyOf(roleId) == null) {
            // 타 org 의 역할·플랫폼 그룹은 **존재조차 알리지 않는다**(열거 방지).
            throw AuthException(ErrorCode.NOT_FOUND, "역할을 찾을 수 없습니다")
        }
        return group
    }

    private fun rolePolicyOf(groupId: UUID): IamInlinePolicy? =
        inlineRepository.findByPrincipalTypeAndPrincipalIdAndName(
            IamPrincipalType.GROUP,
            groupId,
            ROLE_POLICY_NAME,
        )

    private fun actionsOf(document: String): List<String> =
        runCatching { policyParser.parse(document).statements.flatMap { it.actions } }.getOrDefault(emptyList())

    private fun audit(
        orgId: UUID,
        actorId: UUID,
        type: String,
        roleId: UUID,
        name: String,
        actions: List<String>,
    ) {
        auditEventService.record(
            type,
            actorId,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "roleId" to roleId.toString(),
                "name" to name,
                "actions" to actions,
            ),
        )
    }

    companion object {
        /**
         * 이 서비스가 만든 역할임을 나타내는 인라인 정책 이름 — **플랫폼이 만든 org 그룹과 구별하는 유일한 표지**.
         * 이 이름이 없는 그룹은 조직 콘솔이 건드리지 않는다(손으로 쓴 정책을 체크박스 편집이 덮지 않게).
         */
        const val ROLE_POLICY_NAME = "taspa:org-role"

        /** 정책 문서 스키마 라벨 — 브리지가 쓰는 값과 같아야 파서 기대가 갈리지 않는다. */
        const val POLICY_VERSION = "2026-07-25"

        /** `iam_principal_groups.description` 컬럼 상한과 같아야 한다(V27). */
        private const val MAX_DESCRIPTION = 512
    }
}
