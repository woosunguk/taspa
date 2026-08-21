package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.org.dto.DepartmentView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 계층형 부서(조직도) — org 스코프 자기참조 트리. org 격리 불변식:
 *  - 생성 시 parentId 가 주어지면 그 부모가 **같은 org 소속**인지 검증한다(타 org 부모 결속 금지).
 *  - rename/delete 는 findByIdAndOrgId 로 대상이 그 org 소속일 때만 동작(타 org 는 404).
 *  - 형제(같은 부모) 이름 중복은 거부한다(DB 부분 유니크와 정합 — 앱 계층 선검증으로 명확한 400).
 *  - 삭제는 **자식 부서가 있으면 거부**(서브트리 보존 — 수동 정리 강제). 리프 삭제 시 배정 멤버는 department_id SET NULL(DB FK).
 */
@Service
class DepartmentService(
    private val departmentRepository: DepartmentRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val organizationService: OrganizationService,
) {
    @Transactional(readOnly = true)
    fun listTree(orgId: UUID): List<DepartmentView> {
        organizationService.requireOrg(orgId) // 조회는 존재만 검증(정지 조직도 조회 가능)
        val counts =
            membershipRepository
                .countByDepartmentGrouped(orgId)
                .associate { (it[0] as UUID) to (it[1] as Long) }
        return departmentRepository
            .findByOrgId(orgId)
            .map {
                DepartmentView(
                    id = it.id!!,
                    parentId = it.parentId,
                    name = it.name,
                    memberCount = counts[it.id!!] ?: 0L,
                )
            }.sortedBy { it.name.lowercase() }
    }

    @Transactional
    fun create(
        orgId: UUID,
        name: String,
        parentId: UUID?,
    ): DepartmentView {
        organizationService.requireActiveOrg(orgId)
        val normalized = organizationService.normalizeStructureName(name)
        if (parentId != null) {
            // 타 org 부모 결속 금지 — 부모가 같은 org 소속일 때만 허용(핵심 격리). 부모 행을 FOR UPDATE 로
            // 잠가 delete()의 서브트리 가드와 직렬화한다(동시 자식 생성 + 부모 삭제로 인한 CASCADE 무손실 방지).
            departmentRepository.findByIdAndOrgIdForUpdate(parentId, orgId)
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "상위 부서를 찾을 수 없습니다")
        }
        if (departmentRepository.existsByOrgIdAndParentIdAndName(orgId, parentId, normalized)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "같은 상위 아래에 동일한 이름의 부서가 이미 있습니다")
        }
        val saved = departmentRepository.save(Department(orgId = orgId, parentId = parentId, name = normalized))
        return DepartmentView(id = saved.id!!, parentId = saved.parentId, name = saved.name, memberCount = 0)
    }

    @Transactional
    fun rename(
        orgId: UUID,
        deptId: UUID,
        name: String,
    ): DepartmentView {
        val dept =
            departmentRepository.findByIdAndOrgId(deptId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다")
        val normalized = organizationService.normalizeStructureName(name)
        if (normalized != dept.name &&
            departmentRepository.existsByOrgIdAndParentIdAndName(orgId, dept.parentId, normalized)
        ) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "같은 상위 아래에 동일한 이름의 부서가 이미 있습니다")
        }
        dept.name = normalized
        val saved = departmentRepository.save(dept)
        return DepartmentView(
            id = saved.id!!,
            parentId = saved.parentId,
            name = saved.name,
            memberCount = membershipRepository.countByDepartmentId(saved.id!!),
        )
    }

    @Transactional
    fun delete(
        orgId: UUID,
        deptId: UUID,
    ) {
        // 대상 부서 행을 FOR UPDATE 로 잠근 뒤 existsByParentId 검사 — 동시 자식 생성(create 가 이 행을 부모로
        // 잠금)과 직렬화해, parent_id ON DELETE CASCADE 로 방금 커밋된 자식이 조용히 삭제되는 TOCTOU 를 막는다.
        val dept =
            departmentRepository.findByIdAndOrgIdForUpdate(deptId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다")
        // 자식 부서가 있으면 삭제 거부 — 서브트리 우발 삭제 방지(수동 정리 강제).
        if (departmentRepository.existsByParentId(dept.id!!)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "하위 부서를 먼저 정리하세요")
        }
        // 리프 삭제 — 배정 멤버는 department_id SET NULL(DB FK)로 자동 해제된다.
        departmentRepository.delete(dept)
    }
}
