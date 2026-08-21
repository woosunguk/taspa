package com.taspa.server.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 부서 위임 정책의 **엔진 수준** 증거.
 *
 * 통합 테스트가 "위임자가 승격을 못 한다"를 보여 주긴 하지만, 그건 명시 Deny 없이 **암묵적 거부**만으로도
 * 같은 결과가 나온다 — 즉 Deny 가 실제로 일하는지는 증명되지 않는다. 여기서는 Deny 를 이기려는 Allow 를
 * 일부러 함께 넣어 놓고도 거부되는지, 그리고 스코프 키가 호출자에 의해 위조되지 않는지를 직접 본다.
 */
class DelegationPolicyTest {
    private val bridge = LegacyPolicyBridge()
    private val evaluator = PolicyEvaluator()

    private val orgId = UUID.randomUUID()
    private val hq = UUID.randomUUID()
    private val team = UUID.randomUUID()
    private val otherHq = UUID.randomUUID()

    /** `/hq/team/` — 위임 대상 부서(team)와 그 상위(hq)를 포함하는 경로. */
    private val teamPath = ResourceScope.ofDepartmentChain(listOf(hq, team))
    private val otherPath = ResourceScope.ofDepartmentChain(listOf(otherHq))

    private fun request(
        action: String,
        scope: ResourceScope,
        context: Map<String, String> = emptyMap(),
    ) = AuthorizationRequest(
        action = action,
        resource = Trn.member(orgId, UUID.randomUUID()),
        context = context + (IamContextKeys.PRINCIPAL_TYPE to IamPrincipalKind.SESSION.name),
        scope = scope,
    )

    @Test
    fun `위임받은 부서 경로에 있는 자원은 허용된다`() {
        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                request(IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES, teamPath),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.ALLOW)
    }

    @Test
    fun `상위 부서를 위임받으면 하위 경로도 허용된다(서브트리)`() {
        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, hq)),
                request(IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES, teamPath),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.ALLOW)
    }

    @Test
    fun `다른 부서 경로는 거부된다`() {
        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                request(IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES, otherPath),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.DENY)
    }

    @Test
    fun `★부서 축이 없는 자원은 거부된다 — 스코프를 채우지 않으면 닫히는 쪽으로 실패한다`() {
        // 새 표면을 추가하면서 스코프 주입을 잊었을 때 열리면 안 된다. 양성 조건 + 키 부재 →
        // Allow 미적용(ConditionEvaluator 의 fail-safe 편향)이 그것을 보장한다.
        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                request(IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES, ResourceScope.NONE),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.DENY)
    }

    @Test
    fun `★★명시 Deny 는 그것을 허용하는 Allow 가 함께 있어도 이긴다`() {
        // 이 테스트가 없으면 "Deny 를 넣었다"는 사실이 검증되지 않는다 — 열거에서 빠진 action 은
        // 암묵적 거부로도 막히기 때문이다. 위임자가 나중에 별도 정책·그룹으로 넓은 Allow 를 받는
        // 상황을 가정해 Deny 가 그것을 이기는지 본다.
        val broadAllow =
            PolicyDocument(
                PolicyParser.DEFAULT_VERSION,
                listOf(Statement(sid = "Oops", effect = Effect.ALLOW, actions = listOf("*"), resources = listOf("*"))),
            )
        IamActions.DEPARTMENT_DELEGATE_DENIED_ACTIONS.forEach { action ->
            val decision =
                evaluator.evaluate(
                    listOf(broadAllow, bridge.departmentDelegate(orgId, team)),
                    request(action, teamPath),
                )
            assertThat(decision.effect)
                .describedAs("위임자에게 금지된 action 이 넓은 Allow 로 열렸다: %s", action)
                .isEqualTo(DecisionEffect.DENY)
        }
    }

    @Test
    fun `★★호출자가 스코프 조건키를 컨텍스트에 지어 넣어도 무시된다`() {
        // ★공격의 실제 형태: **스코프가 비어 있는 요청**에 위조 키를 심는다.
        //   스코프가 채워진 요청이었다면 파생값이 덮어쓰므로 위조가 무의미하다 — 즉 "덮어쓰기"만으로는
        //   이 경로가 막히지 않고, 평가 전 **제거**(SCOPE_OWNED_KEYS)가 유일한 방어다.
        //   (이 구분을 놓치면 테스트가 통과해도 아무것도 증명하지 못한다.)
        val forged = mapOf(IamContextKeys.RESOURCE_DEPARTMENT_PATH to teamPath.departmentPath!!)

        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                // 부서 축이 없는 자원(스코프 미주입) + 위조된 경로 키.
                request(IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES, ResourceScope.NONE, forged),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.DENY)
    }

    @Test
    fun `대조군 — 스코프가 채워진 요청은 파생값이 위조 키를 덮어쓴다`() {
        // 위 테스트가 무엇을 증명하는지 분명히 하기 위한 짝. 여기서는 제거가 없어도 통과하므로,
        // 이 케이스만 있었다면 제거 로직을 지워도 초록불이었을 것이다.
        val forged = mapOf(IamContextKeys.RESOURCE_DEPARTMENT_PATH to teamPath.departmentPath!!)

        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                request(IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES, otherPath, forged),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.DENY)
    }

    @Test
    fun `★호출자가 ResourceOrg 를 지어 넣어도 무시된다(기존 테넌시 보증이 유지된다)`() {
        val otherOrg = UUID.randomUUID()
        val forged = mapOf(PolicyEvaluator.RESOURCE_ORG_KEY to orgId.toString())

        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                AuthorizationRequest(
                    action = IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES,
                    // 자원은 **다른 org** 의 것 — 컨텍스트의 위조된 org 로는 넘어오지 못한다.
                    resource = Trn.member(otherOrg, UUID.randomUUID()),
                    context = forged,
                    scope = teamPath,
                ),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.DENY)
    }

    @Test
    fun `다른 조직의 같은 부서 id 로는 넘어오지 못한다(두 조건이 AND 다)`() {
        val otherOrg = UUID.randomUUID()
        val decision =
            evaluator.evaluate(
                listOf(bridge.departmentDelegate(orgId, team)),
                AuthorizationRequest(
                    action = IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES,
                    resource = Trn.member(otherOrg, UUID.randomUUID()),
                    context = emptyMap(),
                    scope = teamPath,
                ),
            )
        assertThat(decision.effect).isEqualTo(DecisionEffect.DENY)
    }

    @Test
    fun `위임 허용 목록과 금지 목록은 겹치지 않는다`() {
        // 겹치면 Deny 가 이겨서 그 action 은 영원히 죽은 권한이 된다 — 목록을 손댈 때의 사고를 잡는다.
        assertThat(IamActions.DEPARTMENT_DELEGATE_ACTIONS)
            .doesNotContainAnyElementsOf(IamActions.DEPARTMENT_DELEGATE_DENIED_ACTIONS)
    }

    @Test
    fun `위임 목록에 역할 변경·초대·위임 부여가 들어 있지 않다`() {
        // 자기 증식 경로 3종. 실수로 추가되면 여기서 걸린다.
        assertThat(IamActions.DEPARTMENT_DELEGATE_ACTIONS).doesNotContain(
            IamActions.ORG_CHANGE_MEMBER_ROLE,
            IamActions.ORG_CREATE_INVITATION,
            IamActions.ORG_MANAGE_DELEGATION,
        )
    }

    @Test
    fun `부서 경로는 앞뒤 구분자를 포함한다(끝 부서가 글롭에 걸리게)`() {
        val path = ResourceScope.ofDepartmentChain(listOf(hq, team)).departmentPath!!
        assertThat(path).startsWith("/").endsWith("/")
        assertThat(path).isEqualTo("/$hq/$team/")
        // 구분자가 없으면 마지막 부서가 서브트리 글롭에 걸리지 않는다 — 그게 이 형식의 존재 이유다.
        assertThat(PolicyMatching.globMatches(ResourceScope.subtreeGlob(team), path)).isTrue()
        assertThat(PolicyMatching.globMatches(ResourceScope.subtreeGlob(team), "/$hq/$team")).isFalse()
    }

    @Test
    fun `빈 체인은 경로가 아니라 키 부재로 수렴한다`() {
        assertThat(ResourceScope.ofDepartmentChain(emptyList())).isEqualTo(ResourceScope.NONE)
        assertThat(ResourceScope.NONE.departmentPath).isNull()
    }
}
