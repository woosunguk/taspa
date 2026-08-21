package com.taspa.server.org

import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.iam.ResourceScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 부서 경로(`/{root}/{...}/{self}/`) 계산 — 서브트리 위임 판정의 입력이다.
 *
 * 왜 별도 서비스인가: 이 경로는 **인가 판정에 들어가는 값**이라, 계산이 두 곳에 생기면 한쪽이 틀렸을 때
 * "어떤 요청은 통과하고 어떤 요청은 막히는" 형태로 나타난다 — 재현이 어렵고 원인 추적도 어렵다.
 * 한 곳에서만 만든다.
 *
 * ★조직당 부서를 **한 번에 읽어** 메모리에서 조상을 탄다. 노드마다 질의하면 왕복이 깊이에 비례하는데,
 * 이 계산은 인가 경로라 모든 요청에 얹힌다. 조직당 부서 수는 수백 규모라 전부 읽는 편이 싸다.
 */
@Service
class DepartmentPathService(
    private val departmentRepository: DepartmentRepository,
    private val membershipRepository: OrgMembershipRepository,
) {
    /** 이 부서의 경로. 조직에 없는 id 면 [ResourceScope.NONE](= 위임 정책 미적용 → 거부로 수렴). */
    @Transactional(readOnly = true)
    fun scopeOfDepartment(
        orgId: UUID,
        departmentId: UUID?,
    ): ResourceScope {
        if (departmentId == null) return ResourceScope.NONE
        val byId = departmentRepository.findByOrgId(orgId).associateBy { it.id!! }
        return ResourceScope.ofDepartmentChain(ancestry(departmentId, byId))
    }

    /**
     * 이 멤버가 속한 부서의 경로. 부서 미배정 멤버는 [ResourceScope.NONE] 이다 —
     * **어떤 부서장에게도 속하지 않는다**는 뜻이고, 그 사람은 조직관리자만 관리할 수 있다.
     * (미배정을 "아무나 관리 가능"으로 만들면 위임의 경계가 무의미해진다.)
     */
    @Transactional(readOnly = true)
    fun scopeOfMember(
        orgId: UUID,
        userId: UUID,
    ): ResourceScope = scopeOfDepartment(orgId, membershipRepository.findEligibilityView(orgId, userId)?.getDepartmentId())

    /**
     * [root] 와 그 하위 전체의 부서 id 집합 — 위임자의 멤버 목록 필터에 쓴다.
     *
     * 조상 방향(경로)과 자손 방향(서브트리)을 둘 다 여기 두는 것은 의도적이다. 인가는 경로로 판정하고
     * 목록은 서브트리로 거르는데, 두 계산이 다른 파일에 있으면 한쪽만 고쳐져 "볼 수는 있는데 열면 403"
     * 또는 그 반대가 생긴다.
     */
    @Transactional(readOnly = true)
    fun subtreeIds(
        orgId: UUID,
        root: UUID,
    ): Set<UUID> {
        val children = departmentRepository.findByOrgId(orgId).groupBy { it.parentId }
        val result = mutableSetOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            children[current].orEmpty().forEach { child ->
                // add 가 false 면 이미 방문 — 손상 데이터의 순환에서 빠져나온다.
                if (result.add(child.id!!)) queue.addLast(child.id!!)
            }
        }
        return result
    }

    /**
     * 루트→자신 순서의 조상 체인.
     *
     * ★`parent_id` 는 자기참조 FK 일 뿐 DB 가 **트리를 강제하지 않는다** — 손상 데이터나 미래의 잘못된
     * 이동 연산이 순환을 만들 수 있다. 방문 집합으로 끊는다. 여기서 무한 루프가 나면 그건 인가 판정이
     * 영원히 끝나지 않는다는 뜻이고, 워커 하나가 그대로 묶인다.
     */
    private fun ancestry(
        start: UUID,
        byId: Map<UUID, Department>,
    ): List<UUID> {
        val reversed = mutableListOf<UUID>()
        val seen = mutableSetOf<UUID>()
        var cursor: UUID? = start
        while (cursor != null && seen.add(cursor)) {
            val node = byId[cursor] ?: return emptyList() // 조직 밖 부서 → 경로 없음(거부로 수렴)
            reversed += cursor
            cursor = node.parentId
        }
        return reversed.reversed()
    }
}
