package com.taspa.server.iam

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.iam.IamGroupMember
import com.taspa.server.domain.iam.IamGroupMemberRepository
import com.taspa.server.domain.iam.IamInlinePolicy
import com.taspa.server.domain.iam.IamInlinePolicyRepository
import com.taspa.server.domain.iam.IamPolicy
import com.taspa.server.domain.iam.IamPolicyAttachment
import com.taspa.server.domain.iam.IamPolicyAttachmentRepository
import com.taspa.server.domain.iam.IamPolicyRepository
import com.taspa.server.domain.iam.IamPrincipalGroup
import com.taspa.server.domain.iam.IamPrincipalGroupRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.dto.AttachmentRequest
import com.taspa.server.iam.dto.GroupCreateRequest
import com.taspa.server.iam.dto.GroupMemberView
import com.taspa.server.iam.dto.GroupView
import com.taspa.server.iam.dto.PolicyCreateRequest
import com.taspa.server.iam.dto.PolicyUpdateRequest
import com.taspa.server.iam.dto.PolicyView
import com.taspa.server.iam.dto.PrincipalPolicyView
import com.taspa.server.iam.dto.SimulateRequest
import com.taspa.server.iam.dto.SimulateResponse
import com.taspa.server.iam.dto.SimulateSubjectType
import com.taspa.server.org.OrganizationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * IAM 정책 관리 계층(관리 콘솔/API 백엔드). 코어 정책 엔진(PolicyParser/PolicyEvaluator/IamAuthorizationService)은
 * 그대로 사용하고, 이 서비스는 정책·그룹·부착·inline 의 CRUD 와 시뮬레이터(판정 미리보기)만 제공한다.
 *   - systemManaged 정책/그룹은 편집·삭제 거부(IAM_POLICY_IMMUTABLE, AWS managed policy 대응).
 *   - 정책 문서는 저장 전 parser.parse + parser.validate(실패 → IAM_VALIDATION).
 *   - 이름 충돌은 org 스코프 유니크(전역/조직) 위반 → IAM_CONFLICT.
 */
@Service
@Transactional
class IamPolicyService(
    private val policyRepository: IamPolicyRepository,
    private val groupRepository: IamPrincipalGroupRepository,
    private val groupMemberRepository: IamGroupMemberRepository,
    private val attachmentRepository: IamPolicyAttachmentRepository,
    private val inlineRepository: IamInlinePolicyRepository,
    private val parser: PolicyParser,
    private val iamShadowService: IamAuthorizationService,
    private val userRepository: UserRepository,
    private val organizationRepository: OrganizationRepository,
    private val organizationService: OrganizationService,
    private val lockoutGuard: IamLockoutGuard,
) {
    // ── 정책 CRUD ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun listPolicies(orgId: UUID?): List<PolicyView> =
        (if (orgId == null) policyRepository.findByOrgIdIsNull() else policyRepository.findByOrgId(orgId))
            .map { it.toView() }

    @Transactional(readOnly = true)
    fun getPolicy(id: UUID): PolicyView = findPolicy(id).toView()

    fun createPolicy(request: PolicyCreateRequest): PolicyView {
        val name = request.name.trim()
        if (name.isEmpty()) throw AuthException(ErrorCode.IAM_VALIDATION, "정책 이름이 비어있습니다")
        parseAndValidate(request.document)
        requireOrgExists(request.orgId)
        val duplicate =
            if (request.orgId == null) {
                policyRepository.findByNameAndOrgIdIsNull(name)
            } else {
                policyRepository.findByNameAndOrgId(name, request.orgId)
            }
        if (duplicate != null) throw AuthException(ErrorCode.IAM_CONFLICT)
        val saved =
            policyRepository.save(
                IamPolicy(
                    name = name,
                    orgId = request.orgId,
                    description = request.description?.trim()?.ifEmpty { null },
                    document = request.document,
                    systemManaged = false,
                ),
            )
        return saved.toView()
    }

    fun updatePolicy(
        id: UUID,
        request: PolicyUpdateRequest,
    ): PolicyView {
        val policy = findPolicy(id)
        if (policy.systemManaged) throw AuthException(ErrorCode.IAM_POLICY_IMMUTABLE)
        parseAndValidate(request.document)
        policy.document = request.document
        policy.description = request.description?.trim()?.ifEmpty { null }
        policy.updatedAt = Instant.now()
        val view = policyRepository.save(policy).toView()
        lockoutGuard.verifyRecoverable()
        return view
    }

    /** 정책 삭제 — 부착 행은 iam_policy_attachments.policy_id FK(ON DELETE CASCADE)로 함께 정리된다. */
    fun deletePolicy(id: UUID): PolicyView {
        val policy = findPolicy(id)
        if (policy.systemManaged) throw AuthException(ErrorCode.IAM_POLICY_IMMUTABLE)
        val view = policy.toView()
        policyRepository.delete(policy)
        lockoutGuard.verifyRecoverable()
        return view
    }

    // ── 그룹 CRUD + 멤버십 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun listGroups(orgId: UUID?): List<GroupView> =
        (if (orgId == null) groupRepository.findByOrgIdIsNull() else groupRepository.findByOrgId(orgId))
            .map { it.toView() }

    fun createGroup(request: GroupCreateRequest): GroupView {
        val name = request.name.trim()
        if (name.isEmpty()) throw AuthException(ErrorCode.IAM_VALIDATION, "그룹 이름이 비어있습니다")
        requireOrgExists(request.orgId)
        val duplicate =
            if (request.orgId == null) {
                groupRepository.findByNameAndOrgIdIsNull(name)
            } else {
                groupRepository.findByNameAndOrgId(name, request.orgId)
            }
        if (duplicate != null) throw AuthException(ErrorCode.IAM_CONFLICT)
        val saved =
            groupRepository.save(
                IamPrincipalGroup(
                    name = name,
                    orgId = request.orgId,
                    description = request.description?.trim()?.ifEmpty { null },
                    systemManaged = false,
                ),
            )
        return saved.toView()
    }

    /**
     * 그룹 삭제 — 멤버십은 iam_group_members.group_id FK(ON DELETE CASCADE)로 함께 정리된다.
     * 그룹이 나르던 Allow 도 함께 사라지므로 락아웃 가드 대상이다.
     */
    fun deleteGroup(id: UUID): GroupView {
        val group = findGroup(id)
        if (group.systemManaged) throw AuthException(ErrorCode.IAM_POLICY_IMMUTABLE)
        val view = group.toView()
        groupRepository.delete(group)
        lockoutGuard.verifyRecoverable()
        return view
    }

    @Transactional(readOnly = true)
    fun listGroupMembers(groupId: UUID): List<GroupMemberView> {
        findGroup(groupId)
        return groupMemberRepository.findByGroupId(groupId).map { member ->
            val email = userRepository.findById(member.userId).orElse(null)?.email
            GroupMemberView(member.userId, email, member.createdAt)
        }
    }

    /** 멤버 추가 — 멱등(이미 존재하면 no-op). 그룹·유저 존재를 검증한다. */
    fun addGroupMember(
        groupId: UUID,
        userId: UUID,
    ) {
        findGroup(groupId)
        requireUserExists(userId)
        if (groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) return
        groupMemberRepository.save(IamGroupMember(groupId = groupId, userId = userId))
        lockoutGuard.verifyRecoverable()
    }

    fun removeGroupMember(
        groupId: UUID,
        userId: UUID,
    ) {
        findGroup(groupId)
        groupMemberRepository.deleteByGroupIdAndUserId(groupId, userId)
        lockoutGuard.verifyRecoverable()
    }

    // ── 부착 · inline ────────────────────────────────────────────────────

    /** managed 정책을 principal 에 부착 — 멱등(중복 부착 무시). 정책·principal 존재를 검증한다. */
    fun attach(request: AttachmentRequest) {
        val policy = findPolicy(request.policyId)
        requirePrincipalExists(request.principalType, request.principalId)
        requireOrgConsistency(policy, request)
        if (attachmentRepository.existsByPolicyIdAndPrincipalTypeAndPrincipalId(
                request.policyId,
                request.principalType,
                request.principalId,
            )
        ) {
            return
        }
        attachmentRepository.save(
            IamPolicyAttachment(
                policyId = request.policyId,
                principalType = request.principalType,
                principalId = request.principalId,
            ),
        )
        lockoutGuard.verifyRecoverable()
    }

    /** 부착 해제 — 멱등(존재하지 않아도 조용히 성공). */
    fun detach(request: AttachmentRequest) {
        attachmentRepository.deleteByPolicyIdAndPrincipalTypeAndPrincipalId(
            request.policyId,
            request.principalType,
            request.principalId,
        )
        lockoutGuard.verifyRecoverable()
    }

    /** inline 정책 set(upsert) — principal 별 name 으로 문서를 임베드한다. */
    fun setInlinePolicy(
        principalType: IamPrincipalType,
        principalId: UUID,
        name: String,
        document: String,
    ) {
        requirePrincipalExists(principalType, principalId)
        val policyName = name.trim()
        if (policyName.isEmpty()) throw AuthException(ErrorCode.IAM_VALIDATION, "inline 정책 이름이 비어있습니다")
        parseAndValidate(document)
        val existing = inlineRepository.findByPrincipalTypeAndPrincipalIdAndName(principalType, principalId, policyName)
        if (existing != null) {
            existing.document = document
            existing.updatedAt = Instant.now()
            inlineRepository.save(existing)
        } else {
            inlineRepository.save(
                IamInlinePolicy(
                    principalType = principalType,
                    principalId = principalId,
                    name = policyName,
                    document = document,
                ),
            )
        }
        lockoutGuard.verifyRecoverable()
    }

    fun removeInlinePolicy(
        principalType: IamPrincipalType,
        principalId: UUID,
        name: String,
    ) {
        val existing =
            inlineRepository.findByPrincipalTypeAndPrincipalIdAndName(principalType, principalId, name.trim())
                ?: throw AuthException(ErrorCode.IAM_POLICY_NOT_FOUND)
        inlineRepository.delete(existing)
        lockoutGuard.verifyRecoverable()
    }

    /**
     * principal 의 유효 정책 목록(직접 inline·부착 + USER 는 소속 그룹의 inline·부착). PrincipalPolicyResolver 의
     * 조립을 출처 라벨과 함께 노출한다(관리 콘솔의 "이 principal 에 적용되는 정책" 표시용).
     */
    @Transactional(readOnly = true)
    fun principalPolicies(
        principalType: IamPrincipalType,
        principalId: UUID,
    ): List<PrincipalPolicyView> {
        requirePrincipalExists(principalType, principalId)
        val result = mutableListOf<PrincipalPolicyView>()
        collectInline(principalType, principalId, "inline", result)
        collectAttached(principalType, principalId, "attached", result)
        if (principalType == IamPrincipalType.USER) {
            groupMemberRepository.findByUserId(principalId).forEach { membership ->
                collectInline(IamPrincipalType.GROUP, membership.groupId, "group-inline", result)
                collectAttached(IamPrincipalType.GROUP, membership.groupId, "group-attached", result)
            }
        }
        return result
    }

    // ── 시뮬레이터 ─────────────────────────────────────────────────────────

    /**
     * action+resource 판정 미리보기. 세션 사용자는 role=ADMIN → platformAdmin, orgId 지정 시
     * isOrgAdmin/isActiveMember 로 orgAdminOf/memberOf 를 도출해 decideSession, M2M 은 decideM2m 을 호출한다.
     * context 에 taspa:OrgId·taspa:StepUpPresent 를 채운다(요청 context 가 우선).
     */
    @Transactional(readOnly = true)
    fun simulate(request: SimulateRequest): SimulateResponse {
        val authRequest = AuthorizationRequest(request.action, request.resource, buildContext(request))
        val decision =
            when (request.subjectType) {
                // 위임 토큰(DELEGATED)은 세션과 **같은 정책 집합**으로 평가한다 — 차이는 컨텍스트의
                // taspa:PrincipalType 뿐이며, 그 조건이 서비스 전용 능력으로의 도달을 막는다.
                SimulateSubjectType.SESSION, SimulateSubjectType.DELEGATED -> {
                    val userId =
                        request.userId
                            ?: throw AuthException(ErrorCode.IAM_VALIDATION, "세션 시뮬레이션에는 userId 가 필요합니다")
                    val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
                    val platformAdmin = user.role == UserRole.ADMIN.name
                    val orgAdminOf = request.orgId?.takeIf { organizationService.isOrgAdmin(it, userId) }
                    val memberOf = request.orgId?.takeIf { organizationService.isActiveMember(it, userId) }
                    iamShadowService.decideSession(userId, platformAdmin, orgAdminOf, memberOf, authRequest)
                }
                // scimOrg 를 빠뜨리면 org.scim 시뮬레이션이 실제 집행과 달리 항상 DENY 로 나온다.
                SimulateSubjectType.M2M ->
                    iamShadowService.decideM2m(
                        request.scopes,
                        request.boundOrgs,
                        request.merchantId,
                        authRequest,
                        scimOrg = request.scimOrg,
                    )
            }
        return SimulateResponse(decision.effect.name, decision.reason, decision.matchedSid)
    }

    private fun buildContext(request: SimulateRequest): Map<String, String> {
        val context = request.context.toMutableMap()
        val orgForContext = request.orgId ?: request.boundOrgs.singleOrNull() ?: request.scimOrg
        orgForContext?.let { context.putIfAbsent(IamContextKeys.ORG_ID, it.toString()) }
        context.putIfAbsent(IamContextKeys.STEP_UP_PRESENT, request.stepUp.toString())
        // 주체 종류를 조건키로 노출해야 위임 토큰(confused-deputy) 시나리오를 시뮬레이터로 재현할 수 있다.
        context.putIfAbsent(
            IamContextKeys.PRINCIPAL_TYPE,
            when (request.subjectType) {
                SimulateSubjectType.SESSION -> IamPrincipalKind.SESSION
                SimulateSubjectType.M2M -> IamPrincipalKind.M2M
                SimulateSubjectType.DELEGATED -> IamPrincipalKind.DELEGATED
            }.name,
        )
        return context
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private fun collectInline(
        principalType: IamPrincipalType,
        principalId: UUID,
        source: String,
        sink: MutableList<PrincipalPolicyView>,
    ) {
        inlineRepository.findByPrincipalTypeAndPrincipalId(principalType, principalId).forEach {
            sink += PrincipalPolicyView(source, null, it.name, it.document, statementCount(it.document))
        }
    }

    private fun collectAttached(
        principalType: IamPrincipalType,
        principalId: UUID,
        source: String,
        sink: MutableList<PrincipalPolicyView>,
    ) {
        attachmentRepository.findByPrincipalTypeAndPrincipalId(principalType, principalId).forEach { attachment ->
            policyRepository.findById(attachment.policyId).ifPresent { policy ->
                sink +=
                    PrincipalPolicyView(
                        source,
                        policy.id,
                        policy.name,
                        policy.document,
                        statementCount(policy.document),
                    )
            }
        }
    }

    private fun IamPolicy.toView(): PolicyView = PolicyView.from(this, statementCount(document))

    private fun IamPrincipalGroup.toView(): GroupView = GroupView.from(this, groupMemberRepository.findByGroupId(id!!).size)

    /**
     * 조직 스코프 정책은 **그 조직에 속한 principal 에만** 부착할 수 있다(심층방어).
     *
     * 지금은 `/api/admin/iam` 이 플랫폼 ADMIN 전용이라 노출되지 않지만, IAM 관리를 조직에 위임하는 순간
     * 이 검사가 없으면 조직관리자가 자기 조직 정책을 타 조직 사용자에게 붙여 경계를 넘길 수 있다.
     * (전역 정책(org_id NULL)의 부착은 플랫폼 권한 자체를 나눠주는 행위이므로 **엔드포인트 인가**가
     * 유일한 방어선이다 — 위임 시 반드시 플랫폼 권한을 요구하도록 유지할 것.)
     */
    private fun requireOrgConsistency(
        policy: IamPolicy,
        request: AttachmentRequest,
    ) {
        val policyOrg = policy.orgId ?: return
        when (request.principalType) {
            IamPrincipalType.USER -> {
                if (!organizationService.isActiveMember(policyOrg, request.principalId)) {
                    throw AuthException(ErrorCode.IAM_CONFLICT, "조직 정책은 해당 조직의 활성 멤버에게만 부착할 수 있습니다")
                }
            }
            IamPrincipalType.GROUP -> {
                if (findGroup(request.principalId).orgId != policyOrg) {
                    throw AuthException(ErrorCode.IAM_CONFLICT, "조직 정책은 같은 조직의 그룹에만 부착할 수 있습니다")
                }
            }
        }
    }

    private fun findPolicy(id: UUID): IamPolicy =
        policyRepository.findById(id).orElseThrow { AuthException(ErrorCode.IAM_POLICY_NOT_FOUND) }

    private fun findGroup(id: UUID): IamPrincipalGroup =
        groupRepository.findById(id).orElseThrow { AuthException(ErrorCode.NOT_FOUND, "그룹을 찾을 수 없습니다") }

    private fun parseAndValidate(document: String): PolicyDocument =
        try {
            parser.parse(document).also { parser.validate(it) }
        } catch (e: IllegalArgumentException) {
            throw AuthException(ErrorCode.IAM_VALIDATION, e.message ?: "정책 문서가 유효하지 않습니다")
        }

    private fun statementCount(document: String): Int = runCatching { parser.parse(document).statements.size }.getOrDefault(0)

    private fun requireOrgExists(orgId: UUID?) {
        if (orgId != null && !organizationRepository.existsById(orgId)) {
            throw AuthException(ErrorCode.IAM_VALIDATION, "결속할 조직을 찾을 수 없습니다")
        }
    }

    private fun requireUserExists(userId: UUID) {
        if (!userRepository.existsById(userId)) throw AuthException(ErrorCode.USER_NOT_FOUND)
    }

    private fun requirePrincipalExists(
        principalType: IamPrincipalType,
        principalId: UUID,
    ) {
        val exists =
            when (principalType) {
                IamPrincipalType.USER -> userRepository.existsById(principalId)
                IamPrincipalType.GROUP -> groupRepository.existsById(principalId)
            }
        if (!exists) throw AuthException(ErrorCode.NOT_FOUND, "principal 을 찾을 수 없습니다")
    }
}
