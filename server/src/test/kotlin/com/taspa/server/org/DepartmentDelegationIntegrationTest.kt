package com.taspa.server.org

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentDelegationRepository
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 부서 서브트리 위임 — **적대적 관점**의 통합 테스트.
 *
 * 이 기능은 인가 엔진에 새 권한 경로를 여는 것이라, "동작한다"보다 **"넘어가지 못한다"** 가 본체다.
 * 그래서 테스트 대부분이 공격 시나리오다: 자기 증식(부하를 관리자로 승격 → 전권), 경계 이탈(다른 본부
 * 사람 조회·수정), 인원 밀어내기(자기 부서원을 남의 부서로 이동), 자기 관리(자기 재직상태 조작).
 *
 * 조직도: 본부A(개발팀A, 지원팀A) / 본부B. 위임자는 개발팀A 를 받는다.
 */
class DepartmentDelegationIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var delegationRepository: DepartmentDelegationRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgId: UUID
    private lateinit var hqA: Department
    private lateinit var devA: Department
    private lateinit var supportA: Department
    private lateinit var hqB: Department

    private lateinit var orgAdmin: User
    private lateinit var lead: User // 위임자 — devA 담당
    private lateinit var teammate: User // devA 소속(위임 범위 안)
    private lateinit var outsider: User // hqB 소속(위임 범위 밖)
    private lateinit var unassigned: User // 부서 미배정

    @BeforeEach
    fun setUp() {
        delegationRepository.deleteAll()
        membershipRepository.deleteAll()
        departmentRepository.deleteAllInBatch()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs

        orgId = organizationRepository.save(Organization(slug = "deleg", name = "위임 테스트")).id!!
        hqA = departmentRepository.save(Department(orgId = orgId, name = "본부A"))
        devA = departmentRepository.save(Department(orgId = orgId, parentId = hqA.id, name = "개발팀A"))
        supportA = departmentRepository.save(Department(orgId = orgId, parentId = hqA.id, name = "지원팀A"))
        hqB = departmentRepository.save(Department(orgId = orgId, name = "본부B"))

        orgAdmin = member("deleg-admin@example.com", OrgRole.ORG_ADMIN, null)
        lead = member("deleg-lead@example.com", OrgRole.MEMBER, devA.id)
        teammate = member("deleg-mate@example.com", OrgRole.MEMBER, devA.id)
        outsider = member("deleg-outsider@example.com", OrgRole.MEMBER, hqB.id)
        unassigned = member("deleg-unassigned@example.com", OrgRole.MEMBER, null)
    }

    // ── 정상 동작 ────────────────────────────────────────────────────

    @Test
    fun `조직관리자가 위임을 부여하면 위임자가 자기 부서원을 관리할 수 있다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgId, teammate.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"선임"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        assertThat(membershipRepository.findByOrgIdAndUserId(orgId, teammate.id!!)!!.jobTitle).isEqualTo("선임")
    }

    @Test
    fun `위임 전에는 같은 요청이 거부된다(위임이 실제로 권한의 근거임을 보인다)`() {
        // 대조군이 없으면 "원래 통과하던 것"과 구별되지 않는다.
        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgId, teammate.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"선임"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `상위 부서를 위임받으면 하위 부서원까지 관리한다(서브트리)`() {
        grantDelegation(lead, hqA.id!!).andExpect(status().isCreated)

        // 개발팀A(직계 하위)와 지원팀A(형제 하위) 둘 다.
        val supportMember = member("deleg-support@example.com", OrgRole.MEMBER, supportA.id)
        login(lead.email)
            .perform(
                get("/api/orgs/{orgId}/members/{userId}/history", orgId, supportMember.id),
            ).andExpect(status().isOk)
    }

    // ── 경계 이탈 차단 ────────────────────────────────────────────────

    @Test
    fun `★위임 범위 밖 구성원은 조회도 수정도 못 한다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        val session = login(lead.email)
        session
            .perform(get("/api/orgs/{orgId}/members/{userId}/history", orgId, outsider.id))
            .andExpect(status().isForbidden)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgId, outsider.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"침입"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `부서 미배정 구성원은 어떤 위임자도 관리하지 못한다`() {
        // 미배정은 "아무나 관리 가능"이 아니라 "조직관리자만 관리 가능"이어야 한다 —
        // 그렇지 않으면 배정을 지우는 것만으로 남의 위임 범위에 넣을 수 있다.
        grantDelegation(lead, hqA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(get("/api/orgs/{orgId}/members/{userId}/history", orgId, unassigned.id))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `★멤버 목록은 자기 서브트리로 좁혀진다(응답에 남의 부서 사람이 실리지 않는다)`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        val body =
            login(lead.email)
                .perform(get("/api/orgs/{orgId}/members", orgId))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertThat(body).contains(teammate.email)
        assertThat(body).contains(lead.email)
        // 인가만 통과시키고 화면에서 걸렀다면 여기에 이미 실려 있었을 것이다.
        assertThat(body).doesNotContain(outsider.email)
        assertThat(body).doesNotContain(orgAdmin.email)
        assertThat(body).doesNotContain(unassigned.email)
    }

    // ── 자기 증식 차단 ────────────────────────────────────────────────

    @Test
    fun `★위임자는 부서원을 조직관리자로 승격시킬 수 없다(위임이 전권이 되는 경로)`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgId, teammate.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(membershipRepository.findByOrgIdAndUserId(orgId, teammate.id!!)!!.role).isEqualTo(OrgRole.MEMBER.name)
    }

    @Test
    fun `★위임자는 위임을 줄 수 없다(경계의 무한 증식 차단)`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                post("/api/orgs/{orgId}/delegations", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"${teammate.id}","departmentId":"${devA.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `위임자는 초대·부서 편집·식대 정책을 건드릴 수 없다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)
        val session = login(lead.email)

        session
            .perform(
                post("/api/orgs/{orgId}/invitations", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"new@example.com"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
        session
            .perform(
                post("/api/orgs/{orgId}/departments", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"새 팀"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
        session
            .perform(
                put("/api/orgs/{orgId}/meal-policy", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"perMealLimitMinor":50000,"dailyMealCount":3,"monthlyCapMinor":900000,
                        "breakfastStart":"07:00","breakfastEnd":"10:00","lunchStart":"11:00","lunchEnd":"14:00",
                        "dinnerStart":"17:00","dinnerEnd":"21:00"}""",
                    ).with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `★위임자는 자기 부서원을 다른 본부로 밀어낼 수 없다(대상은 둘인데 엔진은 하나만 본다)`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgId, teammate.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":"${hqB.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(membershipRepository.findByOrgIdAndUserId(orgId, teammate.id!!)!!.departmentId).isEqualTo(devA.id)
    }

    @Test
    fun `위임자는 부서원을 미배정으로도 내보낼 수 없다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgId, teammate.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":null}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `위임자는 서브트리 안에서는 배정을 옮길 수 있다`() {
        grantDelegation(lead, hqA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgId, teammate.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":"${supportA.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        assertThat(membershipRepository.findByOrgIdAndUserId(orgId, teammate.id!!)!!.departmentId).isEqualTo(supportA.id)
    }

    @Test
    fun `★위임자는 자기 자신을 대상으로 권한을 쓸 수 없다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(lead.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgId, lead.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"자가 승진"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    // ── 상호배제 ─────────────────────────────────────────────────────

    @Test
    fun `조직관리자에게는 위임을 줄 수 없다`() {
        grantDelegation(orgAdmin, devA.id!!)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("조직관리자")))
    }

    @Test
    fun `★위임자가 조직관리자로 승격되면 위임이 자동 해제된다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)
        assertThat(delegationRepository.findByOrgIdAndUserId(orgId, lead.id!!)).isNotNull()

        login(orgAdmin.email)
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgId, lead.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        // 남겨 두면 "위임을 회수했으니 안전하다"는 오해가 생긴다(실제로는 전권이 이미 있다).
        assertThat(delegationRepository.findByOrgIdAndUserId(orgId, lead.id!!)).isNull()
    }

    @Test
    fun `비멤버·비활성 멤버에게는 위임을 줄 수 없다`() {
        val stranger =
            userRepository.save(
                User(email = "deleg-stranger@example.com", passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        grantDelegation(stranger, devA.id!!).andExpect(status().isNotFound)

        val suspended = member("deleg-suspended@example.com", OrgRole.MEMBER, devA.id)
        val membership = membershipRepository.findByOrgIdAndUserId(orgId, suspended.id!!)!!
        membership.status = "SUSPENDED"
        membershipRepository.save(membership)
        grantDelegation(suspended, devA.id!!).andExpect(status().isBadRequest)
    }

    @Test
    fun `타 조직 부서로는 위임할 수 없다`() {
        val otherOrg = organizationRepository.save(Organization(slug = "deleg-other", name = "다른 조직")).id!!
        val otherDept = departmentRepository.save(Department(orgId = otherOrg, name = "남의 부서"))

        grantDelegation(lead, otherDept.id!!).andExpect(status().isNotFound)
    }

    // ── 회수 ─────────────────────────────────────────────────────────

    @Test
    fun `위임을 회수하면 권한이 즉시 사라진다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)
        login(lead.email)
            .perform(get("/api/orgs/{orgId}/members/{userId}/history", orgId, teammate.id))
            .andExpect(status().isOk)

        login(orgAdmin.email)
            .perform(
                delete("/api/orgs/{orgId}/delegations/{userId}", orgId, lead.id).with(csrf()),
            ).andExpect(status().isNoContent)

        login(lead.email)
            .perform(get("/api/orgs/{orgId}/members/{userId}/history", orgId, teammate.id))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `부서를 삭제하면 그 위임도 사라진다`() {
        grantDelegation(lead, supportA.id!!).andExpect(status().isCreated)
        assertThat(delegationRepository.count()).isEqualTo(1)

        departmentRepository.deleteById(supportA.id!!)
        departmentRepository.flush()

        assertThat(delegationRepository.count()).isZero()
    }

    @Test
    fun `위임 목록은 조직관리자만 볼 수 있다`() {
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)

        login(orgAdmin.email)
            .perform(get("/api/orgs/{orgId}/delegations", orgId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].departmentName").value("개발팀A"))

        login(teammate.email)
            .perform(get("/api/orgs/{orgId}/delegations", orgId))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `★★위임자가 플랫폼 관리자로 승격되면 목록이 더 이상 잘리지 않는다`() {
        // 부여 시점 상호배제는 "관리자에게 위임 금지"를 막지만, **위임 부여 → 이후 플랫폼 승격** 순서는
        // 막지 못한다(플랫폼 역할은 org 밖에서 바뀌므로 detachOnPromotion 이 닿지 않는다).
        // 그 상태에서 응답을 위임 범위로 좁히면 관리자가 **잘린 명단을 전체로 오인**한다 —
        // 화면에는 아무 표시도 없어서 "이 조직엔 두 명뿐"이라고 믿게 된다.
        grantDelegation(lead, devA.id!!).andExpect(status().isCreated)
        login(lead.email)
            .perform(get("/api/orgs/{orgId}/members", orgId))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
            .let { assertThat(it).doesNotContain(outsider.email) }

        // 플랫폼 관리자로 승격(org 밖의 변경 — 위임 행은 그대로 남는다).
        lead.role = UserRole.ADMIN.name
        userRepository.save(lead)

        val body =
            login(lead.email)
                .perform(get("/api/orgs/{orgId}/members", orgId))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        // 이제는 더 넓은 근거로 통과했으므로 전체가 보여야 한다.
        assertThat(body).contains(outsider.email)
        assertThat(body).contains(unassigned.email)
        assertThat(body).contains(orgAdmin.email)
    }

    // ---- helpers ----

    private fun grantDelegation(
        target: User,
        departmentId: UUID,
    ) = login(orgAdmin.email).perform(
        post("/api/orgs/{orgId}/delegations", orgId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":"${target.id}","departmentId":"$departmentId"}""")
            .with(csrf()),
    )

    private fun member(
        email: String,
        role: OrgRole,
        departmentId: UUID?,
    ): User {
        val user =
            userRepository.save(
                User(
                    email = email,
                    passwordHash = passwordEncoder.encode(password),
                    emailVerified = true,
                    role = UserRole.USER.name,
                ),
            )
        val membership =
            membershipRepository.save(
                OrgMembership(orgId = orgId, userId = user.id!!, role = role.name),
            )
        if (departmentId != null) {
            membership.departmentId = departmentId
            membershipRepository.save(membership)
        }
        return user
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
