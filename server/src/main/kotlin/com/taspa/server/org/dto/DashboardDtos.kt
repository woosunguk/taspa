package com.taspa.server.org.dto

import java.util.UUID

// ---- 조직 대시보드(읽기전용 집계) ----

/**
 * 조직 개요 대시보드 뷰 — 예측(식수) 모집단 가시화 토대. 모든 카운트는 **활성 멤버십(status=ACTIVE)** 기준으로
 * 그룹/카운트 쿼리에서 집계된다(멤버 전체 로드 금지). 개별 멤버 식별 정보는 담지 않는다(집계 카운트만).
 *
 *  - byRole/byEmploymentStatus/byEmploymentType 은 enum 전 키가 항상 존재한다(0 포함 — UI 빈 상태 자연 표기).
 *    byEmploymentType 의 `UNSPECIFIED` 키는 employment_type NULL(미지정) 멤버 수다.
 *  - byDepartment 의 rollupCount 는 자기 + 모든 하위 부서의 배정 합(트리 워크 — 재귀 CTE 불요).
 *  - departmentUnassignedCount/siteUnassignedCount 는 구조 미배정(activeship 의 department_id/site_id NULL) 수.
 *  - pendingInvitations 는 만료되지 않은 PENDING 초대 수(lazy 만료 전이와 무관하게 expires_at 로 판정).
 *  - recentJoins30d 는 멤버십 이력(SCD)의 JOINED 스냅샷 중 최근 30일 건수.
 */
data class OrgDashboardView(
    val memberCount: Long,
    val byRole: Map<String, Long>,
    val byEmploymentStatus: Map<String, Long>,
    val byEmploymentType: Map<String, Long>,
    val byDepartment: List<DepartmentRollupView>,
    val departmentUnassignedCount: Long,
    val bySite: List<SiteCountView>,
    val siteUnassignedCount: Long,
    val siteCount: Long,
    val pendingInvitations: Long,
    val recentJoins30d: Long,
)

/** 부서별 인원 — directCount(직접 배정) + rollupCount(자기+모든 하위 부서 합). 콘솔이 parentId 로 트리를 구성한다. */
data class DepartmentRollupView(
    val id: UUID,
    val parentId: UUID?,
    val name: String,
    val directCount: Long,
    val rollupCount: Long,
)

/** 사업장별 인원. */
data class SiteCountView(
    val id: UUID,
    val name: String,
    val count: Long,
)
