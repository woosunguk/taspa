package com.taspa.server.org.dto

import com.taspa.server.domain.org.MembershipHistory
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.Organization
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 조직 생성 요청. slug 는 비어 있으면 name 에서 파생한다. timezone 미지정 시 UTC. */
data class OrgCreateRequest(
    val slug: String? = null,
    val name: String = "",
    /** org-로컬 타임존(IANA 존 이름, 예: Asia/Seoul). 소비 이벤트 집계 date 버킷의 기준. 미지정 시 UTC. */
    val timezone: String? = null,
)

/** 조직 수정 요청(name·status·timezone). slug 는 불변(식별 안정성). */
data class OrgUpdateRequest(
    val name: String? = null,
    val status: String? = null,
    /** org-로컬 타임존(IANA 존 이름). 지정 시 유효한 존만 허용한다. */
    val timezone: String? = null,
)

/**
 * 조직 프로필 자율 편집 요청(ORG_ADMIN 콘솔) — **name·timezone 만** 담는다. status·slug 필드가 아예 없어
 * 본문에 그 값을 실어도 역직렬화 단계에서 무시된다(정지 해제·slug 탈취 불가 — 플랫폼 관리자 전용).
 */
data class OrgProfileRequest(
    val name: String? = null,
    /** org-로컬 타임존(IANA 존 이름). 지정 시 유효한 존만 허용한다. */
    val timezone: String? = null,
)

data class OrgView(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: String,
    /** org-로컬 타임존(소비 집계 date 버킷 앵커). 관리 콘솔 노출·감사 추적용. */
    val timezone: String,
    val memberCount: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(
            o: Organization,
            memberCount: Long = 0,
        ) = OrgView(
            id = o.id!!,
            slug = o.slug,
            name = o.name,
            status = o.status,
            timezone = o.timezone,
            memberCount = memberCount,
            createdAt = o.createdAt,
        )
    }
}

/**
 * 자율 콘솔용 뷰 — 로그인 사용자가 ORG_ADMIN 으로 관리하는 활성 조직 하나. 멤버 수를 함께 담아
 * 콘솔 목록에서 즉시 렌더한다(별도 조회 왕복 제거).
 */
data class AdministeredOrgView(
    val id: UUID,
    val name: String,
    val slug: String,
    val status: String,
    /** org-로컬 타임존 — 콘솔 프로필 편집 폼의 현재값 프리필용(별도 조회 왕복 제거). */
    val timezone: String,
    val role: String,
    val memberCount: Long,
    /**
     * 조직 생성 시각.
     *
     * 화면이 **조직이 존재하지도 않던 달**을 재촉하지 않게 하는 근거다. 이 값이 없던 동안 개요의
     * '처리 대기'는 지난달 청구서가 없으면 무조건 적색 항목을 만들었고, 어제 만든 조직에도
     * "지난달 청구서가 없습니다"가 떴다.
     */
    val createdAt: Instant,
)

/**
 * 계정 페이지 "내 조직"(읽기 전용, 일반 멤버용) 뷰 — 로그인 사용자가 소속된 활성 조직 하나.
 * 역할(MEMBER·ORG_ADMIN 모두)·부서를 함께 담아 계정 페이지에서 즉시 렌더한다.
 */
data class MyMembershipView(
    val orgId: UUID,
    val orgName: String,
    val orgSlug: String,
    val role: String,
    val department: String?,
    val joinedAt: Instant,
)

/** 멤버십 upsert 요청. userId 대상, 역할·부서 지정. */
data class MembershipRequest(
    val userId: UUID,
    val role: String? = null,
    val department: String? = null,
    /** 구조적 부서 배정(departments.id). 정책 재정의·대시보드 롤업이 보는 축이다. */
    val departmentId: UUID? = null,
)

data class MembershipRoleRequest(
    val role: String,
)

data class MembershipView(
    val id: UUID,
    val orgId: UUID,
    val userId: UUID,
    val email: String?,
    /**
     * 표시 이름(users.display_name). 이메일만으로는 구성원 화면에서 **누가 누군지 알 수 없다** —
     * 조직관리자가 역할·부서를 정하는 화면인데 사람을 식별할 정보가 로컬파트뿐이었다.
     * 미설정 계정이 있으므로 nullable 이고, 화면은 이름이 없으면 이메일로 내려간다.
     */
    val displayName: String?,
    val role: String,
    val department: String?,
    /** 구조적 부서 배정(departments.id) — 자유 텍스트 department 라벨과 별개. 미배정이면 null. */
    val departmentId: UUID?,
    /** 구조적 사업장 배정(sites.id) — 미배정이면 null. */
    val siteId: UUID?,
    /** 임직원 사번(HR 속성) — 미설정이면 null. */
    val employeeId: String?,
    /** 직함(HR 속성) — 미설정이면 null. */
    val jobTitle: String?,
    /** 고용 형태(FULL_TIME/PART_TIME/CONTRACT/INTERN) — 미설정이면 null. */
    val employmentType: String?,
    /** 입사일(HR 속성) — 미설정이면 null. */
    val hireDate: LocalDate?,
    /** 재직 상태(EMPLOYED/ON_LEAVE/TERMINATED). */
    val employmentStatus: String,
    val status: String,
    val joinedAt: Instant,
) {
    companion object {
        fun from(
            m: OrgMembership,
            email: String?,
            displayName: String? = null,
        ) = MembershipView(
            id = m.id!!,
            orgId = m.orgId,
            userId = m.userId,
            email = email,
            displayName = displayName,
            role = m.role,
            department = m.department,
            departmentId = m.departmentId,
            siteId = m.siteId,
            employeeId = m.employeeId,
            jobTitle = m.jobTitle,
            employmentType = m.employmentType,
            hireDate = m.hireDate,
            employmentStatus = m.employmentStatus,
            status = m.status,
            joinedAt = m.joinedAt,
        )
    }
}

/**
 * 임직원 속성 갱신 요청(ORG_ADMIN 콘솔). full-replace 시맨틱: 각 nullable 필드는 null 이면 해당 값을
 * 해제(clear)한다. employmentStatus 는 NOT NULL 컬럼이라 null 이면 기존 값을 **유지**한다(재직상태를 실수로
 * 초기화하지 않도록). hireDate 는 ISO-8601(yyyy-MM-dd) 문자열; 형식 오류는 서비스에서 400 으로 매핑한다.
 */
data class MemberAttributesRequest(
    val employeeId: String? = null,
    val jobTitle: String? = null,
    val employmentType: String? = null,
    val hireDate: String? = null,
    val employmentStatus: String? = null,
)

/**
 * 멤버십 이력(SCD) 한 스냅샷 뷰. PII(user_sub·email 등) 없이 멤버십 상태만 담는다 — 이력 조회는
 * org 스코프 활동 재구성용이라 민감정보를 노출하지 않는다.
 */
data class MembershipHistoryView(
    val id: UUID,
    val userId: UUID,
    val role: String,
    val departmentId: UUID?,
    val siteId: UUID?,
    val employmentType: String?,
    val employmentStatus: String,
    val jobTitle: String?,
    val changeType: String,
    val recordedAt: Instant,
    val recordedBy: UUID?,
) {
    companion object {
        fun from(h: MembershipHistory) =
            MembershipHistoryView(
                id = h.id!!,
                userId = h.userId,
                role = h.role,
                departmentId = h.departmentId,
                siteId = h.siteId,
                employmentType = h.employmentType,
                employmentStatus = h.employmentStatus,
                jobTitle = h.jobTitle,
                changeType = h.changeType,
                recordedAt = h.recordedAt,
                recordedBy = h.recordedBy,
            )
    }
}

/** 부서 서브트리 위임 한 건. */
data class DepartmentDelegationView(
    val id: UUID,
    val userId: UUID,
    val userEmail: String?,
    val departmentId: UUID,
    /** 조회 시점 부서 이름(live 는 정합이 옳다 — 이력의 스냅샷과 다른 성격). */
    val departmentName: String?,
    val grantedBy: UUID?,
    val createdAt: Instant,
)

/** 위임 부여 요청. */
data class DepartmentDelegationRequest(
    val userId: UUID? = null,
    val departmentId: UUID? = null,
)
