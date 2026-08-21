package com.taspa.server.admin

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.federation.FederatedIdentityRepository
import com.taspa.server.domain.sso.SsoConnectionRepository
import com.taspa.server.domain.sso.SsoDomainRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.enterprise.EnterpriseSsoLoginIntegrationTest
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 기업 SSO(Stage E) 관리 콘솔 통합 테스트: 역할 기반 접근(403/200), 커넥션 CRUD, 도메인 검증 토글,
 * secret 미노출, CSRF 보호, 검증 도메인 기반 HRD 라우팅.
 */
class AdminSsoConsoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var connectionRepository: SsoConnectionRepository

    @Autowired lateinit var domainRepository: SsoDomainRepository

    @Autowired lateinit var federatedIdentityRepository: FederatedIdentityRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "sso-admin@example.com"
    private val memberEmail = "sso-member@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        federatedIdentityRepository.deleteAll()
        domainRepository.deleteAll()
        connectionRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = adminEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true, role = UserRole.ADMIN.name),
        )
        userRepository.save(
            User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `sso admin pages and apis are 403 for members and 200 for admins`() {
        val member = login(memberEmail)
        member.perform(get("/admin/sso")).andExpect(status().isForbidden)
        member.perform(get("/api/admin/sso")).andExpect(status().isForbidden)
        member
            .perform(post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(oidcBody("m-oidc")).with(csrf()))
            .andExpect(status().isForbidden)

        val admin = login(adminEmail)
        admin.perform(get("/admin/sso")).andExpect(status().isOk)
        admin.perform(get("/api/admin/sso")).andExpect(status().isOk)
    }

    @Test
    fun `admin creates an OIDC connection secret is never exposed and verified domain drives HRD`() {
        val admin = login(adminEmail)
        val id =
            admin
                .perform(
                    post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(oidcBody("acme-oidc")).with(csrf()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.registrationId").value("acme-oidc"))
                .andExpect(jsonPath("$.hasOidcSecret").value(true))
                .andExpect(jsonPath("$.oidcClientSecret").doesNotExist())
                .andReturn()
                .response
                .let { Regex(""""id":"([0-9a-f-]+)"""").find(it.contentAsString)!!.groupValues[1] }

        // secret 원문은 DB 에 평문으로 저장되지 않는다(암호화).
        val stored = connectionRepository.findByRegistrationId("acme-oidc")!!
        assertThat(stored.oidcClientSecretEncrypted).isNotNull()
        assertThat(stored.oidcClientSecretEncrypted).doesNotContain("s3cr3t-oidc")

        // 도메인은 생성 시 미검증 → HRD 라우팅 안 됨.
        webSession()
            .perform(post("/login/identifier").param("email", "u@acme-oidc.example.com").with(csrf()))
            .andExpect(status().isOk)

        // 도메인 검증 토글 → HRD 라우팅됨.
        admin
            .perform(
                post("/api/admin/sso/$id/domain/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"acme-oidc.example.com","verified":true}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        webSession()
            .perform(post("/login/identifier").param("email", "u@acme-oidc.example.com").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/oauth2/authorization/acme-oidc"))

        // 목록은 secret 을 다시 노출하지 않는다.
        admin
            .perform(get("/api/admin/sso"))
            .andExpect(status().isOk)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .content()
                    .string(not(containsString("s3cr3t-oidc"))),
            )
    }

    @Test
    fun `admin creates a SAML connection and sees SP acs and metadata urls`() {
        val admin = login(adminEmail)
        admin
            .perform(
                post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(samlBody("acme-saml")).with(csrf()),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.protocol").value("SAML"))
            .andExpect(jsonPath("$.spAcsUrl").value(containsString("/login/saml2/sso/acme-saml")))
            .andExpect(jsonPath("$.spEntityId").value(containsString("/saml2/service-provider-metadata/acme-saml")))
    }

    @Test
    fun `duplicate registration id is rejected`() {
        val admin = login(adminEmail)
        admin
            .perform(post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(oidcBody("dup")).with(csrf()))
            .andExpect(status().isCreated)
        admin
            .perform(post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(oidcBody("dup")).with(csrf()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `invalid registration id is rejected`() {
        val admin = login(adminEmail)
        admin
            .perform(
                post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(oidcBody("Bad_Id!")).with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `sso mutation without csrf token is rejected`() {
        val admin = login(adminEmail)
        admin
            .perform(post("/api/admin/sso").contentType(MediaType.APPLICATION_JSON).content(oidcBody("nocsrf")))
            .andExpect(status().isForbidden)
        assertThat(connectionRepository.findByRegistrationId("nocsrf")).isNull()
    }

    // ---- helpers ----

    private fun oidcBody(regId: String): String =
        """
        {"registrationId":"$regId","displayName":"Acme OIDC","protocol":"OIDC","enabled":true,"enforced":true,
         "domains":["$regId.example.com"],
         "oidcAuthorizationUri":"https://idp.example.com/authorize","oidcTokenUri":"https://idp.example.com/token",
         "oidcJwksUri":"https://idp.example.com/jwks","oidcClientId":"acme-client","oidcClientSecret":"s3cr3t-oidc",
         "oidcScopes":"openid,email,profile"}
        """.trimIndent()

    private fun samlBody(regId: String): String =
        """
        {"registrationId":"$regId","displayName":"Acme SAML","protocol":"SAML","enabled":true,"enforced":true,
         "domains":["$regId.example.com"],
         "samlIdpEntityId":"https://idp.example.com/saml","samlSsoUrl":"https://idp.example.com/sso",
         "samlVerificationCert":"${EnterpriseSsoLoginIntegrationTest.TEST_CERT.replace("\n", "\\n")}",
         "samlEmailAttr":"email","samlNameAttr":"name"}
        """.trimIndent()

    private fun login(email: String): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        return session
    }
}
