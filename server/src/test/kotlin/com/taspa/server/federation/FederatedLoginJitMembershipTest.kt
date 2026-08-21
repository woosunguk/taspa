package com.taspa.server.federation

import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.federation.FederatedIdentity
import com.taspa.server.domain.sso.SsoConnection
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.enterprise.SsoConnectionService
import com.taspa.server.login.LoginFlowSupport
import com.taspa.server.org.OrganizationService
import com.taspa.server.verification.EmailVerificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import java.util.Optional
import java.util.UUID

/**
 * JIT 멤버십 배선(Phase 0-A) 단위 테스트 — 조직 IdP 로그인 성공 시, 사용한 sso_connection 의 org_id 가
 * 있으면 멤버십을 upsert 하고(ensureJitMembership 호출), 없으면 호출하지 않는다(잘못된 자동가입 금지).
 * 이미 연결된 신원(identity) 경로로 검증한다(최소 협력자).
 */
class FederatedLoginJitMembershipTest {
    private val socialAttributesExtractor = mockk<SocialAttributesExtractor>()
    private val federationService = mockk<FederationService>()
    private val userRepository = mockk<UserRepository>()
    private val loginFlowSupport = mockk<LoginFlowSupport>(relaxed = true)
    private val emailVerificationService = mockk<EmailVerificationService>(relaxed = true)
    private val accountLockoutService = mockk<AccountLockoutService>(relaxed = true)
    private val auditEventService = mockk<com.taspa.server.audit.AuditEventService>(relaxed = true)
    private val ssoConnectionService = mockk<SsoConnectionService>()
    private val organizationService = mockk<OrganizationService>(relaxed = true)
    private val orgAutoJoinService = mockk<com.taspa.server.org.OrgAutoJoinService>(relaxed = true)

    private val handler =
        FederatedLoginSuccessHandler(
            socialAttributesExtractor,
            federationService,
            userRepository,
            loginFlowSupport,
            emailVerificationService,
            accountLockoutService,
            auditEventService,
            ssoConnectionService,
            organizationService,
            orgAutoJoinService,
        )

    private val userId = UUID.randomUUID()
    private val connectionId = UUID.randomUUID()
    private val orgId = UUID.randomUUID()

    private fun token(): OAuth2AuthenticationToken {
        val principal =
            DefaultOAuth2User(
                listOf(SimpleGrantedAuthority("ROLE_USER")),
                mapOf("sub" to "ext-1"),
                "sub",
            )
        return OAuth2AuthenticationToken(principal, listOf(SimpleGrantedAuthority("ROLE_USER")), "acme")
    }

    private fun stubCommon(connectionOrgId: UUID?) {
        val attributes =
            SocialAttributes(
                provider = "oidc:acme",
                providerUserId = "ext-1",
                email = "u@acme.example.com",
                emailVerifiedByProvider = true,
                displayName = "User",
            )
        every { socialAttributesExtractor.extract(any(), any()) } returns attributes
        every { ssoConnectionService.findByRegistrationId("acme") } returns
            SsoConnection(
                id = connectionId,
                registrationId = "acme",
                displayName = "Acme",
                protocol = "OIDC",
                orgId = connectionOrgId,
            )
        every { ssoConnectionService.isEmailDomainVerified(any(), any()) } returns true
        every { federationService.findIdentityForConnection(any(), any(), connectionId) } returns
            FederatedIdentity(userId = userId, provider = "oidc:acme", providerUserId = "ext-1")
        every { userRepository.findById(userId) } returns
            Optional.of(User(id = userId, email = "u@acme.example.com", emailVerified = true))
        every { loginFlowSupport.requiredGate(any(), any(), any(), any()) } returns null
        every { loginFlowSupport.completeAuthentication(any(), any(), any(), any()) } returns "redirect:/account"
    }

    private fun request(): HttpServletRequest =
        mockk(relaxed = true) {
            every { getSession(false) } returns null
            every { contextPath } returns ""
        }

    @Test
    fun `org_id 가 있으면 JIT 멤버십을 upsert 한다`() {
        stubCommon(connectionOrgId = orgId)
        every { organizationService.ensureJitMembership(orgId, userId) } returns true

        handler.onAuthenticationSuccess(request(), mockk<HttpServletResponse>(relaxed = true), token())

        verify(exactly = 1) { organizationService.ensureJitMembership(orgId, userId) }
        // 이미 검증된 계정의 기존 신원 로그인 — email_verified 전이가 없으므로 도메인 자동가입 판정도 없다.
        verify(exactly = 0) { orgAutoJoinService.evaluate(any()) }
    }

    @Test
    fun `org_id 가 없으면 JIT 멤버십을 만들지 않는다`() {
        stubCommon(connectionOrgId = null)

        handler.onAuthenticationSuccess(request(), mockk<HttpServletResponse>(relaxed = true), token())

        verify(exactly = 0) { organizationService.ensureJitMembership(any(), any()) }
        verify(exactly = 0) { orgAutoJoinService.evaluate(any()) }
    }

    /**
     * 자동가입 배선 회귀 가드 — 조직 OIDC 가 미검증 로컬 이메일을 검증됨으로 승격하는 지점
     * (emailVerified false→true 전이)에서 OrgAutoJoinService.evaluate 가 반드시 호출돼야 한다.
     * 이 배선이 리팩터링으로 제거되면 이 단언이 잡는다.
     */
    @Test
    fun `조직 커넥션이 미검증 로컬 이메일을 승격하면 자동가입 판정을 호출한다`() {
        stubCommon(connectionOrgId = orgId)
        // 연결된 신원이 아직 없고, 같은 이메일의 미검증 로컬 계정이 존재하는 상황.
        every { federationService.findIdentityForConnection(any(), any(), connectionId) } returns null
        val localUser = User(id = userId, email = "u@acme.example.com", emailVerified = false)
        every { userRepository.findByEmail("u@acme.example.com") } returns localUser
        every { userRepository.save(any()) } answers { firstArg() }
        every { federationService.linkOrConverge(userId, any()) } returns true
        every { organizationService.ensureJitMembership(orgId, userId) } returns true

        handler.onAuthenticationSuccess(request(), mockk<HttpServletResponse>(relaxed = true), token())

        verify(exactly = 1) { orgAutoJoinService.evaluate(localUser) }
    }
}
