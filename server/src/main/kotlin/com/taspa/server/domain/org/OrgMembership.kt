package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 사용자-조직 멤버십(다대다 + 역할). (org_id, user_id) 유니크.
 * status 로 조직 내 정지(SUSPENDED)를 표현한다. JIT 로그인 성공 시 upsert 된다.
 */
@Entity
@Table(name = "org_memberships")
class OrgMembership(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "role", nullable = false, length = 24)
    var role: String = OrgRole.MEMBER.name,
    @Column(name = "department", length = 120)
    var department: String? = null,
    /** 구조적 부서 배정(departments.id). 자유 텍스트 department 라벨과 별개. 배정 부서 삭제 시 SET NULL. */
    @Column(name = "department_id")
    var departmentId: UUID? = null,
    /** 구조적 사업장 배정(sites.id). 배정 사이트 삭제 시 SET NULL. */
    @Column(name = "site_id")
    var siteId: UUID? = null,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = MembershipStatus.ACTIVE.name,
    /** 임직원 사번(HR 속성, nullable). org 내 식별 라벨 — 유니크 강제는 아직 없음(정합성은 HR 소스가 책임). */
    @Column(name = "employee_id", length = 64)
    var employeeId: String? = null,
    /** 직함(HR 속성, nullable). */
    @Column(name = "job_title", length = 120)
    var jobTitle: String? = null,
    /** 고용 형태(FULL_TIME/PART_TIME/CONTRACT/INTERN, nullable). EmploymentType enum 문자열. */
    @Column(name = "employment_type", length = 24)
    var employmentType: String? = null,
    /** 입사일(HR 속성, nullable). */
    @Column(name = "hire_date")
    var hireDate: LocalDate? = null,
    /** 재직 상태(EMPLOYED/ON_LEAVE/TERMINATED). 기본 EMPLOYED. EmploymentStatus enum 문자열. */
    @Column(name = "employment_status", nullable = false, length = 24)
    var employmentStatus: String = EmploymentStatus.EMPLOYED.name,
    /** SCIM externalId(HR 시스템 부여, nullable). org 범위 유니크(V23 부분 인덱스) — 조직 간 충돌 무관. */
    @Column(name = "scim_external_id", length = 255)
    var scimExternalId: String? = null,
    @Column(name = "joined_at", nullable = false, updatable = false)
    val joinedAt: Instant = Instant.now(),
) {
    fun roleEnum(): OrgRole = OrgRole.valueOf(role)

    fun statusEnum(): MembershipStatus = MembershipStatus.valueOf(status)
}
