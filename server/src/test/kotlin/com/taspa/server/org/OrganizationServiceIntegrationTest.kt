package com.taspa.server.org

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.common.exception.AuthException
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.dto.MembershipRequest
import com.taspa.server.org.dto.OrgCreateRequest
import com.taspa.server.org.dto.OrgUpdateRequest
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/**
 * 조직 테넌시(Phase 0-A) 통합 테스트 — 조직 CRUD, 멤버십 upsert/역할/제거, 마지막 ORG_ADMIN 자기보호,
 * JIT 멤버십 멱등성.
 */
class OrganizationServiceIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationService: OrganizationService

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    @BeforeEach
    fun setUp() {
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun newUser(email: String): UUID =
        userRepository.save(User(email = email, passwordHash = passwordEncoder.encode("x"), emailVerified = true)).id!!

    @Test
    fun `조직 생성은 name 에서 slug 를 정규화하고 중복 slug 는 거부한다`() {
        val org = organizationService.create(OrgCreateRequest(name = "Acme  Corp!!"))
        assertThat(org.slug).isEqualTo("acme-corp")
        assertThat(organizationRepository.existsBySlug("acme-corp")).isTrue()

        assertThatThrownBy { organizationService.create(OrgCreateRequest(slug = "acme-corp", name = "Other")) }
            .isInstanceOf(AuthException::class.java)
    }

    @Test
    fun `멤버십 upsert 는 생성 후 갱신하고 역할을 바꾼다`() {
        val orgId = organizationService.create(OrgCreateRequest(name = "Org")).id
        val userId = newUser("m1@example.com")

        val created = organizationService.upsertMember(orgId, MembershipRequest(userId = userId, role = "MEMBER"))
        assertThat(created.role).isEqualTo("MEMBER")
        assertThat(membershipRepository.existsByOrgIdAndUserId(orgId, userId)).isTrue()

        val updated = organizationService.changeRole(orgId, userId, "ORG_ADMIN")
        assertThat(updated.role).isEqualTo("ORG_ADMIN")
        assertThat(organizationService.isOrgAdmin(orgId, userId)).isTrue()
    }

    @Test
    fun `마지막 ORG_ADMIN 은 강등·제거할 수 없다`() {
        val orgId = organizationService.create(OrgCreateRequest(name = "Guard")).id
        val admin1 = newUser("a1@example.com")
        organizationService.upsertMember(orgId, MembershipRequest(userId = admin1, role = "ORG_ADMIN"))

        // 유일한 관리자 강등 금지.
        assertThatThrownBy { organizationService.changeRole(orgId, admin1, "MEMBER") }
            .isInstanceOf(AuthException::class.java)
        // 유일한 관리자 제거 금지.
        assertThatThrownBy { organizationService.removeMember(orgId, admin1) }
            .isInstanceOf(AuthException::class.java)

        // 관리자 2명이 되면 하나는 강등 가능.
        val admin2 = newUser("a2@example.com")
        organizationService.upsertMember(orgId, MembershipRequest(userId = admin2, role = "ORG_ADMIN"))
        organizationService.changeRole(orgId, admin1, "MEMBER")
        assertThat(organizationService.isOrgAdmin(orgId, admin1)).isFalse()
    }

    @Test
    fun `JIT 멤버십은 없을 때 생성하고 이미 있으면 멱등이다`() {
        val orgId = organizationService.create(OrgCreateRequest(name = "Jit")).id
        val userId = newUser("jit@example.com")

        assertThat(organizationService.ensureJitMembership(orgId, userId)).isTrue()
        assertThat(membershipRepository.existsByOrgIdAndUserId(orgId, userId)).isTrue()
        // 두 번째 호출은 아무 것도 만들지 않는다(멱등).
        assertThat(organizationService.ensureJitMembership(orgId, userId)).isFalse()
        assertThat(membershipRepository.findByOrgId(orgId)).hasSize(1)
    }

    @Test
    fun `org update 는 Postgres 가 거부하는 타임존을 400 으로 막는다`() {
        val orgId = organizationService.create(OrgCreateRequest(name = "Tz")).id

        // 리전-ID 형식이지만 알 수 없는 존은 ZoneId.of 파싱 단계에서 거부된다.
        assertThatThrownBy { organizationService.update(orgId, OrgUpdateRequest(timezone = "Not/AZone")) }
            .isInstanceOf(AuthException::class.java)

        // ZoneId.of 는 통과하나 pg_timezone_names 에는 없는 오프셋 존은 existsPgTimezone 게이트가 거부한다
        // (저장 시 집계 쿼리 AT TIME ZONE 500 예방 — 이 케이스가 pg 재확인 분기를 커버한다).
        assertThatThrownBy { organizationService.update(orgId, OrgUpdateRequest(timezone = "+09:00")) }
            .isInstanceOf(AuthException::class.java)

        // 유효한 IANA 존은 성공하고 저장된다.
        val updated = organizationService.update(orgId, OrgUpdateRequest(timezone = "Asia/Seoul"))
        assertThat(updated.timezone).isEqualTo("Asia/Seoul")
    }

    @Test
    fun `isActiveMember 는 소속 여부를 정확히 판정한다(org 격리)`() {
        val orgA = organizationService.create(OrgCreateRequest(name = "A")).id
        val orgB = organizationService.create(OrgCreateRequest(name = "B")).id
        val userId = newUser("iso@example.com")
        organizationService.upsertMember(orgA, MembershipRequest(userId = userId, role = "MEMBER"))

        assertThat(organizationService.isActiveMember(orgA, userId)).isTrue()
        assertThat(organizationService.isActiveMember(orgB, userId)).isFalse()
    }
}
