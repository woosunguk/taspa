package com.taspa.server.org

import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.EmploymentType
import com.taspa.server.domain.org.InvitationStatus
import com.taspa.server.domain.org.MembershipChangeType
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgInvitationRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.org.dto.DepartmentRollupView
import com.taspa.server.org.dto.OrgDashboardView
import com.taspa.server.org.dto.SiteCountView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조직 개요 대시보드(읽기전용 집계) — 예측(식수) 모집단 가시화 토대. 스키마 변경 없이 기존 테이블의
 * 그룹/카운트 쿼리만으로 집계한다(멤버 전체 로드·1+N 금지).
 *
 * 일관성 불변식: 모든 인원 카운트는 **활성 멤버십(status=ACTIVE)** 기준이다. (기존 countByOrgId·
 * countByDepartmentGrouped 등은 status 필터가 없어 SUSPENDED 멤버십도 세므로 대시보드에선 쓰지 않는다.)
 * 부서 롤업(자기+모든 하위 부서 합)은 flat 부서 목록을 서비스 계층에서 트리 워크로 계산한다(재귀 CTE 불요).
 */
@Service
class OrgDashboardService(
    private val organizationService: OrganizationService,
    private val membershipRepository: OrgMembershipRepository,
    private val departmentRepository: DepartmentRepository,
    private val siteRepository: SiteRepository,
    private val invitationRepository: OrgInvitationRepository,
    private val historyRepository: MembershipHistoryRepository,
) {
    companion object {
        /** employment_type NULL(미지정) 분포 키 — enum 이 아니라 응답 전용 라벨 키다. */
        const val TYPE_UNSPECIFIED = "UNSPECIFIED"

        /** 최근 합류 윈도우(일) — JOINED 이력 스냅샷 카운트 기준. */
        const val RECENT_JOIN_WINDOW_DAYS = 30L
    }

    @Transactional(readOnly = true)
    fun dashboard(orgId: UUID): OrgDashboardView {
        organizationService.requireOrg(orgId) // 존재만 검증(정지 조직도 개요 조회 가능 — 구조 조회와 동일 계약)
        val active = MembershipStatus.ACTIVE.name

        val byRole =
            fixedKeyCounts(
                OrgRole.entries.map { it.name },
                membershipRepository.countByRoleGrouped(orgId, active),
            )
        val byEmploymentStatus =
            fixedKeyCounts(
                EmploymentStatus.entries.map { it.name },
                membershipRepository.countByEmploymentStatusGrouped(orgId, active),
            )
        // 고용형태는 nullable — NULL 그룹을 UNSPECIFIED 키로 승격해 4종 + 미지정이 항상 존재한다.
        val byEmploymentType = LinkedHashMap<String, Long>()
        EmploymentType.entries.forEach { byEmploymentType[it.name] = 0L }
        byEmploymentType[TYPE_UNSPECIFIED] = 0L
        membershipRepository.countByEmploymentTypeGrouped(orgId, active).forEach { row ->
            val key = (row[0] as String?) ?: TYPE_UNSPECIFIED
            byEmploymentType[key] = (byEmploymentType[key] ?: 0L) + (row[1] as Long)
        }

        // 부서 분포 — NULL 그룹(미배정) 포함 단일 GROUP BY 후, 서비스 계층 트리 워크로 롤업을 계산한다.
        val directByDept = HashMap<UUID?, Long>()
        membershipRepository.countByDepartmentGroupedWithStatus(orgId, active).forEach { row ->
            directByDept[row[0] as UUID?] = row[1] as Long
        }
        val departments = departmentRepository.findByOrgId(orgId)
        val rollups = computeRollups(departments, directByDept)
        val byDepartment =
            departments
                .map {
                    DepartmentRollupView(
                        id = it.id!!,
                        parentId = it.parentId,
                        name = it.name,
                        directCount = directByDept[it.id!!] ?: 0L,
                        rollupCount = rollups[it.id!!] ?: 0L,
                    )
                }.sortedBy { it.name.lowercase() }

        // 사업장 분포 — NULL 그룹(미배정) 포함.
        val countsBySite = HashMap<UUID?, Long>()
        membershipRepository.countBySiteGroupedWithStatus(orgId, active).forEach { row ->
            countsBySite[row[0] as UUID?] = row[1] as Long
        }
        val bySite =
            siteRepository
                .findByOrgId(orgId)
                .map { SiteCountView(id = it.id!!, name = it.name, count = countsBySite[it.id!!] ?: 0L) }
                .sortedBy { it.name.lowercase() }

        return OrgDashboardView(
            // role 은 NOT NULL 이라 역할별 합이 곧 활성 멤버십 총수다(별도 count 쿼리 불요).
            memberCount = byRole.values.sum(),
            byRole = byRole,
            byEmploymentStatus = byEmploymentStatus,
            byEmploymentType = byEmploymentType,
            byDepartment = byDepartment,
            departmentUnassignedCount = directByDept[null] ?: 0L,
            bySite = bySite,
            siteUnassignedCount = countsBySite[null] ?: 0L,
            siteCount = bySite.size.toLong(),
            pendingInvitations =
                invitationRepository.countByOrgIdAndStatusAndExpiresAtAfter(
                    orgId,
                    InvitationStatus.PENDING.name,
                    Instant.now(),
                ),
            recentJoins30d =
                historyRepository.countByOrgIdAndChangeTypeAndRecordedAtAfter(
                    orgId,
                    MembershipChangeType.JOINED.name,
                    Instant.now().minus(RECENT_JOIN_WINDOW_DAYS, ChronoUnit.DAYS),
                ),
        )
    }

    /** enum 전 키를 0 으로 채운 뒤 GROUP BY 결과를 얹는다(빈 상태에서도 키가 항상 존재 — UI 계약). */
    private fun fixedKeyCounts(
        keys: List<String>,
        rows: List<Array<Any>>,
    ): Map<String, Long> {
        val counts = LinkedHashMap<String, Long>()
        keys.forEach { counts[it] = 0L }
        rows.forEach { counts[it[0] as String] = it[1] as Long }
        return counts
    }

    /**
     * flat 부서 목록 + 직접 카운트에서 부서별 롤업(자기+모든 하위 부서 합)을 트리 워크로 계산한다.
     * 방어: 목록에 없는 부모(고아)는 무시하고, 자기참조·순환은 경로 가드로 0 처리한다(콘솔 buildDeptTree 와
     * 동일한 방어선 — 서비스 계층이 사실상 트리인 데이터에 대해 무한 재귀 없이 안전하게 동작).
     */
    private fun computeRollups(
        departments: List<Department>,
        direct: Map<UUID?, Long>,
    ): Map<UUID, Long> {
        val ids = departments.mapNotNull { it.id }.toSet()
        val childrenByParent = HashMap<UUID, MutableList<UUID>>()
        departments.forEach { d ->
            val parent = d.parentId
            if (parent != null && parent in ids && parent != d.id) {
                childrenByParent.getOrPut(parent) { mutableListOf() }.add(d.id!!)
            }
        }
        val memo = HashMap<UUID, Long>()

        fun rollup(
            id: UUID,
            path: MutableSet<UUID>,
        ): Long {
            memo[id]?.let { return it }
            if (!path.add(id)) return 0L // 순환 방어
            val total = (direct[id] ?: 0L) + (childrenByParent[id]?.sumOf { rollup(it, path) } ?: 0L)
            path.remove(id)
            memo[id] = total
            return total
        }
        ids.forEach { rollup(it, mutableSetOf()) }
        return memo
    }
}
