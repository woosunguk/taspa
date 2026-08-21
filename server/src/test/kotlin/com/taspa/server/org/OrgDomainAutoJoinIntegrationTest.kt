package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.OrgDomain
import com.taspa.server.domain.org.OrgDomainRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
import com.taspa.server.stepup.StepUp
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 이메일 도메인 자동 조직 가입(V24, 승인 정책: 권고안 + DNS TXT 자가검증) 통합 테스트.
 *  - 등록: 공용 도메인 400, 검증 선점 정책(타 org 검증 클레임 409·미검증 클레임 비선점·검증 시 탈환),
 *    org 내 중복 409, 타 org/비관리자 403, TXT 안내 반환.
 *  - DNS 검증: mock TXT 일치 → verified, 불일치/조회 예외 → 400(5xx 금지). 강제검증(플랫폼 ADMIN).
 *  - 자동 가입: 가입 → 이메일 코드 확인 시 verified+opt-in ON+ACTIVE 조직에 MEMBER 로 가입
 *    (JOINED 이력 + ORG_AUTO_JOINED 감사). 부정 경로(미검증/opt-in OFF/SUSPENDED/공용·미등록 도메인)
 *    전부 미가입, 이미 멤버면 멱등(역할 보존).
 *  - opt-in 토글: ORG_ADMIN 성공(감사 포함), step-up 만료 401, 타 org 403.
 */
class OrgDomainAutoJoinIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var membershipHistoryRepository: MembershipHistoryRepository

    @Autowired lateinit var orgDomainRepository: OrgDomainRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    @MockkBean(relaxed = true)
    lateinit var dnsTxtResolver: DnsTxtResolver

    private val password = "SecureP@ssw0rd123"
    private val sentMessages = mutableListOf<SimpleMailMessage>()
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID

    @BeforeEach
    fun setUp() {
        orgDomainRepository.deleteAll()
        membershipHistoryRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        emailVerificationCodeRepository.deleteAll()
        userRepository.deleteAll()
        auditEventRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
        orgA = organizationRepository.save(Organization(slug = "dom-a", name = "Domain A")).id!!
        orgB = organizationRepository.save(Organization(slug = "dom-b", name = "Domain B")).id!!
    }

    // ---- 등록 ----

    @Test
    fun `ORG_ADMIN 은 도메인을 등록하고 TXT 게시 안내를 받는다`() {
        val session = loginAsOrgAdmin(orgA, "dom-admin1@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/domains", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"Corp-One.Example."}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.domain").value("corp-one.example"))
            .andExpect(jsonPath("$.verified").value(false))
            .andExpect(jsonPath("$.txtRecordName").value("_taspa-verify.corp-one.example"))

        val saved = orgDomainRepository.findByDomain("corp-one.example")!!
        assertThat(saved.orgId).isEqualTo(orgA)
        assertThat(saved.verified).isFalse()
        assertThat(saved.verificationToken).isNotBlank()

        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_DOMAIN_ADDED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().orgId).isEqualTo(orgA)
    }

    @Test
    fun `공용 이메일 도메인 등록은 400 으로 거부된다`() {
        val session = loginAsOrgAdmin(orgA, "dom-admin2@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/domains", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"gmail.com"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
        assertThat(orgDomainRepository.findByDomain("gmail.com")).isNull()
    }

    @Test
    fun `다른 조직이 검증한 도메인은 409 로 거부된다`() {
        orgDomainRepository.save(
            OrgDomain(
                orgId = orgA,
                domain = "taken.example",
                verified = true,
                verificationToken = "tok-a",
                verifiedMethod = OrgDomain.METHOD_DNS_TXT,
                verifiedAt = Instant.now(),
            ),
        )
        val session = loginAsOrgAdmin(orgB, "dom-b-admin@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/domains", orgB)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"taken.example"}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DOMAIN_ALREADY_CLAIMED"))
    }

    @Test
    fun `미검증 클레임은 선점 효력이 없다 — 다른 조직도 등록할 수 있다(스쿼팅 무력화)`() {
        // orgA 가 소유 증명 없이 등록만 해 둔(스쿼팅) 미검증 클레임은 orgB 의 등록을 막지 못한다.
        orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "shared.example", verificationToken = "tok-squat"))
        val session = loginAsOrgAdmin(orgB, "dom-b2-admin@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/domains", orgB)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"shared.example"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.verified").value(false))
        // 같은 org 의 중복 클레임은 여전히 409.
        session
            .perform(
                post("/api/orgs/{orgId}/domains", orgB)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"shared.example"}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DOMAIN_ALREADY_CLAIMED"))
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 도메인을 관리할 수 없다(격리)`() {
        val session = loginAsOrgAdmin(orgB, "dom-iso-admin@example.com")
        session.perform(get("/api/orgs/{orgId}/domains", orgA)).andExpect(status().isForbidden)
        session
            .perform(
                post("/api/orgs/{orgId}/domains", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"iso.example"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `일반 멤버는 도메인 관리가 거부된다`() {
        val member = saveUser("dom-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/domains", orgA)).andExpect(status().isForbidden)
    }

    // ---- DNS TXT 검증 ----

    @Test
    fun `DNS TXT 가 일치하면 도메인이 검증된다`() {
        val row = orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "verify-ok.example", verificationToken = "tok-ok"))
        every { dnsTxtResolver.lookupTxt("_taspa-verify.verify-ok.example") } returns
            listOf("unrelated=1", "taspa-verify=tok-ok")

        val session = loginAsOrgAdmin(orgA, "dom-verify1@example.com")
        session
            .perform(post("/api/orgs/{orgId}/domains/{id}/verify", orgA, row.id).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))

        val reloaded = orgDomainRepository.findById(row.id!!).orElseThrow()
        assertThat(reloaded.verified).isTrue()
        assertThat(reloaded.verifiedAt).isNotNull()
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_DOMAIN_VERIFIED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
    }

    @Test
    fun `DNS TXT 불일치는 400 이고 미검증으로 남는다`() {
        val row = orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "verify-no.example", verificationToken = "tok-no"))
        every { dnsTxtResolver.lookupTxt("_taspa-verify.verify-no.example") } returns listOf("taspa-verify=wrong")

        val session = loginAsOrgAdmin(orgA, "dom-verify2@example.com")
        session
            .perform(post("/api/orgs/{orgId}/domains/{id}/verify", orgA, row.id).with(csrf()))
            .andExpect(status().isBadRequest)
        assertThat(orgDomainRepository.findById(row.id!!).orElseThrow().verified).isFalse()
    }

    @Test
    fun `DNS 조회 예외는 400 으로 수렴한다(5xx 금지)`() {
        val row = orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "verify-err.example", verificationToken = "tok-err"))
        every { dnsTxtResolver.lookupTxt(any()) } throws RuntimeException("NXDOMAIN")

        val session = loginAsOrgAdmin(orgA, "dom-verify3@example.com")
        session
            .perform(post("/api/orgs/{orgId}/domains/{id}/verify", orgA, row.id).with(csrf()))
            .andExpect(status().isBadRequest)
        assertThat(orgDomainRepository.findById(row.id!!).orElseThrow().verified).isFalse()
    }

    @Test
    fun `검증 성공 시 타 조직의 미검증 클레임은 제거된다(검증 선점 탈환)`() {
        // orgA(스쿼터)가 먼저 등록만 해 두고, 실소유 orgB 가 등록 + DNS TXT 로 소유를 증명하는 상황.
        orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "contested.example", verificationToken = "tok-squat"))
        val rowB = orgDomainRepository.save(OrgDomain(orgId = orgB, domain = "contested.example", verificationToken = "tok-owner"))
        every { dnsTxtResolver.lookupTxt("_taspa-verify.contested.example") } returns listOf("taspa-verify=tok-owner")

        val session = loginAsOrgAdmin(orgB, "dom-owner@example.com")
        session
            .perform(post("/api/orgs/{orgId}/domains/{id}/verify", orgB, rowB.id).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))

        val winner = orgDomainRepository.findByDomainAndVerifiedTrue("contested.example")!!
        assertThat(winner.orgId).isEqualTo(orgB)
        // 스쿼터(orgA)의 미검증 클레임은 탈환 시점에 제거된다 — 소유를 증명한 조직이 이긴다.
        assertThat(orgDomainRepository.findByOrgIdOrderByCreatedAtAsc(orgA)).isEmpty()
    }

    @Test
    fun `이미 검증된 도메인의 verify 재호출은 DNS 없이 멱등이고 감사가 중복되지 않는다`() {
        val row = orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "verify-idem.example", verificationToken = "tok-idem"))
        every { dnsTxtResolver.lookupTxt("_taspa-verify.verify-idem.example") } returns listOf("taspa-verify=tok-idem")

        val session = loginAsOrgAdmin(orgA, "dom-verify-idem@example.com")
        session
            .perform(post("/api/orgs/{orgId}/domains/{id}/verify", orgA, row.id).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))

        // 이후 DNS 가 전부 실패해도(레코드 삭제 등) 재호출은 조기 반환 — DNS 를 다시 조회하지 않는다.
        every { dnsTxtResolver.lookupTxt(any()) } throws RuntimeException("record gone")
        session
            .perform(post("/api/orgs/{orgId}/domains/{id}/verify", orgA, row.id).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))

        // 실제 전이(1회)만 감사에 남는다 — 멱등 재호출은 ORG_DOMAIN_VERIFIED 를 기록하지 않는다.
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_DOMAIN_VERIFIED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
    }

    @Test
    fun `플랫폼 ADMIN 은 DNS 없이 강제 검증할 수 있다(수동 승인 오버라이드)`() {
        val row = orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "force.example", verificationToken = "tok-force"))
        val admin = saveUser("dom-platform@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)
        session
            .perform(post("/api/admin/orgs/{orgId}/domains/{id}/force-verify", orgA, row.id).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))
        assertThat(orgDomainRepository.findById(row.id!!).orElseThrow().verified).isTrue()
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_DOMAIN_FORCE_VERIFIED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().userId).isEqualTo(admin.id)
    }

    @Test
    fun `검증 철회(unverify) 후에는 자동 가입되지 않는다`() {
        prepareAutoJoinOrg(orgA, "auto-revoked.example", verified = true, optIn = true)
        val row = orgDomainRepository.findByDomain("auto-revoked.example")!!
        val admin = saveUser("dom-unverify@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)

        session
            .perform(post("/api/admin/orgs/{orgId}/domains/{id}/unverify", orgA, row.id).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(false))
        val reloaded = orgDomainRepository.findById(row.id!!).orElseThrow()
        assertThat(reloaded.verified).isFalse()
        assertThat(reloaded.verifiedAt).isNull()
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_DOMAIN_UNVERIFIED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)

        // 철회 이후의 이메일 인증은 자동 가입을 발동시키지 않는다(즉시 제외 계약).
        signupAndVerify("harry@auto-revoked.example")
        assertNoMembership("harry@auto-revoked.example")
    }

    @Test
    fun `매직 링크로 이메일이 검증되어도 자동 가입된다(트리거 커버리지)`() {
        prepareAutoJoinOrg(orgA, "auto-magic.example", verified = true, optIn = true)
        val user =
            userRepository.save(
                User(email = "mia@auto-magic.example", passwordHash = passwordEncoder.encode(password), emailVerified = false),
            )

        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", user.email).with(csrf()))
            .andExpect(status().is3xxRedirection)
        session.perform(post("/login/magic/request").with(csrf())).andExpect(status().isOk)
        val token = Regex("token=([A-Za-z0-9_-]+)").find(sentMessages.last().text ?: "")!!.groupValues[1]

        // 링크 확정(소비) = 이메일 소유 증명 → 검증 마킹과 함께 자동 가입 판정이 발동해야 한다.
        session
            .perform(post("/login/magic").param("token", token).with(csrf()))
            .andExpect(status().is3xxRedirection)

        val reloaded = userRepository.findByEmail(user.email)!!
        assertThat(reloaded.emailVerified).isTrue()
        val membership = membershipRepository.findByOrgIdAndUserId(orgA, reloaded.id!!)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(OrgRole.MEMBER.name)
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_AUTO_JOINED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().userId).isEqualTo(reloaded.id)
    }

    // ---- 자동 가입 (트리거: 이메일 인증 성공) ----

    @Test
    fun `가입 후 이메일 인증 시 검증 도메인 조직에 MEMBER 로 자동 가입된다`() {
        prepareAutoJoinOrg(orgA, "auto-yes.example", verified = true, optIn = true)

        signupAndVerify("alice@auto-yes.example")

        val user = userRepository.findByEmail("alice@auto-yes.example")!!
        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(OrgRole.MEMBER.name)

        val history = membershipHistoryRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, user.id!!)
        assertThat(history).anyMatch { it.changeType == "JOINED" }

        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_AUTO_JOINED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().userId).isEqualTo(user.id)
        assertThat(events.first().orgId).isEqualTo(orgA)
    }

    @Test
    fun `미검증 도메인이면 자동 가입되지 않는다`() {
        prepareAutoJoinOrg(orgA, "auto-unverified.example", verified = false, optIn = true)
        signupAndVerify("bob@auto-unverified.example")
        assertNoMembership("bob@auto-unverified.example")
    }

    @Test
    fun `opt-in 이 꺼져 있으면 자동 가입되지 않는다`() {
        prepareAutoJoinOrg(orgA, "auto-off.example", verified = true, optIn = false)
        signupAndVerify("carol@auto-off.example")
        assertNoMembership("carol@auto-off.example")
    }

    @Test
    fun `SUSPENDED 조직이면 자동 가입되지 않는다`() {
        prepareAutoJoinOrg(orgA, "auto-susp.example", verified = true, optIn = true)
        val org = organizationRepository.findById(orgA).orElseThrow()
        org.status = OrgStatus.SUSPENDED.name
        organizationRepository.save(org)

        signupAndVerify("dave@auto-susp.example")
        assertNoMembership("dave@auto-susp.example")
    }

    @Test
    fun `미등록·공용 도메인 가입자는 자동 가입되지 않는다`() {
        prepareAutoJoinOrg(orgA, "auto-strict.example", verified = true, optIn = true)
        // 미등록 도메인 사용자.
        signupAndVerify("eve@other-corp.example")
        assertNoMembership("eve@other-corp.example")
        // 공용 도메인 사용자(등록 자체가 불가한 도메인 — 판정도 선차단).
        sentMessages.clear()
        signupAndVerify("frank@gmail.com")
        assertNoMembership("frank@gmail.com")
        assertThat(auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_AUTO_JOINED", PageRequest.of(0, 10))).isEmpty()
    }

    @Test
    fun `이미 멤버면 멱등이며 기존 역할을 보존한다`() {
        prepareAutoJoinOrg(orgA, "auto-idem.example", verified = true, optIn = true)

        // 가입(미인증) 후 코드 확인 전에 이미 멤버십(ORG_ADMIN)이 존재하는 상황을 만든다.
        val session = signup("grace@auto-idem.example")
        val user = userRepository.findByEmail("grace@auto-idem.example")!!
        membershipRepository.save(OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.ORG_ADMIN.name))

        session
            .perform(post("/login/verify-email").param("code", latestCode()).with(csrf()))
            .andExpect(status().is3xxRedirection)

        val memberships = membershipRepository.findByUserId(user.id!!)
        assertThat(memberships).hasSize(1)
        assertThat(memberships.first().role).isEqualTo(OrgRole.ORG_ADMIN.name)
        assertThat(auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_AUTO_JOINED", PageRequest.of(0, 10))).isEmpty()
    }

    // ---- opt-in 토글 ----

    @Test
    fun `ORG_ADMIN 은 자동 가입을 켜고 끌 수 있다(감사 포함)`() {
        val session = loginAsOrgAdmin(orgA, "dom-toggle@example.com")
        session
            .perform(
                put("/api/orgs/{orgId}/auto-join", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"enabled":true}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.autoJoinEnabled").value(true))
        assertThat(organizationRepository.findById(orgA).orElseThrow().autoJoinEnabled).isTrue()

        session
            .perform(
                put("/api/orgs/{orgId}/auto-join", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"enabled":false}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        assertThat(organizationRepository.findById(orgA).orElseThrow().autoJoinEnabled).isFalse()

        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_AUTO_JOIN_TOGGLED", PageRequest.of(0, 10))
        assertThat(events).hasSize(2)
        assertThat(events.first().orgId).isEqualTo(orgA)
    }

    @Test
    fun `자동 가입 토글은 step-up 재인증을 요구한다`() {
        val session = loginAsOrgAdmin(orgA, "dom-stepup@example.com")
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
        session
            .perform(
                put("/api/orgs/{orgId}/auto-join", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"enabled":true}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
        assertThat(organizationRepository.findById(orgA).orElseThrow().autoJoinEnabled).isFalse()
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 자동 가입을 토글할 수 없다`() {
        val session = loginAsOrgAdmin(orgB, "dom-toggle-iso@example.com")
        session
            .perform(
                put("/api/orgs/{orgId}/auto-join", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"enabled":true}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
        assertThat(organizationRepository.findById(orgA).orElseThrow().autoJoinEnabled).isFalse()
    }

    @Test
    fun `ORG_ADMIN 은 도메인을 삭제할 수 있다`() {
        val row = orgDomainRepository.save(OrgDomain(orgId = orgA, domain = "del.example", verificationToken = "tok-del"))
        val session = loginAsOrgAdmin(orgA, "dom-del@example.com")
        session
            .perform(delete("/api/orgs/{orgId}/domains/{id}", orgA, row.id).with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(orgDomainRepository.findById(row.id!!)).isEmpty
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_DOMAIN_REMOVED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
    }

    // ---- 헬퍼 ----

    /** orgId 조직에 도메인·검증상태·opt-in 을 세팅한다(자동 가입 판정 3조건 중 2가지를 직접 구성). */
    private fun prepareAutoJoinOrg(
        orgId: UUID,
        domain: String,
        verified: Boolean,
        optIn: Boolean,
    ) {
        orgDomainRepository.save(
            OrgDomain(
                orgId = orgId,
                domain = domain,
                verified = verified,
                verificationToken = "tok-$domain",
                verifiedAt = if (verified) Instant.now() else null,
            ),
        )
        val org = organizationRepository.findById(orgId).orElseThrow()
        org.autoJoinEnabled = optIn
        organizationRepository.save(org)
    }

    /** 가입(EMAIL_VERIFICATION 게이트 진입)까지 수행하고 세션을 돌려준다. */
    private fun signup(email: String): WebSession {
        val session = webSession()
        session
            .perform(
                post("/signup")
                    .param("email", email)
                    .param("password", password)
                    .param("displayName", "도메인 테스터")
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/verify-email"))
        return session
    }

    /** 가입 → 발송된 6자리 코드로 이메일 인증까지 완료한다(자동 가입 트리거 지점). */
    private fun signupAndVerify(email: String) {
        val session = signup(email)
        session
            .perform(post("/login/verify-email").param("code", latestCode()).with(csrf()))
            .andExpect(status().is3xxRedirection)
        assertThat(userRepository.findByEmail(email)!!.emailVerified).isTrue()
    }

    private fun assertNoMembership(email: String) {
        val user = userRepository.findByEmail(email)!!
        assertThat(membershipRepository.findByUserId(user.id!!)).isEmpty()
    }

    private fun latestCode(): String {
        val body = sentMessages.last().text ?: ""
        return Regex("\\b\\d{6}\\b").find(body)!!.value
    }

    private fun saveUser(
        email: String,
        role: UserRole = UserRole.USER,
    ): User =
        userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = role.name,
            ),
        )

    private fun loginAsOrgAdmin(
        orgId: UUID,
        email: String,
    ): WebSession {
        val admin = saveUser(email)
        membershipRepository.save(OrgMembership(orgId = orgId, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        return login(email)
    }

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
