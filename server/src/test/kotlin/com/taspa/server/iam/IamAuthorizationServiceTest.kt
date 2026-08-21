package com.taspa.server.iam

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 인가 서비스의 집행 계약.
 *
 * 가장 중요한 불변식은 **fail-closed** 다: 판정을 계산하지 못하면(정책 손상·저장소 장애) 허용이 아니라
 * 거부여야 한다. 인가 엔진이 결정하지 못한 요청을 통과시키면 그것이 곧 우회 경로다.
 */
class IamAuthorizationServiceTest {
    private val org = UUID.randomUUID()
    private val bridge = LegacyPolicyBridge()

    private fun service(resolver: PrincipalPolicyResolver = emptyResolver()) = IamAuthorizationService(PolicyEvaluator(), resolver, bridge)

    /** 저장 정책이 없는 사용자(브리지 권한만 가진 일반적인 경우). */
    private fun emptyResolver(): PrincipalPolicyResolver {
        val resolver = mockk<PrincipalPolicyResolver>()
        every { resolver.resolveUserPolicies(any()) } returns emptyList()
        return resolver
    }

    private val request = AuthorizationRequest(IamActions.ORG_UPDATE_PROFILE, Trn.organization(UUID.randomUUID()))

    @Test
    fun `denies when decision cannot be computed`() {
        // 판정 람다가 던지면(정책 손상·DB 장애) 예외를 밖으로 흘리지 않고 거부한다.
        val allowed = service().authorize(request, "test") { throw IllegalStateException("policy store down") }
        assertFalse(allowed, "판정 불가는 허용이 아니라 거부여야 한다(fail-closed)")
    }

    @Test
    fun `denies when stored policy is corrupt`() {
        // 손상된 저장 정책은 조용히 스킵되지 않고 예외가 되며(PrincipalPolicyResolver), 그 결과는 거부다.
        val broken = mockk<PrincipalPolicyResolver>()
        every { broken.resolveUserPolicies(any()) } throws IllegalStateException("corrupt policy")
        val svc = service(broken)

        val allowed =
            svc.authorize(request, "test") {
                svc.decideSession(UUID.randomUUID(), platformAdmin = false, orgAdminOf = org, memberOf = org, request = request)
            }
        assertFalse(allowed)
    }

    @Test
    fun `passes through engine verdict`() {
        val svc = service()
        assertTrue(svc.authorize(request, "test") { Decision(DecisionEffect.ALLOW, "ok") })
        assertFalse(svc.authorize(request, "test") { Decision(DecisionEffect.DENY, "no") })
    }

    // ── 위임 토큰(DELEGATED) 경계 ───────────────────────────────────────

    @Test
    fun `delegated token needs both membership and consent`() {
        val svc = service()
        val userId = UUID.randomUUID()
        val calendar = AuthorizationRequest(IamActions.CALENDAR_READ_EVENTS, Trn.calendarEvents(org))

        // 동의(scope) 없이는 멤버여도 불가 — 제3자 앱이 동의 밖 능력에 도달하지 못한다.
        assertFalse(
            svc.decideDelegated(userId, false, null, org, setOf("openid", "profile"), calendar).isAllowed,
        )
        // 동의가 있으면 허용
        assertTrue(svc.decideDelegated(userId, false, null, org, setOf("calendar.read"), calendar).isAllowed)
        // 멤버십이 없으면 동의가 있어도 불가(교집합)
        val otherOrg = AuthorizationRequest(IamActions.CALENDAR_READ_EVENTS, Trn.calendarEvents(UUID.randomUUID()))
        assertFalse(svc.decideDelegated(userId, false, null, org, setOf("calendar.read"), otherOrg).isAllowed)
    }

    @Test
    fun `machine-only surfaces reject delegated principals`() {
        val svc = service()
        val scim =
            AuthorizationRequest(
                IamActions.SCIM_MANAGE_DIRECTORY,
                Trn.scimDirectory(org),
                mapOf(IamContextKeys.PRINCIPAL_TYPE to IamPrincipalKind.DELEGATED.name),
            )
        assertEquals(
            DecisionEffect.DENY,
            svc.decideM2m(setOf("org.scim"), setOf(org), null, scim, scimOrg = org).effect,
        )

        // 같은 요청이 기계 신원이면 허용된다(조건이 주체 종류만 구분함을 확인).
        val asMachine = scim.copy(context = mapOf(IamContextKeys.PRINCIPAL_TYPE to IamPrincipalKind.M2M.name))
        assertEquals(
            DecisionEffect.ALLOW,
            svc.decideM2m(setOf("org.scim"), setOf(org), null, asMachine, scimOrg = org).effect,
        )
    }
}
