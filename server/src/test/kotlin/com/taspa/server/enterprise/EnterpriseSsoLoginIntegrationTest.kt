package com.taspa.server.enterprise

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.federation.FederatedIdentityRepository
import com.taspa.server.domain.sso.SsoConnectionRepository
import com.taspa.server.domain.sso.SsoDomainRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.enterprise.dto.SsoConnectionRequest
import com.taspa.server.federation.FederatedLoginSuccessHandler
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.saml2.core.Saml2X509Credential
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64

/**
 * 기업 SSO(Stage E) 로그인 플로우 통합 테스트.
 *
 * - HRD 라우팅(도메인 매칭 → IdP 리다이렉트, 미매칭/enforced=false 는 로컬 흐름).
 * - 도메인 일치 강제(정책 5): 조직 OIDC/SAML 성공 핸들러가 공급자 이메일 도메인 == verified 도메인을 강제.
 * - JIT 프로비저닝 + 로컬 UserDetails 완전 인증 승격.
 * - SAML ACS(permitAll + CSRF 면제) 와 SP 메타데이터 엔드포인트 배선.
 *
 * OIDC/SAML 성공 경로는 성공 핸들러 레벨에서 검증한다(실 IdP·서명 어서션 라운드트립 없이 —
 * OpenSaml4AuthenticationProvider 가 만드는 principal 형태를 직접 구성). ACS 의 서명 검증 거부는
 * MockMvc bogus SAMLResponse 로 확인한다.
 */
class EnterpriseSsoLoginIntegrationTest : IntegrationTestBase() {
    companion object {
        // 자가 서명 X.509(RSA 2048) — SAML verification cert / SP 메타데이터 픽스처.
        val TEST_CERT =
            """
            -----BEGIN CERTIFICATE-----
            MIIDHzCCAgegAwIBAgIUHsSNRtHcRXA5so0e9wRl0Tm9O+4wDQYJKoZIhvcNAQEL
            BQAwHzEdMBsGA1UEAwwUdGVzdC1pZHAuZXhhbXBsZS5jb20wHhcNMjYwNzE3MTgy
            OTM2WhcNMzYwNzE0MTgyOTM2WjAfMR0wGwYDVQQDDBR0ZXN0LWlkcC5leGFtcGxl
            LmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAOy7V1ZMifdKodu9
            Dr0r5+Lq8zbsTm8i5UAfpjJLkGOgRypVy5a/SkwNBhH6bZKmpjKpphf88GzbDH4h
            cxyc2mZ06tHxnwZYGqqa14k+Q0ESKr/K2UVogFH3t1AEU6xwwuS5BCtA6qt3tlB3
            HT4dL5XuqIsbzN6rndy90X0FDM9w4ew8SmaCYSKC9BpqG2o5TDX4P24fQ6A36ZRS
            yeszuKm2LSddcVm9fGpjzg2iF5eK2JMSu613zJ37xa7aVjOK5iWU1+QXCLNmtM45
            nKrnTGiRakX1MTSO/Rb/JeQkA2DkQbeCbFyYwPYtjYHnBxoI+AM7t0g5kJgTvnAv
            G8DVdM0CAwEAAaNTMFEwHQYDVR0OBBYEFAsm0cZmrxwyVDnRlgJg7nWyVYqNMB8G
            A1UdIwQYMBaAFAsm0cZmrxwyVDnRlgJg7nWyVYqNMA8GA1UdEwEB/wQFMAMBAf8w
            DQYJKoZIhvcNAQELBQADggEBABQGZdDrmzLfzcI5Mz24abgs3xqIwG+XbspTece1
            cRMeBMpdT/0kw1qt/nD5jKa4y9okUhZMOvdV1mkZ1wvS1VhVSr19b827/qq89CIs
            5lmBosgn0W0rZyxSTYs6Y2k4YTUfrCDZfE/Un1UnCBuoGQQgbrNC9r3TghKInZjA
            e8rGb6ymYSPB63vGjOFPhC31uucPBfcjiRFNONiidRxOIE8D/kCrbj4eb4pajo6q
            3WqYAh0Wf5p3nEDwJXFvj3o8T8Undld/KRNzeCV8wsStG02CmskYTujKuYJe8VK5
            fu1zCfirqjVMA9qo+nOu6p4BJ3ZWmqY6+hSv3XCNp0msmCA=
            -----END CERTIFICATE-----
            """.trimIndent()
    }

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var federatedIdentityRepository: FederatedIdentityRepository

    @Autowired lateinit var connectionRepository: SsoConnectionRepository

    @Autowired lateinit var domainRepository: SsoDomainRepository

    @Autowired lateinit var ssoConnectionService: SsoConnectionService

    @Autowired lateinit var federatedLoginSuccessHandler: FederatedLoginSuccessHandler

    @Autowired lateinit var saml2SuccessHandler: Saml2FederatedLoginSuccessHandler

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        federatedIdentityRepository.deleteAll()
        domainRepository.deleteAll()
        connectionRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    // ---- HRD 라우팅 ----

    @Test
    fun `enforced OIDC connection routes matching email domain to the IdP`() {
        createOidcConnection("org-oidc", "oidc.example.com", verified = true, enforced = true)

        webSession()
            .perform(post("/login/identifier").param("email", "alice@oidc.example.com").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/oauth2/authorization/org-oidc"))
    }

    @Test
    fun `enforced SAML connection routes matching email domain to the IdP`() {
        createSamlConnection("org-saml", "saml.example.com", verified = true, enforced = true)

        webSession()
            .perform(post("/login/identifier").param("email", "bob@saml.example.com").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/saml2/authenticate/org-saml"))
    }

    @Test
    fun `unverified domain is not routed to the IdP and falls through to local flow`() {
        // verified=false → HRD 미매칭. 로컬 계정도 없으므로 identifier 페이지가 에러로 재렌더된다(리다이렉트 아님).
        createOidcConnection("org-oidc", "oidc.example.com", verified = false, enforced = true)

        webSession()
            .perform(post("/login/identifier").param("email", "carol@oidc.example.com").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("taspa 계정을 찾을 수 없습니다")))
    }

    @Test
    fun `non-enforced connection does not short-circuit to the IdP`() {
        createOidcConnection("org-oidc", "oidc.example.com", verified = true, enforced = false)

        webSession()
            .perform(post("/login/identifier").param("email", "dave@oidc.example.com").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("taspa 계정을 찾을 수 없습니다")))
    }

    // ---- 조직 OIDC 성공 핸들러: JIT + 도메인 일치 강제 ----

    @Test
    fun `org OIDC JIT provisions a user and links oidc identity when domain matches`() {
        createOidcConnection("org-oidc", "oidc.example.com", verified = true, enforced = true)
        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()

        federatedLoginSuccessHandler.onAuthenticationSuccess(
            request,
            response,
            oidcToken("org-oidc", sub = "oidc-sub-1", email = "erin@oidc.example.com", emailVerified = true),
        )

        val user = userRepository.findByEmail("erin@oidc.example.com")
        assertThat(user).isNotNull
        assertThat(user!!.passwordHash).isNull()
        assertThat(user.emailVerified).isTrue()
        val identity = federatedIdentityRepository.findByProviderAndProviderUserId("oidc:org-oidc", "oidc-sub-1")
        assertThat(identity).isNotNull
        assertThat(identity!!.userId).isEqualTo(user.id)
        assertThat(response.redirectedUrl).isEqualTo("/account")
    }

    @Test
    fun `org OIDC rejects a provider email whose domain is not the connection verified domain`() {
        createOidcConnection("org-oidc", "oidc.example.com", verified = true, enforced = true)
        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()

        // 조직 IdP 가 타 도메인 이메일(계정 탈취 시도)을 주장 → 도메인 불일치로 거부.
        federatedLoginSuccessHandler.onAuthenticationSuccess(
            request,
            response,
            oidcToken("org-oidc", sub = "oidc-sub-2", email = "attacker@evil.example.com", emailVerified = true),
        )

        assertThat(userRepository.findByEmail("attacker@evil.example.com")).isNull()
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId("oidc:org-oidc", "oidc-sub-2")).isNull()
        assertThat(response.redirectedUrl).isEqualTo("/login?error=social")
    }

    // ---- SAML 성공 핸들러: JIT + 도메인 일치 강제 ----

    @Test
    fun `saml JIT provisions a user and links saml identity when domain matches`() {
        createSamlConnection("org-saml", "saml.example.com", verified = true, enforced = true)
        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()

        saml2SuccessHandler.onAuthenticationSuccess(
            request,
            response,
            samlAuth("org-saml", nameId = "frank@saml.example.com", email = "frank@saml.example.com"),
        )

        val user = userRepository.findByEmail("frank@saml.example.com")
        assertThat(user).isNotNull
        assertThat(user!!.emailVerified).isTrue()
        val identity = federatedIdentityRepository.findByProviderAndProviderUserId("saml:org-saml", "frank@saml.example.com")
        assertThat(identity).isNotNull
        assertThat(identity!!.userId).isEqualTo(user.id)
        assertThat(response.redirectedUrl).isEqualTo("/account")
    }

    @Test
    fun `saml rejects a provider email whose domain is not the connection verified domain`() {
        createSamlConnection("org-saml", "saml.example.com", verified = true, enforced = true)
        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()

        saml2SuccessHandler.onAuthenticationSuccess(
            request,
            response,
            samlAuth("org-saml", nameId = "attacker", email = "attacker@evil.example.com"),
        )

        assertThat(userRepository.findByEmail("attacker@evil.example.com")).isNull()
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId("saml:org-saml", "attacker")).isNull()
        assertThat(response.redirectedUrl).isEqualTo("/login?error=sso")
    }

    @Test
    fun `saml links to an existing local account with the same verified-domain email`() {
        createSamlConnection("org-saml", "saml.example.com", verified = true, enforced = true)
        val existing =
            userRepository.save(
                User(email = "grace@saml.example.com", passwordHash = null, emailVerified = true),
            )
        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()

        saml2SuccessHandler.onAuthenticationSuccess(
            request,
            response,
            samlAuth("org-saml", nameId = "grace-nameid", email = "grace@saml.example.com"),
        )

        val identity = federatedIdentityRepository.findByProviderAndProviderUserId("saml:org-saml", "grace-nameid")
        assertThat(identity).isNotNull
        assertThat(identity!!.userId).isEqualTo(existing.id)
        assertThat(response.redirectedUrl).isEqualTo("/account")
    }

    @Test
    fun `saml signed response authenticates through the real provider then links and completes`() {
        // 양성 경로: 자가서명 키로 서명한 Response → OpenSaml4AuthenticationProvider 서명검증·기본
        // ResponseAuthenticationConverter → 성공 핸들러 링킹/완전인증까지 관통(손으로 만든 principal 아님).
        createSamlConnection("org-saml-signed", "saml.example.com", verified = true, enforced = true)

        val idpEntityId = "https://idp.example.com/saml"
        val spEntityId = "https://sp.example.com/saml2/service-provider-metadata/org-saml-signed"
        val acsLocation = "https://sp.example.com/login/saml2/sso/org-saml-signed"

        // 서명검증용 RelyingPartyRegistration(검증 인증서 = 서명 키페어의 공개 인증서).
        val relyingParty =
            RelyingPartyRegistration
                .withRegistrationId("org-saml-signed")
                .entityId(spEntityId)
                .assertionConsumerServiceLocation(acsLocation)
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyMetadata { party ->
                    party.entityId(idpEntityId)
                    party.singleSignOnServiceLocation("https://idp.example.com/sso")
                    party.verificationX509Credentials { creds ->
                        creds.add(Saml2X509Credential.verification(SamlSignedResponseFixture.signingCertificate))
                    }
                }.build()

        val responseXml =
            SamlSignedResponseFixture.signedResponse(
                idpEntityId = idpEntityId,
                spEntityId = spEntityId,
                acsLocation = acsLocation,
                nameId = "heidi@saml.example.com",
                email = "heidi@saml.example.com",
            )

        val provider = OpenSaml4AuthenticationProvider()
        val authentication =
            provider.authenticate(
                Saml2AuthenticationToken(relyingParty, responseXml),
            ) as Saml2Authentication

        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()
        saml2SuccessHandler.onAuthenticationSuccess(request, response, authentication)

        val user = userRepository.findByEmail("heidi@saml.example.com")
        assertThat(user).isNotNull
        assertThat(user!!.passwordHash).isNull()
        assertThat(user.emailVerified).isTrue()
        val identity =
            federatedIdentityRepository.findByProviderAndProviderUserId("saml:org-saml-signed", "heidi@saml.example.com")
        assertThat(identity).isNotNull
        assertThat(identity!!.userId).isEqualTo(user.id)
        assertThat(response.redirectedUrl).isEqualTo("/account")
    }

    // ---- SAML ACS 보안 배선(permitAll + CSRF 면제) + SP 메타데이터 ----

    @Test
    fun `saml ACS is permitAll and csrf-exempt and rejects an unsigned bogus response`() {
        createSamlConnection("org-saml", "saml.example.com", verified = true, enforced = true)
        val bogus = Base64.getEncoder().encodeToString("<not-a-saml-response/>".toByteArray())

        // CSRF 토큰 없이 cross-site form POST 를 흉내낸다. 403(CSRF)·로그인 리다이렉트(인증)로 막히지 않고
        // saml2 필터가 처리해 서명 검증 실패 → 실패 핸들러(/login?error=sso)로 수렴해야 한다.
        webSession()
            .perform(
                post("/login/saml2/sso/org-saml").param("SAMLResponse", bogus),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login?error=sso"))
    }

    @Test
    fun `saml SP metadata endpoint is public and renders the SP entity descriptor`() {
        createSamlConnection("org-saml", "saml.example.com", verified = true, enforced = true)

        webSession()
            .perform(get("/saml2/service-provider-metadata/org-saml"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("EntityDescriptor")))
    }

    // ---- helpers ----

    private fun createOidcConnection(
        regId: String,
        domain: String,
        verified: Boolean,
        enforced: Boolean,
    ) {
        val view =
            ssoConnectionService.create(
                SsoConnectionRequest(
                    registrationId = regId,
                    displayName = "Org OIDC",
                    protocol = "OIDC",
                    enforced = enforced,
                    domains = listOf(domain),
                    oidcAuthorizationUri = "https://idp.example.com/authorize",
                    oidcTokenUri = "https://idp.example.com/token",
                    oidcJwksUri = "https://idp.example.com/jwks",
                    oidcClientId = "client-123",
                    oidcClientSecret = "secret-xyz",
                    oidcScopes = "openid,email,profile",
                ),
            )
        if (verified) ssoConnectionService.setDomainVerified(view.id, domain, true)
    }

    private fun createSamlConnection(
        regId: String,
        domain: String,
        verified: Boolean,
        enforced: Boolean,
    ) {
        val view =
            ssoConnectionService.create(
                SsoConnectionRequest(
                    registrationId = regId,
                    displayName = "Org SAML",
                    protocol = "SAML",
                    enforced = enforced,
                    domains = listOf(domain),
                    samlIdpEntityId = "https://idp.example.com/saml",
                    samlSsoUrl = "https://idp.example.com/sso",
                    samlVerificationCert = TEST_CERT,
                    samlEmailAttr = "email",
                    samlNameAttr = "name",
                ),
            )
        if (verified) ssoConnectionService.setDomainVerified(view.id, domain, true)
    }

    private fun oidcToken(
        regId: String,
        sub: String,
        email: String,
        emailVerified: Boolean,
    ): OAuth2AuthenticationToken {
        val attributes =
            mapOf<String, Any>(
                "sub" to sub,
                "email" to email,
                "email_verified" to emailVerified,
                "name" to "Org User",
            )
        val principal = DefaultOAuth2User(listOf(SimpleGrantedAuthority("OAUTH2_USER")), attributes, "sub")
        return OAuth2AuthenticationToken(principal, principal.authorities, regId)
    }

    private fun samlAuth(
        regId: String,
        nameId: String,
        email: String,
    ): Saml2Authentication {
        val principal =
            DefaultSaml2AuthenticatedPrincipal(
                nameId,
                mapOf("email" to listOf<Any>(email), "name" to listOf<Any>("SAML User")),
            )
        principal.relyingPartyRegistrationId = regId
        return Saml2Authentication(principal, "<saml2:Response/>", listOf(SimpleGrantedAuthority("SAML_USER")))
    }
}
