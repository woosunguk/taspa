package com.taspa.server.iam.dto

import com.taspa.server.domain.iam.IamPolicy
import com.taspa.server.domain.iam.IamPrincipalGroup
import com.taspa.server.domain.iam.IamPrincipalType
import java.time.Instant
import java.util.UUID

// ── 정책 ────────────────────────────────────────────────────────────────

data class PolicyCreateRequest(
    val name: String,
    val description: String? = null,
    /** null = 플랫폼 전역 정책, non-null = 조직 관리 정책(존재하는 조직만 허용). */
    val orgId: UUID? = null,
    /** 정책 문서 JSON({Version, Statement[]}). 저장 전 파싱·검증한다. */
    val document: String,
)

data class PolicyUpdateRequest(
    val description: String? = null,
    val document: String,
)

data class PolicyView(
    val id: UUID,
    val name: String,
    val orgId: UUID?,
    val description: String?,
    val document: String,
    val systemManaged: Boolean,
    /** 파싱된 Statement 개수 요약(문서 파손 시 0). */
    val statementCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(
            policy: IamPolicy,
            statementCount: Int,
        ): PolicyView =
            PolicyView(
                id = policy.id!!,
                name = policy.name,
                orgId = policy.orgId,
                description = policy.description,
                document = policy.document,
                systemManaged = policy.systemManaged,
                statementCount = statementCount,
                createdAt = policy.createdAt,
                updatedAt = policy.updatedAt,
            )
    }
}

// ── 그룹 · 멤버십 ─────────────────────────────────────────────────────────

data class GroupCreateRequest(
    val name: String,
    val description: String? = null,
    val orgId: UUID? = null,
)

data class GroupView(
    val id: UUID,
    val name: String,
    val orgId: UUID?,
    val description: String?,
    val systemManaged: Boolean,
    val memberCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(
            group: IamPrincipalGroup,
            memberCount: Int,
        ): GroupView =
            GroupView(
                id = group.id!!,
                name = group.name,
                orgId = group.orgId,
                description = group.description,
                systemManaged = group.systemManaged,
                memberCount = memberCount,
                createdAt = group.createdAt,
                updatedAt = group.updatedAt,
            )
    }
}

data class GroupMemberRequest(
    val userId: UUID,
)

data class GroupMemberView(
    val userId: UUID,
    val email: String?,
    val createdAt: Instant,
)

// ── 부착 · inline · 유효 정책 ─────────────────────────────────────────────

data class AttachmentRequest(
    val policyId: UUID,
    val principalType: IamPrincipalType,
    val principalId: UUID,
)

data class InlinePolicyRequest(
    val document: String,
)

/** principal 의 유효 정책 한 항목. source 로 직접/그룹·inline/managed 출처를 구분한다. */
data class PrincipalPolicyView(
    /** inline | attached | group-inline | group-attached */
    val source: String,
    /** managed 부착이면 정책 id, inline 이면 null. */
    val policyId: UUID?,
    val name: String,
    val document: String,
    val statementCount: Int,
)

// ── 시뮬레이터 ─────────────────────────────────────────────────────────────

/**
 * 시뮬레이션 주체 종류.
 *  - SESSION: 로그인 세션 사용자.
 *  - M2M: client_credentials 서비스 토큰.
 *  - DELEGATED: 사용자를 대신하는 authorization_code 베어러(제3자 앱에 위임된 권한).
 *    SESSION 과 같은 정책 집합으로 평가하되 `taspa:PrincipalType=DELEGATED` 로 구분되므로,
 *    위임 토큰이 서비스 전용 능력에 닿는 confused-deputy 를 시뮬레이터로 재현할 수 있다.
 */
enum class SimulateSubjectType { SESSION, M2M, DELEGATED }

data class SimulateRequest(
    val subjectType: SimulateSubjectType,
    // 세션 사용자 / 위임 토큰
    val userId: UUID? = null,
    val orgId: UUID? = null,
    val stepUp: Boolean = false,
    // M2M 클라이언트
    val scopes: Set<String> = emptySet(),
    val boundOrgs: Set<UUID> = emptySet(),
    val merchantId: UUID? = null,
    /** SCIM 전용 단일 org 앵커(org_id 클레임). 넘기지 않으면 org.scim 시뮬레이션은 항상 DENY 가 된다. */
    val scimOrg: UUID? = null,
    // 공통 질의
    val action: String,
    val resource: String,
    /** 추가 조건키(taspa:OrgId·taspa:StepUpPresent·taspa:PrincipalType 은 서비스가 자동 채운다). */
    val context: Map<String, String> = emptyMap(),
)

data class SimulateResponse(
    /** ALLOW | DENY */
    val effect: String,
    val reason: String,
    val matchedSid: String?,
)
