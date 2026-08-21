package com.taspa.server.iam

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 정책 엔진 순수 단위 테스트(Spring/Testcontainers 없음) — 평가 알고리즘·매칭·조건·파서·레거시 브리지의
 * 핵심 정확성. 특히 (1) 명시적 Deny 우선, (2) 와일드카드/정책변수, (3) 조건 키부재 의미론,
 * (4) 브리지의 테넌시 격리를 못박는다.
 */
class PolicyEngineTest {
    private val evaluator = PolicyEvaluator()
    private val bridge = LegacyPolicyBridge()
    private val parser = PolicyParser(ObjectMapper())

    private fun doc(vararg statements: Statement) = PolicyDocument("t", statements.toList())

    private fun allow(
        action: String,
        resource: String,
        vararg conds: Condition,
    ) = Statement(effect = Effect.ALLOW, actions = listOf(action), resources = listOf(resource), conditions = conds.toList())

    private fun deny(
        action: String,
        resource: String,
        vararg conds: Condition,
    ) = Statement(effect = Effect.DENY, actions = listOf(action), resources = listOf(resource), conditions = conds.toList())

    private fun req(
        action: String,
        resource: String,
        ctx: Map<String, String> = emptyMap(),
    ) = AuthorizationRequest(action, resource, ctx)

    private fun decide(
        policies: List<PolicyDocument>,
        request: AuthorizationRequest,
    ) = evaluator.evaluate(policies, request).isAllowed

    // ── 평가 알고리즘 ──────────────────────────────────────────────────

    @Test fun `no policy is implicit deny`() {
        assertFalse(decide(emptyList(), req("org:UpdateProfile", "trn:taspa:org:x:organization/x")))
    }

    @Test fun `matching allow grants`() {
        assertTrue(
            decide(
                listOf(doc(allow("org:UpdateProfile", "trn:taspa:org:x:*"))),
                req("org:UpdateProfile", "trn:taspa:org:x:organization/x"),
            ),
        )
    }

    @Test fun `explicit deny beats allow regardless of order`() {
        val allowThenDeny = doc(allow("*", "*"), deny("billing:FinalizeInvoice", "*"))
        val denyThenAllow = doc(deny("billing:FinalizeInvoice", "*"), allow("*", "*"))
        assertFalse(decide(listOf(allowThenDeny), req("billing:FinalizeInvoice", "trn:taspa:billing:x:invoice/1")))
        assertFalse(decide(listOf(denyThenAllow), req("billing:FinalizeInvoice", "trn:taspa:billing:x:invoice/1")))
        // 다른 action 은 여전히 허용
        assertTrue(decide(listOf(allowThenDeny), req("billing:ReadInvoice", "trn:taspa:billing:x:invoice/1")))
    }

    @Test fun `deny in one policy overrides allow in another`() {
        val policies = listOf(doc(allow("*", "*")), doc(deny("org:RemoveMember", "*")))
        assertFalse(decide(policies, req("org:RemoveMember", "trn:taspa:org:x:member/1")))
    }

    // ── 액션 매칭 ──────────────────────────────────────────────────────

    @Test fun `action wildcard and case insensitivity`() {
        assertTrue(decide(listOf(doc(allow("org:*", "*"))), req("org:CreateSite", "trn:taspa:org:x:site/1")))
        assertTrue(decide(listOf(doc(allow("*", "*"))), req("meal:Redeem", "trn:taspa:meal::merchant/1")))
        assertTrue(decide(listOf(doc(allow("ORG:updateprofile", "*"))), req("org:UpdateProfile", "trn:taspa:org:x:organization/x")))
        assertFalse(decide(listOf(doc(allow("org:*", "*"))), req("billing:ReadInvoice", "trn:taspa:billing:x:invoice/1")))
    }

    // ── 리소스 글롭 + 정책 변수 ────────────────────────────────────────

    @Test fun `resource glob star and question mark`() {
        assertTrue(PolicyMatching.globMatches("trn:taspa:org:x:*", "trn:taspa:org:x:member/abc"))
        assertTrue(PolicyMatching.globMatches("a?c", "abc"))
        assertFalse(PolicyMatching.globMatches("a?c", "ac"))
    }

    @Test fun `policy variable substitution binds to context org`() {
        val statement = allow("org:UpdateProfile", "trn:taspa:org:\${taspa:OrgId}:*")
        val policies = listOf(doc(statement))
        assertTrue(decide(policies, req("org:UpdateProfile", "trn:taspa:org:A:organization/A", mapOf("taspa:OrgId" to "A"))))
        // context 가 다른 org 를 가리키면 그 org 리소스에만 매치(변수는 요청 org 로 치환됨)
        assertFalse(decide(policies, req("org:UpdateProfile", "trn:taspa:org:A:organization/A", mapOf("taspa:OrgId" to "B"))))
    }

    @Test fun `unresolved policy variable never matches`() {
        // context 에 taspa:OrgId 가 없으면 리터럴로 남아 실제 TRN 과 불일치 → 미부여(안전측)
        assertFalse(
            decide(
                listOf(doc(allow("org:UpdateProfile", "trn:taspa:org:\${taspa:OrgId}:*"))),
                req("org:UpdateProfile", "trn:taspa:org:A:organization/A"),
            ),
        )
    }

    // ── 조건 연산자 ────────────────────────────────────────────────────

    private fun cond(
        op: String,
        key: String,
        vararg values: String,
    ) = Condition(op, key, values.toList())

    /** Allow 문장 맥락(fail-safe 편향 없음)에서의 조건 평가. */
    private fun satisfiedAllow(
        conditions: List<Condition>,
        context: Map<String, String>,
    ) = ConditionEvaluator.satisfied(conditions, context, denyStatement = false)

    /** Deny 문장 맥락(미평가 시 적용 쪽으로 편향)에서의 조건 평가. */
    private fun satisfiedDeny(
        conditions: List<Condition>,
        context: Map<String, String>,
    ) = ConditionEvaluator.satisfied(conditions, context, denyStatement = true)

    @Test fun `condition StringEquals and Bool`() {
        val stmt = allow("billing:FinalizeInvoice", "*", cond("Bool", "taspa:StepUpPresent", "true"))
        assertTrue(
            decide(
                listOf(doc(stmt)),
                req(
                    "billing:FinalizeInvoice",
                    "trn:taspa:billing:x:invoice/1",
                    mapOf(
                        "taspa:StepUpPresent" to "true",
                    ),
                ),
            ),
        )
        assertFalse(
            decide(
                listOf(doc(stmt)),
                req(
                    "billing:FinalizeInvoice",
                    "trn:taspa:billing:x:invoice/1",
                    mapOf(
                        "taspa:StepUpPresent" to "false",
                    ),
                ),
            ),
        )
    }

    @Test fun `missing condition key positive false negative true ifexists true`() {
        val positive = cond("StringEquals", "taspa:OrgId", "A")
        val negative = cond("StringNotEquals", "taspa:OrgId", "A")
        val ifExists = cond("StringEquals:IfExists", "taspa:OrgId", "A")
        assertFalse(satisfiedAllow(listOf(positive), emptyMap()))
        assertTrue(satisfiedAllow(listOf(negative), emptyMap()))
        assertTrue(satisfiedAllow(listOf(ifExists), emptyMap()))
    }

    @Test fun `Null operator tests key presence`() {
        assertTrue(satisfiedAllow(listOf(cond("Null", "k", "true")), emptyMap()))
        assertFalse(satisfiedAllow(listOf(cond("Null", "k", "true")), mapOf("k" to "v")))
        assertTrue(satisfiedAllow(listOf(cond("Null", "k", "false")), mapOf("k" to "v")))
    }

    @Test fun `numeric and string like conditions`() {
        assertTrue(satisfiedAllow(listOf(cond("NumericLessThan", "n", "10")), mapOf("n" to "5")))
        assertFalse(satisfiedAllow(listOf(cond("NumericLessThan", "n", "10")), mapOf("n" to "20")))
        assertTrue(satisfiedAllow(listOf(cond("StringLike", "s", "trn:*:x")), mapOf("s" to "trn:anything:x")))
    }

    // ── 파서 ───────────────────────────────────────────────────────────

    @Test fun `parser roundtrip preserves semantics`() {
        val json =
            """
            {"Version":"2026-07-25","Statement":[
              {"Sid":"s1","Effect":"Allow","Action":["org:UpdateProfile","billing:ReadInvoice"],"Resource":"trn:taspa:org:A:*"},
              {"Effect":"Deny","Action":"*","Resource":"*","Condition":{"StringNotEquals":{"taspa:OrgId":"A"}}}
            ]}
            """.trimIndent()
        val parsed = parser.parse(json)
        assertEquals(2, parsed.statements.size)
        parser.validate(parsed)
        // reparse serialized == same statement count and effects
        val reparsed = parser.parse(parser.serialize(parsed))
        assertEquals(2, reparsed.statements.size)
        assertEquals(Effect.DENY, reparsed.statements[1].effect)
    }

    @Test fun `parser accepts single statement object and string action`() {
        val parsed = parser.parse("""{"Statement":{"Effect":"Allow","Action":"org:*","Resource":"*"}}""")
        assertEquals(1, parsed.statements.size)
        assertEquals(listOf("org:*"), parsed.statements[0].actions)
    }

    @Test fun `validate rejects unknown operator and empty action`() {
        val badOp =
            PolicyDocument(
                "t",
                listOf(
                    Statement(
                        effect = Effect.ALLOW,
                        actions = listOf("org:*"),
                        resources = listOf("*"),
                        conditions = listOf(cond("BogusOp", "k", "v")),
                    ),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) { parser.validate(badOp) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { parser.parse("""{"Statement":[{"Effect":"Allow","Action":[],"Resource":"*"}]}""") }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { parser.parse("""{"Statement":[{"Effect":"Bogus","Action":"*","Resource":"*"}]}""") }
    }

    // ── 레거시 브리지: 테넌시 격리(가장 중요) ──────────────────────────

    private val orgA = UUID.randomUUID()
    private val orgB = UUID.randomUUID()

    @Test fun `platform admin allows org administration across orgs`() {
        val p = listOf(bridge.platformAdmin())
        assertTrue(decide(p, req("org:RemoveMember", Trn.member(orgA, UUID.randomUUID()))))
        assertTrue(decide(p, req("billing:FinalizeInvoice", Trn.invoice(orgB, UUID.randomUUID()))))
        assertTrue(decide(p, req("forecast:Read", Trn.forecast(orgA))))
    }

    @Test fun `platform admin does NOT get membership- or machine-bound powers`() {
        // 레거시가 플랫폼 ADMIN 에게 주지 않는 능력은 엔진도 주면 안 된다(엔진이 레거시보다 강해지는 것 차단).
        val p = listOf(bridge.platformAdmin())
        // 식권 발급은 활성 멤버십 결속 — 허용하면 임의 조직 명의로 실지출이 발생한다.
        assertFalse(decide(p, req("meal:IssueQr", Trn.mealQr(orgA))))
        // 가맹 결제·취소는 merchant M2M 전용
        assertFalse(decide(p, req("meal:Redeem", Trn.merchant(UUID.randomUUID()))))
        assertFalse(decide(p, req("meal:VoidRedeem", Trn.merchant(UUID.randomUUID()))))
        // 소비 적재는 생산자 M2M 전용(장부 무결성)
        assertFalse(decide(p, req("consumption:Write", Trn.consumptionLog(orgA))))
        // SCIM 프로비저닝은 HR M2M 전용
        assertFalse(decide(p, req("scim:ManageDirectory", Trn.scimDirectory(orgA))))
    }

    @Test fun `org admin action list is explicit — unknown org actions are not auto-granted`() {
        // 과거 org:* 와일드카드는 앞으로 추가되는 모든 org: action 을 자동 부여했다.
        // 명시 열거로 바꿔, 열거되지 않은 action 은(예: 플랫폼 전용 조작) 부여되지 않는다.
        val p = listOf(bridge.orgAdmin(orgA))
        assertTrue(decide(p, req("org:UpdateProfile", Trn.organization(orgA))))
        assertFalse(decide(p, req("org:ForceVerifyDomain", Trn.orgDomains(orgA))))
        assertFalse(decide(p, req("org:ChangeStatus", Trn.organization(orgA))))
        assertFalse(decide(p, req("org:LinkSsoConnection", Trn.organization(orgA))))
    }

    @Test fun `org admin scoped to own org only`() {
        val p = listOf(bridge.orgAdmin(orgA))
        // 자기 org 관리 허용
        assertTrue(decide(p, req("org:UpdateProfile", Trn.organization(orgA))))
        assertTrue(decide(p, req("billing:FinalizeInvoice", Trn.invoice(orgA, UUID.randomUUID()))))
        assertTrue(decide(p, req("forecast:Read", Trn.forecast(orgA))))
        // 멤버 권한도 포함
        assertTrue(decide(p, req("meal:IssueQr", Trn.mealQr(orgA))))
        // ★타 org 리소스는 거부(테넌시 격리)
        assertFalse(decide(p, req("org:UpdateProfile", Trn.organization(orgB))))
        assertFalse(decide(p, req("billing:FinalizeInvoice", Trn.invoice(orgB, UUID.randomUUID()))))
        // ★소비 적재(M2M 전용)는 org admin 이라도 거부
        assertFalse(decide(p, req("consumption:Write", Trn.consumptionLog(orgA))))
        // ★가맹 redeem(merchant M2M)도 거부
        assertFalse(decide(p, req("meal:Redeem", Trn.merchant(UUID.randomUUID()))))
    }

    @Test fun `org member cannot do admin actions`() {
        val p = listOf(bridge.orgMember(orgA))
        assertTrue(decide(p, req("meal:IssueQr", Trn.mealQr(orgA))))
        assertTrue(decide(p, req("consumption:ReadAggregate", Trn.consumptionLog(orgA))))
        assertFalse(decide(p, req("forecast:Read", Trn.forecast(orgA))))
        assertFalse(decide(p, req("org:RemoveMember", Trn.member(orgA, UUID.randomUUID()))))
        assertFalse(decide(p, req("meal:IssueQr", Trn.mealQr(orgB))))
    }

    @Test fun `m2m redeem bound to merchant`() {
        val merchant = UUID.randomUUID()
        val p = bridge.m2m(setOf("meal.redeem"), emptySet(), merchant)
        assertTrue(decide(p, req("meal:Redeem", Trn.merchant(merchant), m2mCtx())))
        assertTrue(decide(p, req("meal:VoidRedeem", Trn.merchant(merchant), m2mCtx())))
        assertFalse(decide(p, req("meal:Redeem", Trn.merchant(UUID.randomUUID()), m2mCtx())))
        assertFalse(decide(p, req("consumption:Write", Trn.consumptionLog(orgA), m2mCtx())))
    }

    @Test fun `m2m consumption write bound to org`() {
        val p = bridge.m2m(setOf("meal.consumption.write"), setOf(orgA), null)
        assertTrue(decide(p, req("consumption:Write", Trn.consumptionLog(orgA), m2mCtx())))
        assertFalse(decide(p, req("consumption:Write", Trn.consumptionLog(orgB), m2mCtx())))
        assertFalse(decide(p, req("consumption:ReadAggregate", Trn.consumptionLog(orgA), m2mCtx())))
    }

    @Test fun `m2m read all spans orgs`() {
        val p = bridge.m2m(setOf("meal.consumption.read.all"), emptySet(), null)
        assertTrue(decide(p, req("consumption:ReadAggregate", Trn.consumptionLog(orgA), m2mCtx())))
        assertTrue(decide(p, req("consumption:ReadAggregate", Trn.consumptionLog(orgB), m2mCtx())))
    }

    @Test fun `m2m policies deny when PrincipalType context key is missing`() {
        // fail-safe 확인: 컨텍스트에 주체 종류가 없으면(배선 누락) 허용이 아니라 거부가 된다.
        val p = bridge.m2m(setOf("meal.consumption.write"), setOf(orgA), null)
        assertFalse(decide(p, req("consumption:Write", Trn.consumptionLog(orgA))))
    }

    // ── 가맹 관리자(사람 신원) ──────────────────────────────────────────

    @Test fun `merchant admin sees only own merchant and cannot redeem`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        val p = listOf(bridge.merchantAdmin(mine))

        // 자기 매장 조회는 허용
        assertTrue(decide(p, req(IamActions.MERCHANT_READ_TRANSACTIONS, Trn.merchant(mine))))
        assertTrue(decide(p, req(IamActions.MERCHANT_READ_FORECAST, Trn.merchant(mine))))

        // ★타 매장은 거부 — merchant TRN 은 org 세그먼트가 비어 있어 UUID 정확일치가 테넌시를 가른다
        assertFalse(decide(p, req(IamActions.MERCHANT_READ_TRANSACTIONS, Trn.merchant(other))))
        assertFalse(decide(p, req(IamActions.MERCHANT_READ_FORECAST, Trn.merchant(other))))

        // ★결제 승인은 사람 신원에 절대 주지 않는다(계정 탈취가 무단 결제가 되지 않게)
        assertFalse(decide(p, req(IamActions.MEAL_REDEEM, Trn.merchant(mine))))
        assertFalse(decide(p, req(IamActions.MEAL_VOID_REDEEM, Trn.merchant(mine))))
    }

    @Test fun `merchant scope is not delegatable to third-party tokens`() {
        // 가맹 관리자가 제3자 앱에 로그인해도, 그 토큰으로는 매장 데이터에 닿지 못한다(위임 경계 밖).
        val merchant = UUID.randomUUID()
        val svc = shadowService()
        val request = req(IamActions.MERCHANT_READ_TRANSACTIONS, Trn.merchant(merchant))
        assertFalse(
            svc.decideDelegated(UUID.randomUUID(), false, null, null, setOf("calendar.read"), request).isAllowed,
        )
    }

    // ── 위임 토큰(DELEGATED) 방어선 ─────────────────────────────────────

    /** M2M 정책은 주체가 기계일 때만 적용된다 — 컨텍스트에 PrincipalType 을 명시해야 한다. */
    private fun m2mCtx(kind: IamPrincipalKind = IamPrincipalKind.M2M) = mapOf(IamContextKeys.PRINCIPAL_TYPE to kind.name)

    @Test fun `m2m policies require machine principal`() {
        val p = bridge.m2m(setOf("meal.consumption.write"), setOf(orgA), null)
        // 기계 신원이면 허용
        assertTrue(decide(p, req(IamActions.CONSUMPTION_WRITE, Trn.consumptionLog(orgA), m2mCtx())))
        // ★같은 scope 를 가진 **사용자 위임 토큰**은 거부 — 정답데이터 위조 주입 차단
        assertFalse(decide(p, req(IamActions.CONSUMPTION_WRITE, Trn.consumptionLog(orgA), m2mCtx(IamPrincipalKind.DELEGATED))))
        // 세션 주체도 마찬가지(적재는 기계 전용 표면)
        assertFalse(decide(p, req(IamActions.CONSUMPTION_WRITE, Trn.consumptionLog(orgA), m2mCtx(IamPrincipalKind.SESSION))))
    }

    @Test fun `delegated token cannot reach machine-only surfaces even with scope`() {
        // SCIM·예측은 기계 전용 — 위임 토큰에 그 scope 가 붙어도 도달 불가.
        val scim = bridge.m2m(setOf("org.scim"), setOf(orgA), null, scimOrg = orgA)
        assertFalse(decide(scim, req(IamActions.SCIM_MANAGE_DIRECTORY, Trn.scimDirectory(orgA), m2mCtx(IamPrincipalKind.DELEGATED))))
        val forecast = bridge.m2m(setOf("meal.forecast.read"), setOf(orgA), null)
        assertFalse(decide(forecast, req(IamActions.FORECAST_READ, Trn.forecast(orgA), m2mCtx(IamPrincipalKind.DELEGATED))))
    }

    @Test fun `delegation boundary enforces scope consent for delegated tokens`() {
        // 사용자가 org 활성 멤버여도, 토큰이 동의받지 않은 능력은 쓸 수 없다(confused deputy 차단).
        val userId = UUID.randomUUID()
        val svc = shadowService()
        val calendarReq = req(IamActions.CALENDAR_READ_EVENTS, Trn.calendarEvents(orgA))

        // scope 없음(openid/profile/email 뿐) → 거부
        assertFalse(
            svc.decideDelegated(userId, false, null, orgA, setOf("openid", "profile", "email"), calendarReq).isAllowed,
        )
        // 동의받은 scope → 허용
        assertTrue(svc.decideDelegated(userId, false, null, orgA, setOf("calendar.read"), calendarReq).isAllowed)
        // 다른 능력은 그 scope 로 못 함(경계 밖)
        val consumptionReq = req(IamActions.CONSUMPTION_READ_AGGREGATE, Trn.consumptionLog(orgA))
        assertFalse(svc.decideDelegated(userId, false, null, orgA, setOf("calendar.read"), consumptionReq).isAllowed)
        assertTrue(svc.decideDelegated(userId, false, null, orgA, setOf("meal.consumption.read"), consumptionReq).isAllowed)
    }

    @Test fun `delegation boundary cannot exceed identity permissions`() {
        // 경계가 넓어도 신원 권한이 없으면 못 한다(교집합).
        val userId = UUID.randomUUID()
        val svc = shadowService()
        val calendarReq = req(IamActions.CALENDAR_READ_EVENTS, Trn.calendarEvents(orgB))
        // orgA 멤버인데 orgB 캘린더 요청 → 신원 쪽에서 거부
        assertFalse(svc.decideDelegated(userId, false, null, orgA, setOf("calendar.read"), calendarReq).isAllowed)
    }

    /** 저장 정책 없는(브리지 권한만 가진) 판정용 서비스 — 브리지 자체의 성질을 보는 테스트용. */
    private fun shadowService(): IamAuthorizationService {
        val emptyResolver =
            object : PrincipalPolicyResolver(
                io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true),
                // 멤버십·조직 저장소 — 이 테스트는 resolveUserPolicies 를 통째로 재정의하므로 쓰이지 않는다.
                io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true),
                parser,
            ) {
                override fun resolveUserPolicies(userId: UUID): List<PolicyDocument> = emptyList()
            }
        return IamAuthorizationService(evaluator = evaluator, resolver = emptyResolver, bridge = bridge)
    }

    @Test fun `m2m scim uses single org anchor not bound orgs`() {
        // SCIM 은 org_id 클레임(단일 앵커)만 인정 — boundOrgs 에 다른 org 가 섞여도 그 org 는 부여되지 않는다.
        val p = bridge.m2m(setOf("org.scim"), boundOrgs = setOf(orgA, orgB), merchantId = null, scimOrg = orgA)
        assertTrue(decide(p, req("scim:ManageDirectory", Trn.scimDirectory(orgA), m2mCtx())))
        assertFalse(decide(p, req("scim:ManageDirectory", Trn.scimDirectory(orgB), m2mCtx())))
        // 앵커가 없으면(org_id 클레임 부재) 아무 org 에도 부여되지 않는다.
        val noAnchor = bridge.m2m(setOf("org.scim"), setOf(orgA), null, scimOrg = null)
        assertFalse(decide(noAnchor, req("scim:ManageDirectory", Trn.scimDirectory(orgA), m2mCtx())))
    }

    // ── 보안 회귀: fail-safe 불변식 ────────────────────────────────────

    @Test fun `deny with absent condition key still denies (fail-closed)`() {
        // 정책 작성자가 "step-up 없으면 거부"를 양성 연산자로 표현했는데 집행부가 키를 안 채운 경우:
        // Deny 가 조용히 스킵되면 Allow 가 이겨 열린다 → effect 편향으로 Deny 를 살린다.
        val policies =
            listOf(
                doc(
                    allow("*", "*"),
                    deny("billing:FinalizeInvoice", "*", cond("Bool", "taspa:StepUpPresent", "false")),
                ),
            )
        assertFalse(decide(policies, req("billing:FinalizeInvoice", Trn.invoice(orgA, UUID.randomUUID()))))
        // Allow 문장은 반대로 키 부재 시 미부여(안전측).
        assertFalse(
            decide(
                listOf(doc(allow("billing:ReadInvoice", "*", cond("Bool", "taspa:StepUpPresent", "true")))),
                req("billing:ReadInvoice", Trn.invoice(orgA, UUID.randomUUID())),
            ),
        )
    }

    @Test fun `deny with unparseable numeric still denies`() {
        val policies =
            listOf(
                doc(
                    allow("*", "*"),
                    deny("org:RemoveMember", "*", cond("NumericLessThan", "n", "10")),
                ),
            )
        // n 이 수치가 아니라 미평가 → Deny 는 적용(fail-closed)
        assertFalse(decide(policies, req("org:RemoveMember", Trn.member(orgA, UUID.randomUUID()), mapOf("n" to "not-a-number"))))
    }

    @Test fun `deny with unknown operator still denies`() {
        assertTrue(satisfiedDeny(listOf(cond("BogusOp", "k", "v")), mapOf("k" to "v")))
        assertFalse(satisfiedAllow(listOf(cond("BogusOp", "k", "v")), mapOf("k" to "v")))
    }

    @Test fun `substituted variable value cannot inject wildcards`() {
        // context 값에 "*" 가 들어와도 리터럴로 취급 — 전 org 매치로 번지지 않아야 한다.
        val policies = listOf(doc(allow("org:UpdateProfile", "trn:taspa:org:\${taspa:OrgId}:*")))
        assertFalse(decide(policies, req("org:UpdateProfile", Trn.organization(orgA), mapOf("taspa:OrgId" to "*"))))
        // 정상 값은 여전히 동작
        assertTrue(decide(policies, req("org:UpdateProfile", Trn.organization(orgA), mapOf("taspa:OrgId" to orgA.toString()))))
    }

    @Test fun `org scope resists colon crossing glob via ResourceOrg condition`() {
        // 리소스 id 에 다른 org 의 UUID 가 콜론과 함께 박혀도(:{orgA}:) 테넌시는 ResourceOrg 정확일치가 지킨다.
        val hostile = "trn:taspa:org:$orgB:note/:$orgA:"
        assertEquals(orgB.toString(), Trn.orgSegmentOf(hostile))
        assertFalse(decide(listOf(bridge.orgAdmin(orgA)), req("org:UpdateProfile", hostile)))
        assertTrue(decide(listOf(bridge.orgAdmin(orgB)), req("org:UpdateProfile", hostile)))
    }

    @Test fun `caller cannot spoof ResourceOrg context key`() {
        // 호출자가 taspa:ResourceOrg 를 직접 넣어도 엔진이 리소스에서 뽑은 값으로 덮어쓴다.
        val spoof = mapOf(PolicyEvaluator.RESOURCE_ORG_KEY to orgA.toString())
        assertFalse(decide(listOf(bridge.orgAdmin(orgA)), req("org:UpdateProfile", Trn.organization(orgB), spoof)))
    }

    @Test fun `org segment extraction handles edge shapes`() {
        assertEquals("", Trn.orgSegmentOf("trn:taspa:meal::merchant/x")) // 가맹(빈 org)
        assertEquals("", Trn.orgSegmentOf("not-a-trn"))
        assertEquals("", Trn.orgSegmentOf("trn:taspa:org")) // 세그먼트 부족
        assertEquals("A", Trn.orgSegmentOf("trn:taspa:org:A:organization/A"))
    }

    // ── 파서 엄격성 회귀 ───────────────────────────────────────────────

    @Test fun `parser rejects unsupported statement elements`() {
        // NotResource 를 조용히 무시하면 "IAM 제외" 의도가 사라져 Allow * 가 된다 → 저장 시점 거부.
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("""{"Statement":[{"Effect":"Allow","Action":"*","Resource":"*","NotResource":"trn:taspa:iam:::*"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("""{"Statement":[{"Effect":"Allow","Action":"*","Resource":"*","Principal":"*"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("""{"Statement":[{"Effect":"Allow","Action":"*","Resource":"*","Bogus":1}]}""")
        }
    }

    @Test fun `parser rejects duplicate keys`() {
        // 마지막 값이 이기면 리뷰에서 Deny 로 보인 문서가 Allow 로 저장될 수 있다.
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("""{"Statement":[{"Effect":"Deny","Action":"*","Resource":"*","Effect":"Allow"}]}""")
        }
    }
}
