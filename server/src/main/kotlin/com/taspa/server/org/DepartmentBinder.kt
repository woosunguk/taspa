package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.DepartmentRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 자유 텍스트 부서 라벨을 **구조적 부서**(departments.id)로 잇는다.
 *
 * 왜 필요한가: `org_memberships.department`(라벨)와 `department_id`(FK)는 다른 축이다. 라벨은 화면에
 * 글자로 보이고, 정책 재정의·대시보드 롤업이 보는 것은 FK 쪽이다. 초대·CSV·SCIM 은 지금까지 라벨만
 * 채웠는데, 부서별 식대 정책이 붙은 뒤로 그 차이가 돈으로 나타난다 — 개발팀 재정의(1식 18,000원)를
 * 만들어 두고 HR 이 "개발팀"이라 적어 초대하면, 그 사람만 조직 기본값으로 결제된다.
 *
 * ★**모호하면 잇지 않는다.** 부서 이름은 형제 사이에서만 유일하므로(`uq_dept_sibling`), 트리 어딘가에
 * 같은 이름이 둘 이상 있을 수 있다("영업팀"이 국내본부에도 해외본부에도 있는 경우). 그때 아무 쪽이나
 * 고르면 절반의 확률로 **틀린 부서의 예산**을 쓰게 된다. 라벨은 그대로 두고 구조 배정만 비워 두는
 * 편이 정직하다 — 조직관리자가 배정 화면에서 정하면 된다.
 */
@Component
class DepartmentBinder(
    private val departmentRepository: DepartmentRepository,
) {
    /**
     * @param explicitId 화면이 부서 선택기로 고른 값. 있으면 그것만 쓴다(이름 해석보다 우선).
     * @param label 자유 텍스트(CSV 열, SCIM enterprise.department). 이름이 **정확히 하나**일 때만 잇는다.
     * @return 구조 배정에 쓸 departments.id, 또는 null(잇지 못했거나 지정이 없음).
     * @throws AuthException explicitId 가 이 조직 부서가 아닐 때(테넌시 위반은 조용히 넘기지 않는다).
     */
    fun resolve(
        orgId: UUID,
        explicitId: UUID?,
        label: String?,
    ): UUID? {
        if (explicitId != null) {
            departmentRepository.findByIdAndOrgId(explicitId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다")
            return explicitId
        }
        val name = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val matches = departmentRepository.findByOrgId(orgId).filter { it.name.equals(name, ignoreCase = true) }
        // 0개(그런 부서 없음) 또는 2개 이상(어느 쪽인지 알 수 없음) → 잇지 않는다.
        return matches.singleOrNull()?.id
    }
}
