package com.taspa.server.org

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgInvitationRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/**
 * 초대·CSV 가 **구조적 부서 배정**까지 잇는지 검증한다.
 *
 * 왜 별도 테스트인가: `org_memberships.department`(자유 라벨)와 `department_id`(FK)는 다른 축인데,
 * 부서별 식대 정책이 보는 것은 FK 쪽뿐이다. 초대가 라벨만 채우던 동안 개발팀 재정의(1식 18,000원)를
 * 만들어 두고 개발팀 신입을 초대하면 **그 사람만 조직 기본값으로 결제**됐고, 그 사실을 알려 주는
 * 신호가 아무 데도 없었다. 여기서 그 연결을 못 박는다.
 */
class DepartmentBindingIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var invitationRepository: OrgInvitationRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var invitationService: OrgInvitationService

    @Autowired lateinit var departmentBinder: DepartmentBinder

    @Autowired lateinit var organizationService: OrganizationService

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private lateinit var orgId: UUID
    private lateinit var devTeam: Department

    @BeforeEach
    fun setUp() {
        invitationRepository.deleteAll()
        membershipRepository.deleteAll()
        departmentRepository.deleteAllInBatch()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs

        orgId = organizationRepository.save(Organization(slug = "dept-bind", name = "부서 연결")).id!!
        devTeam = departmentRepository.save(Department(orgId = orgId, name = "개발팀"))
    }

    @Test
    fun `부서를 지정한 초대는 department_id 를 실어 나른다`() {
        val view = invitationService.invite(orgId, "bind-a@example.com", null, null, null, devTeam.id)

        assertThat(view.departmentId).isEqualTo(devTeam.id)
        assertThat(invitationRepository.findById(view.id).orElseThrow().departmentId).isEqualTo(devTeam.id)
    }

    @Test
    fun `★부서 이름만 준 초대(CSV·SCIM 경로)도 이름이 유일하면 구조 배정까지 잇는다`() {
        // CSV 는 이름만 나른다. 이름이 조직도의 부서와 정확히 하나 일치하면 이어 준다 —
        // 그래야 HR 이 붙여넣은 목록으로 입사한 사람들이 부서 정책을 받는다.
        val view = invitationService.invite(orgId, "bind-b@example.com", null, "개발팀", null)

        assertThat(view.department).isEqualTo("개발팀")
        assertThat(view.departmentId).isEqualTo(devTeam.id)
    }

    @Test
    fun `★같은 이름의 부서가 둘이면 잇지 않는다(틀린 부서의 예산을 쓰지 않는다)`() {
        // 부서 이름은 형제 사이에서만 유일하다 — 트리 어딘가에 같은 이름이 둘 있을 수 있다.
        // 아무 쪽이나 고르면 절반의 확률로 다른 부서의 식대 예산을 쓴다.
        val domestic = departmentRepository.save(Department(orgId = orgId, name = "국내본부"))
        val overseas = departmentRepository.save(Department(orgId = orgId, name = "해외본부"))
        departmentRepository.save(Department(orgId = orgId, parentId = domestic.id, name = "영업팀"))
        departmentRepository.save(Department(orgId = orgId, parentId = overseas.id, name = "영업팀"))

        val view = invitationService.invite(orgId, "bind-c@example.com", null, "영업팀", null)

        // 라벨은 남는다(화면에 부서 이름이 보인다) — 구조 배정만 비운다.
        assertThat(view.department).isEqualTo("영업팀")
        assertThat(view.departmentId).isNull()
    }

    @Test
    fun `조직도에 없는 이름은 라벨로만 남는다`() {
        val view = invitationService.invite(orgId, "bind-d@example.com", null, "없는팀", null)

        assertThat(view.department).isEqualTo("없는팀")
        assertThat(view.departmentId).isNull()
    }

    @Test
    fun `타 조직 부서 id 로는 초대할 수 없다`() {
        val otherOrg = organizationRepository.save(Organization(slug = "dept-bind-other", name = "다른 조직")).id!!
        val otherDept = departmentRepository.save(Department(orgId = otherOrg, name = "남의 부서"))

        val failure =
            runCatching {
                invitationService.invite(orgId, "bind-e@example.com", null, null, null, otherDept.id)
            }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `binder 는 명시 id 를 이름 해석보다 우선한다`() {
        // 화면이 부서 선택기로 정확히 고른 값이 있으면 라벨 문자열이 무엇이든 그것을 쓴다.
        val other = departmentRepository.save(Department(orgId = orgId, name = "기획팀"))

        val resolved = departmentBinder.resolve(orgId, other.id, "개발팀")

        assertThat(resolved).isEqualTo(other.id)
    }

    @Test
    fun `대소문자가 달라도 이름이 유일하면 잇는다`() {
        departmentRepository.save(Department(orgId = orgId, name = "Sales"))

        assertThat(departmentBinder.resolve(orgId, null, "sales")).isNotNull()
    }

    @Test
    fun `이미 배정된 멤버의 부서를 초대 수락이 덮어쓰지 않는다`() {
        // 사람의 부서가 초대 수락으로 조용히 바뀌면 그 사람의 식대 정책이 함께 바뀐다.
        // 초대가 나른 값은 **미배정일 때만** 채우는 것이 옳다.
        val planning = departmentRepository.save(Department(orgId = orgId, name = "기획팀"))
        val user =
            userRepository.save(
                User(
                    email = "bind-existing@example.com",
                    passwordHash = passwordEncoder.encode("SecureP@ssw0rd123"),
                    emailVerified = true,
                ),
            )
        organizationService.upsertMember(
            orgId,
            com.taspa.server.org.dto
                .MembershipRequest(userId = user.id!!, role = "MEMBER", departmentId = planning.id),
        )

        organizationService.upsertMember(
            orgId,
            com.taspa.server.org.dto
                .MembershipRequest(userId = user.id!!, role = "MEMBER", departmentId = devTeam.id),
        )

        assertThat(membershipRepository.findByOrgIdAndUserId(orgId, user.id!!)!!.departmentId).isEqualTo(planning.id)
    }
}
