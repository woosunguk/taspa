package com.taspa.server.domain.org

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OrgMembershipRepository : JpaRepository<OrgMembership, UUID> {
    fun findByUserId(userId: UUID): List<OrgMembership>

    fun findByOrgId(orgId: UUID): List<OrgMembership>

    /** SCIM 목록 페이지네이션 — offset/limit 을 DB 로 밀어 org 전체 멤버십 인메모리 적재를 막는다. */
    fun findByOrgId(
        orgId: UUID,
        pageable: Pageable,
    ): List<OrgMembership>

    fun findByOrgIdAndUserId(
        orgId: UUID,
        userId: UUID,
    ): OrgMembership?

    fun existsByOrgIdAndUserId(
        orgId: UUID,
        userId: UUID,
    ): Boolean

    /** SCIM externalId 조회 — org 범위(V23 부분 유니크 인덱스와 정합). 타 org 의 externalId 는 절대 안 잡힌다. */
    fun findByOrgIdAndScimExternalId(
        orgId: UUID,
        scimExternalId: String,
    ): OrgMembership?

    fun countByOrgIdAndRole(
        orgId: UUID,
        role: String,
    ): Long

    fun countByOrgId(orgId: UUID): Long

    /** 현재 재실 모수(식수예측 P0) — 활성 멤버십(status=ACTIVE) ∧ 재직(employment_status=EMPLOYED) 수. */
    fun countByOrgIdAndStatusAndEmploymentStatus(
        orgId: UUID,
        status: String,
        employmentStatus: String,
    ): Long

    fun countByDepartmentId(departmentId: UUID): Long

    fun countBySiteId(siteId: UUID): Long

    /**
     * org 전체 부서별 배정 멤버 수를 한 번의 GROUP BY 로 집계한다(부서 트리 조회의 1+N 라운드트립 제거).
     * 각 행은 [departmentId(UUID), count(Long)]. 배정이 없는 부서는 결과에 없으므로 호출부에서 0 으로 보정한다.
     */
    @Query(
        "select m.departmentId, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.departmentId is not null group by m.departmentId",
    )
    fun countByDepartmentGrouped(
        @Param("orgId") orgId: UUID,
    ): List<Array<Any>>

    /** org 전체 사업장별 배정 멤버 수를 한 번의 GROUP BY 로 집계한다(사업장 목록 조회의 1+N 제거). */
    @Query(
        "select m.siteId, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.siteId is not null group by m.siteId",
    )
    fun countBySiteGrouped(
        @Param("orgId") orgId: UUID,
    ): List<Array<Any>>

    // ---- 대시보드(읽기전용 집계) — 모든 분포는 단일 GROUP BY 로 집계한다(멤버 전체 로드·1+N 금지).
    // 주의: 위의 countByOrgId/countByDepartmentGrouped/countBySiteGrouped 는 status 필터가 없다(전체 멤버십).
    // 대시보드는 "활성 멤버십(status=ACTIVE)" 기준으로 일관해야 하므로 아래 status 파라미터 쿼리만 사용한다.

    /** 역할별 카운트. 각 행 [role(String), count(Long)]. role 은 NOT NULL 이라 합=활성 총원이다. */
    @Query(
        "select m.role, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.status = :status group by m.role",
    )
    fun countByRoleGrouped(
        @Param("orgId") orgId: UUID,
        @Param("status") status: String,
    ): List<Array<Any>>

    /** 재직상태별 카운트. 각 행 [employmentStatus(String), count(Long)]. */
    @Query(
        "select m.employmentStatus, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.status = :status group by m.employmentStatus",
    )
    fun countByEmploymentStatusGrouped(
        @Param("orgId") orgId: UUID,
        @Param("status") status: String,
    ): List<Array<Any>>

    /** 고용형태별 카운트 — nullable 컬럼이라 NULL 그룹(미지정) 행이 포함될 수 있다. 각 행 [employmentType(String?), count(Long)]. */
    @Query(
        "select m.employmentType, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.status = :status group by m.employmentType",
    )
    fun countByEmploymentTypeGrouped(
        @Param("orgId") orgId: UUID,
        @Param("status") status: String,
    ): List<Array<Any?>>

    /** 부서별 카운트(NULL 그룹=미배정 포함 — countByDepartmentGrouped 와 달리 status 필터 + 미배정 행). */
    @Query(
        "select m.departmentId, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.status = :status group by m.departmentId",
    )
    fun countByDepartmentGroupedWithStatus(
        @Param("orgId") orgId: UUID,
        @Param("status") status: String,
    ): List<Array<Any?>>

    /** 사업장별 카운트(NULL 그룹=미배정 포함). */
    @Query(
        "select m.siteId, count(m) from OrgMembership m " +
            "where m.orgId = :orgId and m.status = :status group by m.siteId",
    )
    fun countBySiteGroupedWithStatus(
        @Param("orgId") orgId: UUID,
        @Param("status") status: String,
    ): List<Array<Any?>>

    /**
     * 실효 관리자 수 — 로그인 가능한 ORG_ADMIN 만 센다. 멤버십이 ACTIVE 이고 backing 사용자 계정도
     * ACTIVE 인 경우만 포함한다(정지된 co-admin 은 로그인 불가라 조직 관리에 기여하지 못하므로 제외).
     * isOrgAdmin/listAdministeredOrgs 의 "활성 멤버십" 정의와 정렬하여 마지막 실사용 관리자를 보호한다.
     */
    @Query(
        """
        select count(m) from OrgMembership m, com.taspa.server.domain.user.User u
        where m.orgId = :orgId
          and m.role = :role
          and m.status = :membershipStatus
          and u.id = m.userId
          and u.status = :userStatus
        """,
    )
    fun countEffectiveAdmins(
        @Param("orgId") orgId: UUID,
        @Param("role") role: String,
        @Param("membershipStatus") membershipStatus: String,
        @Param("userStatus") userStatus: String,
    ): Long

    /**
     * (orgId, userId) 멤버십 행을 PESSIMISTIC_WRITE(SELECT ... FOR UPDATE)로 잠근다 — 사용자×조직 단위
     * 직렬화 앵커. 식권 QR 발급 쿨다운·redeem 한도(일 횟수/월 cap) 판정처럼 count-check-write 패턴이
     * 동시 요청으로 같은 스냅샷을 읽고 한도를 우회하는 TOCTOU 를 막는다. 호출부의 활성 트랜잭션 안에서만 유효.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OrgMembership m where m.orgId = :orgId and m.userId = :userId")
    fun findByOrgIdAndUserIdForUpdate(
        @Param("orgId") orgId: UUID,
        @Param("userId") userId: UUID,
    ): OrgMembership?

    /**
     * 식권 자격 판정용 프로젝션 — 재직상태와 소속(부서·사업장)을 한 번에 읽는다.
     *
     * ★엔티티가 아니라 **프로젝션**이어야 한다. 위 [findByOrgIdAndUserIdForUpdate] 로 행을 잠근 뒤
     * 같은 트랜잭션에서 이 값을 읽는데, 엔티티로 읽으면 Hibernate 1차 캐시가 이미 적재된 인스턴스를
     * 돌려주어 잠금 결과가 반영되지 않는다(`@Version` 이 없어 stale 감지도 못 한다). 프로젝션은 캐시를
     * 우회해 항상 DB 를 재조회하므로, 잠금 직후 판정이 **권위 있는 최신 값** 위에서 이뤄진다
     * (초대 수락에서 실제로 터졌던 결함과 같은 형태 — CLAUDE.md 비관적 잠금 불변식 ② 참조).
     */
    @Query(
        """
        select m.departmentId as departmentId, m.siteId as siteId,
               m.status as status, m.employmentStatus as employmentStatus
        from OrgMembership m
        where m.orgId = :orgId and m.userId = :userId
        """,
    )
    fun findEligibilityView(
        @Param("orgId") orgId: UUID,
        @Param("userId") userId: UUID,
    ): MembershipEligibilityView?

    /**
     * 해당 org 의 지정 역할(ORG_ADMIN) 멤버십 행들을 PESSIMISTIC_WRITE(SELECT ... FOR UPDATE)로 잠근다.
     * 마지막 관리자 보호(guardLastAdmin)의 count-check-write 를 직렬화해, 동시 강등/제거로 관리자가 0명이
     * 되는 write-skew(TOCTOU)를 막는다(초대 accept 경로의 비관적 잠금 패턴과 정합). 반환값은 사용하지 않아도
     * 되며 잠금 획득이 목적이다 — 호출부의 활성 트랜잭션 안에서만 유효하다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OrgMembership m where m.orgId = :orgId and m.role = :role")
    fun lockByOrgIdAndRoleForUpdate(
        @Param("orgId") orgId: UUID,
        @Param("role") role: String,
    ): List<OrgMembership>
}
