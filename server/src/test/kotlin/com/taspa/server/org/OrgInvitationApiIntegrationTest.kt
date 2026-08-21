package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.org.InvitationStatus
import com.taspa.server.domain.org.OrgInvitationRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.stepup.StepUp
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.JwkStorageService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 초대 관리 API + 수락 흐름 인가/격리 통합 테스트 — 스펙 §테스트:
 *  - 생성 권한: 플랫폼 ADMIN·해당 ORG_ADMIN 허용 / 타 org ORG_ADMIN·일반 멤버·미인증 거부.
 *  - 목록/취소 org 격리, 수락 e2e(발송→로그인→수락→소속), 미인증 수락 유도, 이메일 불일치 거부.
 */
class OrgInvitationApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var invitationRepository: OrgInvitationRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID

    @BeforeEach
    fun setUp() {
        invitationRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "api-a", name = "API A")).id!!
        orgB = organizationRepository.save(Organization(slug = "api-b", name = "API B")).id!!
    }

    // ---- 생성 권한 ----

    @Test
    fun `플랫폼 ADMIN 은 초대를 생성한다`() {
        val admin = saveUser("admin@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)
        createInvite(session, orgA, "invitee@example.com").andExpect(status().isCreated)
        assertThat(invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)).hasSize(1)
    }

    @Test
    fun `해당 org 의 ORG_ADMIN 은 초대를 생성한다`() {
        val orgAdmin = saveUser("orgadmin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = orgAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(orgAdmin.email)
        createInvite(session, orgA, "invitee@example.com").andExpect(status().isCreated)
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 초대 생성이 거부된다(격리)`() {
        val orgBAdmin = saveUser("orgb-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = orgBAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(orgBAdmin.email)
        createInvite(session, orgA, "invitee@example.com").andExpect(status().isForbidden)
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `일반 멤버는 초대 생성이 거부된다`() {
        val member = saveUser("plain-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        createInvite(session, orgA, "invitee@example.com").andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 초대 생성은 401 이다`() {
        // /api/orgs 세션 체인은 상태변경에 CSRF 를 강제하므로(하드닝), 인증 계층을 격리 검증하려면
        // 유효한 CSRF 토큰을 실어 CsrfFilter 를 통과시킨다 — 그래도 세션이 없으면 401 이다(토큰만으론 불충분).
        mockMvc
            .perform(
                post("/api/orgs/{orgId}/invitations", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"invitee@example.com","role":"MEMBER"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `사용자 베어러 토큰으로는 초대 목록을 읽을 수 없다(confused-deputy 차단)`() {
        // orgA 의 ORG_ADMIN 사용자가, 3rd-party 클라이언트에 openid·calendar.read 만 동의해 발급된 자신의
        // access_token 을 초대 관리 API 에 재사용해도, 세션 전용 게이트가 이를 거부한다(동의 범위 밖 PII 열람 차단).
        val orgAdmin = saveUser("bearer-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = orgAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(orgAdmin.id!!, "openid calendar.read")

        mockMvc
            .perform(
                get("/api/orgs/{orgId}/invitations", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    // ---- 목록 / 취소 org 격리 ----

    @Test
    fun `목록은 org 격리로 본인 org 의 PENDING 만 반환한다`() {
        val admin = saveUser("admin2@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)
        createInvite(session, orgA, "a1@example.com").andExpect(status().isCreated)
        createInvite(session, orgB, "b1@example.com").andExpect(status().isCreated)

        val body =
            session
                .perform(get("/api/orgs/{orgId}/invitations", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val rows = objectMapper.readTree(body)
        assertThat(rows.size()).isEqualTo(1)
        assertThat(rows.first().get("email").asText()).isEqualTo("a1@example.com")
        // 토큰/토큰해시는 응답에 절대 없어야 한다.
        assertThat(body).doesNotContain("token").doesNotContain("Hash")
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 목록을 볼 수 없다(격리)`() {
        val orgBAdmin = saveUser("orgb-admin2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = orgBAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(orgBAdmin.email)
        session.perform(get("/api/orgs/{orgId}/invitations", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `취소는 org 격리된다(타 org 경로로는 404)`() {
        val admin = saveUser("admin3@example.com", role = UserRole.ADMIN)
        val adminSession = login(admin.email)
        val created =
            createInvite(adminSession, orgA, "iso@example.com")
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val invitationId = objectMapper.readTree(created).get("id").asText()

        // orgB 의 ORG_ADMIN 이 orgB 경로로 orgA 초대 취소 시도 → 404(격리).
        val orgBAdmin = saveUser("orgb-admin3@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = orgBAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val bSession = login(orgBAdmin.email)
        bSession
            .perform(delete("/api/orgs/{orgId}/invitations/{id}", orgB, invitationId).with(csrf()))
            .andExpect(status().isNotFound)

        // 플랫폼 ADMIN 은 orgA 경로로 정상 취소.
        adminSession
            .perform(delete("/api/orgs/{orgId}/invitations/{id}", orgA, invitationId).with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(invitationRepository.findById(UUID.fromString(invitationId)).get().status)
            .isEqualTo(InvitationStatus.REVOKED.name)
    }

    // ---- 재발송(resend) ----

    @Test
    fun `해당 org 의 ORG_ADMIN 은 초대를 재발송하고 토큰이 회전된다`() {
        val orgAdmin = saveUser("resend-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = orgAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(orgAdmin.email)

        val createSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(createSlot)) } just Runs
        val created =
            createInvite(session, orgA, "resend-target@example.com")
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val invitationId = objectMapper.readTree(created).get("id").asText()
        val firstToken = Regex("token=([^\\s]+)").find(createSlot.captured.text!!)!!.groupValues[1]

        agePending(UUID.fromString(invitationId)) // 재발송 쿨다운 통과.

        val resendSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(resendSlot)) } just Runs
        session
            .perform(post("/api/orgs/{orgId}/invitations/{id}/resend", orgA, invitationId).with(csrf()))
            .andExpect(status().isOk)

        val secondToken = Regex("token=([^\\s]+)").find(resendSlot.captured.text!!)!!.groupValues[1]
        assertThat(secondToken).isNotEqualTo(firstToken)
        // 구 토큰은 회전으로 무효화된다(해시 교체).
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(firstToken))).isNull()
        assertThat(invitationRepository.findById(UUID.fromString(invitationId)).get().status)
            .isEqualTo(InvitationStatus.PENDING.name)
    }

    @Test
    fun `재발송은 org 격리된다(타 org 경로로는 404)`() {
        val admin = saveUser("resend-iso-admin@example.com", role = UserRole.ADMIN)
        val adminSession = login(admin.email)
        val created =
            createInvite(adminSession, orgA, "iso-target@example.com")
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val invitationId = objectMapper.readTree(created).get("id").asText()

        // orgB 의 ORG_ADMIN 이 orgB 경로로 orgA 초대 재발송 시도 → 404(격리).
        val orgBAdmin = saveUser("resend-orgb-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = orgBAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val bSession = login(orgBAdmin.email)
        bSession
            .perform(post("/api/orgs/{orgId}/invitations/{id}/resend", orgB, invitationId).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `재발송은 step-up 을 요구한다(auth_time 만료 시 401 REAUTH)`() {
        val admin = saveUser("resend-stepup@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)
        val created =
            createInvite(session, orgA, "stepup-target@example.com")
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val invitationId = objectMapper.readTree(created).get("id").asText()

        // auth_time 을 임계 초과로 되돌리면 민감 작업(@RequireRecentAuth)이 재인증을 요구한다.
        expireAuthTime(session)

        session
            .perform(post("/api/orgs/{orgId}/invitations/{id}/resend", orgA, invitationId).with(csrf()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    // ---- CSV 대량 초대(bulk) ----

    @Test
    fun `CSV 다행 초대는 행마다 PENDING 초대와 메일을 만든다`() {
        val orgAdmin = saveUser("bulk-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = orgAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(orgAdmin.email)

        // 로그인 알림 등 로그인 시점 메일과 분리하려고, 로그인 후 재스텁한 리스트로 이후 발송만 수집한다.
        val sent = mutableListOf<SimpleMailMessage>()
        every { mailSender.send(capture(sent)) } just Runs

        val csv =
            """
            b1@example.com
            b2@example.com,ORG_ADMIN
            b3@example.com,MEMBER,Engineering
            """.trimIndent()
        val body =
            bulkInvite(session, orgA, csv)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.rejected").value(0))
                .andReturn()
                .response.contentAsString

        val pending = invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)
        assertThat(pending).hasSize(3)
        assertThat(pending.map { it.email }).containsExactlyInAnyOrder("b1@example.com", "b2@example.com", "b3@example.com")
        assertThat(pending.first { it.email == "b2@example.com" }.role).isEqualTo(OrgRole.ORG_ADMIN.name)
        assertThat(pending.first { it.email == "b3@example.com" }.department).isEqualTo("Engineering")
        // 행마다 invite() 가 자기 트랜잭션을 커밋하고 메일을 보낸다(총 3통 — 각 수신자에게 1통씩).
        assertThat(sent).hasSize(3)
        assertThat(sent.map { it.to!!.single() }).containsExactlyInAnyOrder("b1@example.com", "b2@example.com", "b3@example.com")
        // 토큰/토큰해시는 bulk 응답에도 절대 없어야 한다.
        assertThat(body).doesNotContain("token").doesNotContain("Hash")
    }

    @Test
    fun `email 로 시작하는 첫 행은 헤더로 스킵된다`() {
        val admin = saveUser("bulk-header@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)

        val csv = "email,role,department\nh1@example.com\nh2@example.com,MEMBER"
        bulkInvite(session, orgA, csv)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.created").value(2))
        val pending = invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)
        assertThat(pending.map { it.email }).containsExactlyInAnyOrder("h1@example.com", "h2@example.com")
    }

    @Test
    fun `거부 행 번호는 헤더·빈 행을 포함한 원문 물리 행 기준이다(CRLF 원문 포함)`() {
        val admin = saveUser("bulk-line@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)

        // 1행 헤더, 2행 빈 행, 3행 잘못된 이메일, 4행 정상 — 스킵 후 재번호가 아니라 원문 1-base 를 유지해야 한다.
        val csv = "email,role,department\r\n\r\nnot-an-email\r\nline-ok@example.com\r\n"
        val body =
            bulkInvite(session, orgA, csv)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andReturn()
                .response.contentAsString

        val results = objectMapper.readTree(body).get("results")
        assertThat(results.get(0).get("line").asInt()).isEqualTo(3)
        assertThat(results.get(0).get("email").asText()).isEqualTo("not-an-email")
        assertThat(results.get(0).get("status").asText()).isEqualTo("REJECTED")
        assertThat(results.get(1).get("line").asInt()).isEqualTo(4)
        assertThat(results.get(1).get("email").asText()).isEqualTo("line-ok@example.com")
        assertThat(results.get(1).get("status").asText()).isEqualTo("CREATED")
    }

    @Test
    fun `빈 CSV·헤더만 있는 CSV·64KB 초과 CSV 는 400 이다`() {
        val admin = saveUser("bulk-input-limit@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)

        bulkInvite(session, orgA, "  \n\n").andExpect(status().isBadRequest)
        bulkInvite(session, orgA, "email,role,department\n").andExpect(status().isBadRequest)
        bulkInvite(session, orgA, "x".repeat(64 * 1024 + 1)).andExpect(status().isBadRequest)
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `일부 행이 거부돼도 나머지 행은 성공한다(행별 독립)`() {
        val admin = saveUser("bulk-partial@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)
        // 1행: 이미 활성 멤버(거부), 2행: 잘못된 이메일(거부), 3행: 잘못된 role(거부), 4행: 정상(성공).
        val member = saveUser("already-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val csv =
            """
            already-member@example.com
            not-an-email
            bad-role@example.com,SUPERADMIN
            ok-row@example.com,MEMBER,Sales
            """.trimIndent()
        val body =
            bulkInvite(session, orgA, csv)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.rejected").value(3))
                .andReturn()
                .response.contentAsString

        val results = objectMapper.readTree(body).get("results")
        // 행 번호는 CSV 원문 기준 — 거부 행의 위치·사유(서버 메시지 재사용)가 정직하게 보고된다.
        assertThat(results.get(0).get("line").asInt()).isEqualTo(1)
        assertThat(results.get(0).get("status").asText()).isEqualTo("REJECTED")
        assertThat(results.get(0).get("reason").asText()).contains("이미 이 조직의 멤버")
        assertThat(results.get(1).get("status").asText()).isEqualTo("REJECTED")
        assertThat(results.get(1).get("reason").asText()).contains("유효한 이메일")
        assertThat(results.get(2).get("status").asText()).isEqualTo("REJECTED")
        assertThat(results.get(2).get("reason").asText()).contains("role")
        assertThat(results.get(3).get("status").asText()).isEqualTo("CREATED")

        // 거부 행이 성공 행을 롤백하지 않는다 — 정상 행만 PENDING 으로 남는다.
        val pending = invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)
        assertThat(pending).hasSize(1)
        assertThat(pending.single().email).isEqualTo("ok-row@example.com")
        assertThat(pending.single().department).isEqualTo("Sales")
    }

    @Test
    fun `시간당 상한 초과 행은 rejected 로 보고되고 이전 성공 행은 유지된다`() {
        val admin = saveUser("bulk-rate@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)

        // maxPerHour 기본 20 — 22행을 보내면 앞 20행 성공, 뒤 2행은 상한 거부(행별 독립 — 성공분 유지).
        val csv = (1..22).joinToString("\n") { "rate-$it@example.com" }
        val body =
            bulkInvite(session, orgA, csv)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.total").value(22))
                .andExpect(jsonPath("$.created").value(20))
                .andExpect(jsonPath("$.rejected").value(2))
                .andReturn()
                .response.contentAsString

        val results = objectMapper.readTree(body).get("results")
        assertThat(results.get(20).get("status").asText()).isEqualTo("REJECTED")
        assertThat(results.get(20).get("reason").asText()).contains("한도")
        assertThat(results.get(21).get("status").asText()).isEqualTo("REJECTED")
        // 상한 거부가 이전 성공 행을 롤백하지 않는다.
        assertThat(invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name))
            .hasSize(20)
    }

    @Test
    fun `200행 초과 CSV 는 400 이다`() {
        val admin = saveUser("bulk-limit@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)

        val csv = (1..201).joinToString("\n") { "limit-$it@example.com" }
        bulkInvite(session, orgA, csv).andExpect(status().isBadRequest)
        // 상한 초과는 파싱 직후 전체 거부 — 아무 행도 처리되지 않는다.
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 대량 초대가 거부된다(격리)`() {
        val orgBAdmin = saveUser("bulk-orgb-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = orgBAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(orgBAdmin.email)
        bulkInvite(session, orgA, "x@example.com").andExpect(status().isForbidden)
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `사용자 베어러 토큰으로는 대량 초대할 수 없다(세션 전용)`() {
        val orgAdmin = saveUser("bulk-bearer@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = orgAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(orgAdmin.id!!, "openid")

        // bulk 는 @RequireRecentAuth 라 step-up 인터셉터가 authorize() 보다 먼저 돈다 — 베어러는 세션
        // auth_time 이 없어 REAUTH_REQUIRED(401)로 선차단된다(403 confused-deputy 분기 이전).
        // 어느 계층이든 베어러 대량 초대는 거부되며, 아무 행도 처리되지 않아야 한다.
        mockMvc
            .perform(
                post("/api/orgs/{orgId}/invitations/bulk", orgA)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"csv":"x@example.com"}"""),
            ).andExpect(status().isUnauthorized)
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `대량 초대는 step-up 을 요구한다(auth_time 만료 시 401 REAUTH)`() {
        val admin = saveUser("bulk-stepup@example.com", role = UserRole.ADMIN)
        val session = login(admin.email)
        expireAuthTime(session)

        bulkInvite(session, orgA, "stepup-bulk@example.com")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
        assertThat(invitationRepository.count()).isZero()
    }

    // ---- 수락 흐름 e2e ----

    @Test
    fun `초대 발송 로그인 수락 e2e 로 멤버십이 생성된다`() {
        val admin = saveUser("admin4@example.com", role = UserRole.ADMIN)
        val invitee = saveUser("invitee-e2e@example.com")
        val adminSession = login(admin.email)

        val tokenSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(tokenSlot)) } just Runs
        createInvite(adminSession, orgA, invitee.email).andExpect(status().isCreated)
        val token = Regex("token=([^\\s]+)").find(tokenSlot.captured.text!!)!!.groupValues[1]

        val inviteeSession = login(invitee.email)
        inviteeSession
            .perform(get("/orgs/invite/accept").param("token", token))
            .andExpect(status().isOk)
        inviteeSession
            .perform(
                post("/orgs/invite/accept").param("token", token).with(csrf()),
            ).andExpect(status().isOk)

        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, invitee.id!!)).isNotNull
        val invitations = invitationRepository.findAll()
        assertThat(invitations.single().status).isEqualTo(InvitationStatus.ACCEPTED.name)
    }

    @Test
    fun `다른 계정으로는 수락해도 멤버십이 생기지 않는다(하이재킹 차단)`() {
        val admin = saveUser("admin5@example.com", role = UserRole.ADMIN)
        val attacker = saveUser("attacker-e2e@example.com")
        val adminSession = login(admin.email)

        val tokenSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(tokenSlot)) } just Runs
        createInvite(adminSession, orgA, "victim-e2e@example.com").andExpect(status().isCreated)
        val token = Regex("token=([^\\s]+)").find(tokenSlot.captured.text!!)!!.groupValues[1]

        val attackerSession = login(attacker.email)
        // POST 는 페이지를 재렌더(200)하지만 멤버십은 생기지 않고 초대는 소비되지 않는다.
        attackerSession
            .perform(post("/orgs/invite/accept").param("token", token).with(csrf()))
            .andExpect(status().isOk)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, attacker.id!!)).isNull()
        assertThat(invitationRepository.findAll().single().status).isEqualTo(InvitationStatus.PENDING.name)
    }

    @Test
    fun `미인증 수락 페이지는 로그인으로 유도된다`() {
        mockMvc
            .perform(get("/orgs/invite/accept").param("token", "whatever"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrlPattern("**/login"))
    }

    @Test
    fun `초대 생성 후 조직이 정지되면 수락 페이지는 버튼 대신 안내를 노출한다`() {
        val admin = saveUser("admin-susp@example.com", role = UserRole.ADMIN)
        val invitee = saveUser("susp-invitee@example.com")
        val adminSession = login(admin.email)

        val tokenSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(tokenSlot)) } just Runs
        createInvite(adminSession, orgA, invitee.email).andExpect(status().isCreated)
        val token = Regex("token=([^\\s]+)").find(tokenSlot.captured.text!!)!!.groupValues[1]

        // 초대 생성 후 조직을 정지한다.
        organizationRepository.findById(orgA).get().let {
            it.status = "SUSPENDED"
            organizationRepository.save(it)
        }

        val inviteeSession = login(invitee.email)
        inviteeSession
            .perform(get("/orgs/invite/accept").param("token", token))
            .andExpect(status().isOk)
            .andExpect(model().attribute("state", "ORG_INACTIVE"))
            .andExpect(model().attribute("canAccept", false))
    }

    // ---- helpers ----

    /** 앱의 JWKSource 로 RS256 서명한 사용자 access_token(sub=users.id, 지정 scope). 위임 베어러를 모사한다. */
    private fun userBearerToken(
        userId: UUID,
        scope: String,
    ): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", scope)
                .build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
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

    private fun createInvite(
        session: WebSession,
        orgId: UUID,
        email: String,
        role: String = "MEMBER",
    ) = session.perform(
        post("/api/orgs/{orgId}/invitations", orgId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","role":"$role"}""")
            .with(csrf()),
    )

    /** CSV 는 개행을 포함하므로 objectMapper 로 JSON 이스케이프해 전송한다. */
    private fun bulkInvite(
        session: WebSession,
        orgId: UUID,
        csv: String,
    ) = session.perform(
        post("/api/orgs/{orgId}/invitations/bulk", orgId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("csv" to csv)))
            .with(csrf()),
    )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    /** 재발송 쿨다운을 지나게 하려고 PENDING 초대의 마지막 발송 시각(createdAt)을 과거로 돌린다. */
    private fun agePending(invitationId: UUID) {
        val inv = invitationRepository.findById(invitationId).get()
        inv.createdAt = Instant.now().minus(Duration.ofHours(2))
        invitationRepository.save(inv)
    }

    /** step-up 임계 초과를 모사한다(세션의 auth_time 을 과거로). */
    private fun expireAuthTime(session: WebSession) {
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
    }
}
