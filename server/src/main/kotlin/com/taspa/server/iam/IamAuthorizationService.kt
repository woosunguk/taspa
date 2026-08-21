package com.taspa.server.iam

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * **인가 판정 서비스 — 이 시스템의 유일한 인가 권위.**
 *
 * `decide*` 는 principal 의 유효 정책(저장 정책 + 역할/스코프 브리지)으로 결정론적 판정을 내리고,
 * `authorize` 는 그 판정을 집행 결과(허용/거부)로 바꾼다. 인가 지점은 이 서비스만 호출하며,
 * 어디에도 별도의 allow/deny 계산이 남아 있지 않다(레거시 이중 판정 제거 완료).
 *
 * 판정 불가(정책 손상·저장소 장애)는 **거부**다 — 결정하지 못한 요청을 통과시키면 그것이 우회 경로다.
 *
 * 엔진 밖에 남는 가드(제거하지 말 것): CSRF(요청 위조 방지 — 인가가 아니다), step-up 재인증 인터셉터,
 * 행 단위 소유권 필터(org 앵커가 없어 TRN 을 구성할 수 없는 표면).
 */

/**
 * 부서 위임 앵커. `orgId`/`departmentId` 둘 다 `UUID` 라 위치 인자로 넘기면 뒤바뀌어도 컴파일러가
 * 잡아 주지 않는다 — 인가 입력에서 그런 실수는 "다른 부서의 권한"이 된다. 타입으로 묶어 둔다.
 */
data class DepartmentDelegationAnchor(
    val orgId: UUID,
    val departmentId: UUID,
)

@Service
class IamAuthorizationService(
    private val evaluator: PolicyEvaluator,
    private val resolver: PrincipalPolicyResolver,
    private val bridge: LegacyPolicyBridge,
) {
    private val log = LoggerFactory.getLogger(IamAuthorizationService::class.java)

    /** 기동 시 무엇이 인가를 집행하는지 남긴다 — 운영자가 로그만 보고 알아야 하는 1급 정보다. */
    @jakarta.annotation.PostConstruct
    fun logAuthority() {
        log.info("IAM policy engine: 인가 판정 권위(판정 불가 시 거부)")
    }

    // ── 결정론적 판정 ──────────────────────────────────────────────────

    /**
     * 세션 사용자 판정. platformAdmin/orgAdminOf/memberOf 는 이 요청에 유효한 레거시 신호이며,
     * 저장된 사용자 정책과 합쳐 평가한다.
     *
     * ★호출자 계약(필수): orgAdminOf/memberOf 는 반드시 **요청 경로의 org** 에 대해
     * `OrganizationService.isOrgAdmin(orgId, userId)` / `isActiveMember(orgId, userId)` 로 도출해야 한다
     * (이 두 함수가 org SUSPENDED·멤버십 비활성을 함께 접는다). "어딘가의 관리자" 같은 원시 플래그를
     * 넘기면 정지된 조직·타 조직 권한이 새어 들어온다. merchantAdminOf 도 같은 계약이다 —
     * **요청 경로의 가맹점**에 대한 활성 멤버십일 때만 넘긴다.
     */
    fun decideSession(
        userId: UUID,
        platformAdmin: Boolean,
        orgAdminOf: UUID?,
        memberOf: UUID?,
        request: AuthorizationRequest,
        merchantAdminOf: UUID? = null,
        departmentDelegation: DepartmentDelegationAnchor? = null,
    ): Decision {
        val policies =
            buildList {
                addAll(resolver.resolveUserPolicies(userId))
                if (platformAdmin) add(bridge.platformAdmin())
                if (orgAdminOf != null) add(bridge.orgAdmin(orgAdminOf))
                // ORG_ADMIN 은 멤버 권한을 포함하므로(orgAdmin 문서에 이미 포함) 중복 add 방지.
                if (memberOf != null && memberOf != orgAdminOf) add(bridge.orgMember(memberOf))
                if (merchantAdminOf != null) add(bridge.merchantAdmin(merchantAdminOf))
                // 부서 위임은 ORG_ADMIN 과 **상호배제**(부여 시점에 강제)라 여기서 둘이 함께 오지 않는다.
                // 그래도 방어적으로 조건을 걸지 않는다 — 함께 와도 Deny 우선 규칙이 위임의 제외 목록을
                // 살려 두는 편이 안전하고, 조건을 걸면 그 판단이 두 곳에 생긴다.
                departmentDelegation?.let { add(bridge.departmentDelegate(it.orgId, it.departmentId)) }
            }
        return evaluator.evaluate(policies, request)
    }

    /**
     * M2M 클라이언트 판정 — 저장 정책 없음, scope+앵커 브리지만 사용.
     * scimOrg 는 SCIM 전용 단일 org 앵커(org_id 클레임)로 boundOrgs 와 분리한다.
     */
    fun decideM2m(
        scopes: Set<String>,
        boundOrgs: Set<UUID>,
        merchantId: UUID?,
        request: AuthorizationRequest,
        scimOrg: UUID? = null,
    ): Decision = evaluator.evaluate(bridge.m2m(scopes, boundOrgs, merchantId, scimOrg), request)

    /**
     * **위임 토큰(authorization_code) 판정 — 신원 권한 ∩ 위임 경계.**
     *
     * 사용자를 대신하는 토큰은 "그 사용자가 할 수 있는 것"과 "그 토큰이 동의받은 것"의 **교집합**만 할 수 있다.
     * AWS permission boundary 와 같은 의미론이며, 이 교집합이 두 가지를 동시에 막는다:
     *  - 동의(scope) 없는 능력 행사 — org 멤버라도 제3자 앱이 동의 밖 API 를 쓰지 못한다.
     *  - 기계 전용 능력(SCIM 프로비저닝·장부 적재·예측 조회) — 경계가 애초에 포함하지 않는다.
     *
     * 경계가 비면(위임 가능한 scope 가 하나도 없으면) 무조건 거부다 — 신원 권한이 아무리 넓어도 통과 못 한다.
     */
    fun decideDelegated(
        userId: UUID,
        platformAdmin: Boolean,
        orgAdminOf: UUID?,
        memberOf: UUID?,
        scopes: Set<String>,
        request: AuthorizationRequest,
    ): Decision {
        val identity = decideSession(userId, platformAdmin, orgAdminOf, memberOf, request)
        if (!identity.isAllowed) return identity
        val boundary = bridge.delegationBoundary(scopes)
        if (boundary.isEmpty()) {
            return Decision(DecisionEffect.DENY, "outside delegation boundary (no delegatable scope)", null)
        }
        val boundaryDecision = evaluator.evaluate(boundary, request)
        return if (boundaryDecision.isAllowed) {
            identity
        } else {
            Decision(DecisionEffect.DENY, "outside delegation boundary (scope)", null)
        }
    }

    // ── 집행 권위 API ───────────────────────────────────────────────────

    /**
     * **최종 인가 API — 엔진이 유일한 판정자.** 레거시 판정 입력이 없다.
     *
     * 판정 계산이 실패하면(정책 손상·저장소 장애) **거부**한다 — 인가 엔진이 결정하지 못했는데 통과시키면
     * 그 자체가 우회 경로다. 예외를 밖으로 던지지 않는 이유는 500 이 아니라 403 이 인가 실패의 정직한 표현이고,
     * 호출부가 조립 예외까지 신경 쓰지 않아도 fail-closed 가 보장되게 하기 위해서다.
     *
     * @return true 면 허용. 호출부는 false 일 때 표면에 맞는 거부 응답(예외)을 던진다.
     */
    fun authorize(
        request: AuthorizationRequest,
        label: String,
        decide: () -> Decision,
    ): Boolean {
        val decision =
            runCatching(decide).getOrElse { error ->
                log.error("IAM decision failed [{}] action={}: {}", label, request.action, error.message, error)
                return false
            }
        if (!decision.isAllowed) {
            log.debug("IAM deny [{}] action={} resource={} reason={}", label, request.action, request.resource, decision.reason)
        }
        return decision.isAllowed
    }
}
