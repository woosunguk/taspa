package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.iam.IamGroupMemberRepository
import com.taspa.server.domain.iam.IamInlinePolicyRepository
import com.taspa.server.domain.iam.IamPrincipalGroupRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 조직 커스텀 역할 — **권한을 만드는 기능**이라 테스트가 본체다.
 *
 * 그전까지 조직 역할은 MEMBER/ORG_ADMIN 둘뿐이라, 중간 권한을 주려면 조직 전체 관리자를 줘야 했다.
 * 이 기능은 그 중간을 만드는데, 잘못 만들면 **권한상승 통로**가 된다. 그래서 여기서 확인하는 것은
 * "역할이 동작하는가"만이 아니라 **"역할이 자기보다 커질 수 없는가"** 다.
 */
class OrgRoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var groupRepository: IamPrincipalGroupRepository

    @Autowired lateinit var groupMemberRepository: IamGroupMemberRepository

    @Autowired lateinit var inlineRepository: IamInlinePolicyRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID
    private lateinit var adminA: User
    private lateinit var adminB: User
    private lateinit var member: User
    private lateinit var otherMember: User

    @BeforeEach
    fun setUp() {
        groupMemberRepository.deleteAllInBatch()
        inlineRepository.deleteAllInBatch()
        groupRepository.deleteAllInBatch()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgA = organizationRepository.save(Organization(slug = "role-a", name = "역할 조직 A")).id!!
        orgB = organizationRepository.save(Organization(slug = "role-b", name = "역할 조직 B")).id!!

        adminA = saveUser("role-admin-a@example.com")
        adminB = saveUser("role-admin-b@example.com")
        member = saveUser("role-member@example.com")
        otherMember = saveUser("role-other@example.com")

        membershipRepository.save(OrgMembership(orgId = orgA, userId = adminA.id!!, role = OrgRole.ORG_ADMIN.name))
        membershipRepository.save(OrgMembership(orgId = orgB, userId = adminB.id!!, role = OrgRole.ORG_ADMIN.name))
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        membershipRepository.save(OrgMembership(orgId = orgB, userId = otherMember.id!!, role = OrgRole.MEMBER.name))
    }

    // ---- 1. 역할이 실제로 권한을 준다 ----

    @Test
    fun `★역할을 받은 일반 구성원이 그 능력만큼 통과한다`() {
        // 대조군 먼저 — 역할이 없으면 구성원 목록은 막힌다.
        login(member.email)
            .perform(get("/api/orgs/{orgId}/members", orgA))
            .andExpect(status().isForbidden)

        val roleId = createRole(orgA, "인사 담당", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)

        // 이제 통과한다 — 저장 정책이 엔진 판정에 합류했다는 직접 증거다.
        login(member.email)
            .perform(get("/api/orgs/{orgId}/members", orgA))
            .andExpect(status().isOk)
    }

    @Test
    fun `★역할에 없는 능력은 여전히 막힌다(부여한 만큼만 열린다)`() {
        val roleId = createRole(orgA, "조회 전용", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)

        // 목록은 되지만 초대는 안 된다.
        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)
        session.perform(get("/api/orgs/{orgId}/invitations", orgA)).andExpect(status().isForbidden)
    }

    // ---- 1-1. 역할은 **지금도 그 조직 사람일 때만** 유효하다 ----
    //
    // ★부여 시점 검사(`assign` 의 isActiveMember)만으로는 부족하다. 부여는 `iam_group_members` 행이고
    //   조직에서 제거해도 그 행은 남는다(V27 CASCADE 는 users·groups 삭제에만 걸린다).
    //   그래서 런타임 게이트(`PrincipalPolicyResolver.activeOrgIdsOf`)가 본선이다.
    // ★세 테스트 모두 **차단 직전에 200 이었음**을 함께 단언한다 — 그게 없으면 "원래부터 막혀 있었다"와
    //   구별되지 않아 게이트가 원인임을 증명하지 못한다.

    @Test
    fun `★조직에서 제거되면 역할 권한도 사라진다(퇴사자)`() {
        val roleId = createRole(orgA, "인사 담당", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)
        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)

        membershipRepository.delete(membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!)

        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `★멤버십이 정지되면 역할 권한도 사라진다(SCIM active=false)`() {
        val roleId = createRole(orgA, "인사 담당", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)
        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)

        val membership = membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!
        membership.status = MembershipStatus.SUSPENDED.name
        membershipRepository.save(membership)

        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `★조직이 정지되면 역할 권한도 사라진다`() {
        val roleId = createRole(orgA, "인사 담당", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)
        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)

        val org = organizationRepository.findById(orgA).orElseThrow()
        org.status = OrgStatus.SUSPENDED.name
        organizationRepository.save(org)

        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `역할을 삭제하면 인라인 정책 행도 남지 않는다`() {
        val roleId = createRole(orgA, "임시", listOf("org:ListMembers"))
        login(adminA.email)
            .perform(delete("/api/orgs/{orgId}/roles/{roleId}", orgA, roleId).with(csrf()))
            .andExpect(status().isNoContent)

        // `iam_inline_policies` 에는 principal FK 가 없어 CASCADE 로 지워지지 않는다 — 명시 삭제의 회귀.
        assertThat(
            inlineRepository.findByPrincipalTypeAndPrincipalIdAndName(
                IamPrincipalType.GROUP,
                roleId,
                "taspa:org-role",
            ),
        ).isNull()
    }

    @Test
    fun `설명이 너무 길면 400 으로 거절한다(409 무관 오류가 아니라)`() {
        val session = login(adminA.email)
        session
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"긴 설명","description":"${"가".repeat(513)}","actions":["org:ListMembers"]}"""),
            ).andExpect(status().isBadRequest)

        // 대조군 — 상한 이하는 통과한다.
        session
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"짧은 설명","description":"${"가".repeat(512)}","actions":["org:ListMembers"]}"""),
            ).andExpect(status().isOk)
    }

    @Test
    fun `역할 이름에 쉼표는 쓸 수 없다(연동 선언 포맷의 구분자)`() {
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"인사,담당","actions":["org:ListMembers"]}"""),
            ).andExpect(status().isBadRequest)
    }

    // ---- 2. 역할이 자기보다 커질 수 없다 ----

    @Test
    fun `★초대 능력만으로는 ORG_ADMIN 을 초대할 수 없다(자기 증식 우회 차단)`() {
        // '초대 보내기'는 HR 담당에게 주는 것이 지극히 자연스럽다 — 그래서 이 경로가 위험했다.
        val roleId = createRole(orgA, "HR 담당", listOf("org:CreateInvitation", "org:ListInvitations"))
        assign(orgA, roleId, member.id!!)
        val session = login(member.email)

        // ★대조군 먼저 — 일반 구성원 초대는 여전히 된다. 이게 없으면 "초대를 통째로 막았다"와 구별되지 않는다.
        session
            .perform(
                post("/api/orgs/{orgId}/invitations", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"new-hire@example.com","role":"MEMBER"}"""),
            ).andExpect(status().isCreated)

        // 자기가 통제하는 두 번째 주소를 ORG_ADMIN 으로 초대 → 거부.
        // 이 한 줄이 열려 있으면 수락 즉시 조직 전체 권한을 스스로 획득한다.
        session
            .perform(
                post("/api/orgs/{orgId}/invitations", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"me-alt@example.com","role":"ORG_ADMIN"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `★CSV 대량 초대의 ORG_ADMIN 행도 거절된다(행별)`() {
        val roleId = createRole(orgA, "HR 담당", listOf("org:CreateInvitation", "org:BulkInvite"))
        assign(orgA, roleId, member.id!!)

        // 부분 성공 모델이라 정상 행은 통과하고 승격 행만 REJECTED 여야 한다.
        login(member.email)
            .perform(
                post("/api/orgs/{orgId}/invitations/bulk", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"csv":"ok@example.com,MEMBER\nevil@example.com,ORG_ADMIN"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.results[0].status").value("CREATED"))
            .andExpect(jsonPath("$.results[1].status").value("REJECTED"))
    }

    @Test
    fun `조직관리자는 ORG_ADMIN 초대를 계속 할 수 있다(대조군)`() {
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/invitations", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"co-admin@example.com","role":"ORG_ADMIN"}"""),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `★자기 증식 능력은 부여할 수 없다(역할 관리·역할 변경·위임 부여)`() {
        // 이 셋이 열리면 한 번 부여된 역할이 스스로 조직 전체 권한으로 자란다.
        listOf("org:ManageRoles", "org:ChangeMemberRole", "org:ManageDelegation").forEach { action ->
            login(adminA.email)
                .perform(
                    post("/api/orgs/{orgId}/roles", orgA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"자라는 역할 $action","actions":["$action"]}"""),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `★플랫폼 능력은 부여할 수 없다`() {
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"플랫폼 탈취","actions":["platform:AdministerOrg","org:ListMembers"]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `모르는 능력은 조용히 버리지 않고 요청 전체를 거절한다`() {
        // 조용히 버리면 화면은 "권한을 줬다"고 믿는데 실제로는 안 준 상태가 되고 아무도 그 차이를 모른다.
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"오타 역할","actions":["org:ListMembers","org:Nonexistent"]}"""),
            ).andExpect(status().isBadRequest)
    }

    // ---- 3. 테넌시 ----

    @Test
    fun `★A 조직 역할은 B 조직 자원에 아무 권한도 주지 않는다`() {
        val roleId = createRole(orgA, "A 조회", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)
        // 같은 사람을 B 조직에도 넣는다 — 역할은 A 것뿐이다.
        membershipRepository.save(OrgMembership(orgId = orgB, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)
        // ResourceOrg 정확일치 조건이 B 를 막는다.
        session.perform(get("/api/orgs/{orgId}/members", orgB)).andExpect(status().isForbidden)
    }

    @Test
    fun `타 조직의 역할은 존재조차 알리지 않는다(404, 열거 방지)`() {
        val roleId = createRole(orgA, "A 역할", listOf("org:ListMembers"))
        // B 관리자가 자기 org 경로로 A 의 roleId 를 찔러 본다.
        login(adminB.email)
            .perform(get("/api/orgs/{orgId}/roles/{roleId}", orgB, roleId))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `그 조직의 구성원이 아닌 사람에게는 역할을 줄 수 없다`() {
        val roleId = createRole(orgA, "A 조회", listOf("org:ListMembers"))
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/roles/{roleId}/members", orgA, roleId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"${otherMember.id}"}"""),
            ).andExpect(status().isBadRequest)
    }

    // ---- 4. 역할 관리 자체의 인가 ----

    @Test
    fun `일반 구성원은 역할을 만들 수 없다`() {
        login(member.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"내가 만든 역할","actions":["org:ListMembers"]}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `★역할을 받아도 역할 관리 권한은 생기지 않는다(자기 증식 최종 확인)`() {
        // org:ManageRoles 는 부여 불가 목록에 있지만, 부여 가능한 능력을 다 모아도 역할 관리에는
        // 닿지 못한다는 것을 끝에서 한 번 더 확인한다.
        val roleId = createRole(orgA, "만능에 가까운 역할", listOf("org:ListMembers", "org:ListInvitations", "org:ReadAudit"))
        assign(orgA, roleId, member.id!!)

        login(member.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"2세대 역할","actions":["org:ListMembers"]}"""),
            ).andExpect(status().isForbidden)
    }

    // ---- 5. 편집·해제 ----

    @Test
    fun `역할을 수정하면 권한이 즉시 바뀐다`() {
        val roleId = createRole(orgA, "가변 역할", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)
        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)

        // 능력을 다른 것으로 교체 — 구성원 조회는 빠진다.
        login(adminA.email)
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/api/orgs/{orgId}/roles/{roleId}", orgA, roleId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"가변 역할","actions":["org:ReadAudit"]}"""),
            ).andExpect(status().isOk)

        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `역할을 해제하면 권한이 사라진다`() {
        val roleId = createRole(orgA, "임시 역할", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)
        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)

        login(adminA.email)
            .perform(
                delete("/api/orgs/{orgId}/roles/{roleId}/members/{userId}", orgA, roleId, member.id).with(csrf()),
            ).andExpect(status().isOk)

        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `역할을 삭제하면 부여도 함께 사라진다`() {
        val roleId = createRole(orgA, "삭제될 역할", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)

        login(adminA.email)
            .perform(delete("/api/orgs/{orgId}/roles/{roleId}", orgA, roleId).with(csrf()))
            .andExpect(status().isNoContent)

        assertThat(groupMemberRepository.findByGroupId(roleId)).isEmpty()
        login(member.email).perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    // ---- 6. 카탈로그 ----

    @Test
    fun `부여 가능 능력 목록에 자기 증식·플랫폼 능력이 없다`() {
        val body =
            login(adminA.email)
                .perform(get("/api/orgs/{orgId}/roles/grantable-actions", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertThat(body).contains("org:ListMembers")
        assertThat(body).doesNotContain("org:ManageRoles")
        assertThat(body).doesNotContain("org:ChangeMemberRole")
        assertThat(body).doesNotContain("org:ManageDelegation")
        assertThat(body).doesNotContain("platform:")
        assertThat(body).doesNotContain("meal:Redeem")
    }

    @Test
    fun `능력을 하나도 고르지 않으면 거절된다`() {
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"빈 역할","actions":[]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `같은 이름의 역할은 만들 수 없다`() {
        createRole(orgA, "중복 이름", listOf("org:ListMembers"))
        login(adminA.email)
            .perform(
                post("/api/orgs/{orgId}/roles", orgA)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"중복 이름","actions":["org:ReadAudit"]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `목록은 역할과 부여 인원을 함께 보여준다`() {
        val roleId = createRole(orgA, "집계 역할", listOf("org:ListMembers"))
        assign(orgA, roleId, member.id!!)

        login(adminA.email)
            .perform(get("/api/orgs/{orgId}/roles", orgA))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("집계 역할"))
            .andExpect(jsonPath("$[0].memberCount").value(1))
            .andExpect(jsonPath("$[0].actions[0]").value("org:ListMembers"))
    }

    // ---- helpers ----

    private fun createRole(
        orgId: UUID,
        name: String,
        actions: List<String>,
    ): UUID {
        val body =
            login(adminEmailOf(orgId))
                .perform(
                    post("/api/orgs/{orgId}/roles", orgId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","actions":[${actions.joinToString(",") { "\"$it\"" }}]}""",
                        ),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return UUID.fromString(objectMapper.readTree(body).get("id").asText())
    }

    private fun assign(
        orgId: UUID,
        roleId: UUID,
        userId: UUID,
    ) {
        login(adminEmailOf(orgId))
            .perform(
                post("/api/orgs/{orgId}/roles/{roleId}/members", orgId, roleId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"$userId"}"""),
            ).andExpect(status().isOk)
    }

    private fun adminEmailOf(orgId: UUID) = if (orgId == orgA) adminA.email else adminB.email

    private fun saveUser(email: String): User =
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
