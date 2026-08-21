package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.DepartmentDelegation
import com.taspa.server.domain.org.DepartmentDelegationRepository
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.org.dto.DepartmentDelegationView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 부서 서브트리 위임 관리 — "이 사람은 이 부서와 그 하위만 관리한다".
 *
 * ★**위임과 ORG_ADMIN 은 상호배제**다. 둘 다 가진 상태를 허용하면 위임은 아무 의미가 없고(전사 권한이
 * 이미 있다), 더 나쁘게는 "위임을 회수했으니 안전하다"는 오해를 만든다. 부여 시점에 거절하고,
 * ORG_ADMIN 승격 시에는 위임을 자동 해제한다 — **같은 트랜잭션에서** 해야 그 사이에 낀 요청이
 * 두 권한을 동시에 보지 않는다.
 */
@Service
class DepartmentDelegationService(
    private val delegationRepository: DepartmentDelegationRepository,
    private val departmentRepository: DepartmentRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun list(orgId: UUID): List<DepartmentDelegationView> {
        val delegations = delegationRepository.findByOrgId(orgId)
        if (delegations.isEmpty()) return emptyList()
        val departments = departmentRepository.findByOrgId(orgId).associateBy({ it.id!! }, { it.name })
        val emails =
            userRepository
                .findAllById(delegations.map { it.userId })
                .associateBy({ it.id!! }, { it.email })
        return delegations.map {
            DepartmentDelegationView(
                id = it.id!!,
                userId = it.userId,
                userEmail = emails[it.userId],
                departmentId = it.departmentId,
                departmentName = departments[it.departmentId],
                grantedBy = it.grantedBy,
                createdAt = it.createdAt,
            )
        }
    }

    /**
     * 위임 부여(멱등 — 같은 사람에게 다시 주면 부서만 갱신).
     *
     * 대상은 **그 조직의 활성 멤버**여야 한다. 비멤버에게 주면 "조직에 속하지 않은 사람이 조직 인사를
     * 관리하는" 상태가 되고, 멤버십이 인가의 앵커라는 다른 곳의 전제와도 어긋난다.
     */
    @Transactional
    fun grant(
        orgId: UUID,
        userId: UUID,
        departmentId: UUID,
        actorId: UUID?,
    ): DepartmentDelegationView {
        val department =
            departmentRepository.findByIdAndOrgId(departmentId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다")
        val membership =
            membershipRepository.findByOrgIdAndUserId(orgId, userId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "이 조직의 구성원이 아닙니다")
        if (membership.statusEnum() != MembershipStatus.ACTIVE) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "활성 구성원에게만 위임할 수 있습니다")
        }
        // 상호배제 ① — 이미 전사 권한이 있는 사람에게 부분 권한을 얹는 것은 의미가 없고 오해를 만든다.
        if (membership.roleEnum() == OrgRole.ORG_ADMIN) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "조직관리자에게는 부서 위임을 줄 수 없습니다. 먼저 역할을 구성원으로 바꿔 주세요")
        }
        val user =
            userRepository.findById(userId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다")
        if (user.role == UserRole.ADMIN.name) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "플랫폼 관리자에게는 부서 위임을 줄 수 없습니다")
        }

        val existing = delegationRepository.findByOrgIdAndUserId(orgId, userId)
        val saved =
            if (existing != null) {
                existing.departmentId = departmentId
                existing.grantedBy = actorId
                delegationRepository.save(existing)
            } else {
                delegationRepository.save(
                    DepartmentDelegation(
                        orgId = orgId,
                        departmentId = departmentId,
                        userId = userId,
                        grantedBy = actorId,
                    ),
                )
            }
        return DepartmentDelegationView(
            id = saved.id!!,
            userId = userId,
            userEmail = user.email,
            departmentId = departmentId,
            departmentName = department.name,
            grantedBy = actorId,
            createdAt = saved.createdAt,
        )
    }

    /** 이 사용자가 이 조직에서 위임받은 부서(없으면 null). 인가 통과 후 **응답 범위**를 좁힐 때 쓴다. */
    @Transactional(readOnly = true)
    fun delegatedDepartmentOf(
        orgId: UUID,
        userId: UUID?,
    ): UUID? = userId?.let { delegationRepository.findByOrgIdAndUserId(orgId, it)?.departmentId }

    /** 위임 회수. 없는 위임을 지우는 것도 성공으로 본다(멱등 — 두 번 눌러도 같은 결과). */
    @Transactional
    fun revoke(
        orgId: UUID,
        userId: UUID,
    ) {
        delegationRepository.deleteByOrgIdAndUserId(orgId, userId)
    }

    /**
     * 위임자에게만 적용되는 추가 가드 — **엔진이 표현할 수 없는 것들**.
     *
     * 엔진은 요청 하나에 자원 하나를 본다. 그런데 배정(assign)은 자원이 둘이다: 옮길 **사람**과 옮겨 갈
     * **부서**. 사람만 검사하면 부서장이 자기 부서원을 **다른 본부로 밀어 넣을** 수 있다 — 그쪽 관리자가
     * 모르는 인원이 생기고, 그 사람의 식대는 그쪽 예산에서 나간다. 그래서 목적지도 같은 서브트리여야 한다.
     *
     * 위임이 없는 행위자(조직관리자·플랫폼 관리자)는 no-op 이다 — 그들은 전 부서를 정당하게 넘나든다.
     */
    @Transactional(readOnly = true)
    fun requireDestinationInScope(
        orgId: UUID,
        actorId: UUID?,
        destinationDepartmentId: UUID?,
    ) {
        val delegation = actorId?.let { delegationRepository.findByOrgIdAndUserId(orgId, it) } ?: return
        // 미배정으로 보내는 것도 서브트리 밖으로 내보내는 것이다(누구의 관리도 받지 않게 된다).
        if (destinationDepartmentId == null) {
            throw AuthException(ErrorCode.FORBIDDEN, "위임받은 부서 밖으로 구성원을 옮길 수 없습니다")
        }
        if (!isWithinSubtree(orgId, delegation.departmentId, destinationDepartmentId)) {
            throw AuthException(ErrorCode.FORBIDDEN, "위임받은 부서 밖으로 구성원을 옮길 수 없습니다")
        }
    }

    /**
     * 자기 자신을 대상으로 하는 조작 차단.
     *
     * 위임자도 자기 부서 소속이라 스코프 조건을 스스로 통과한다. 그 상태로 자기 재직상태·속성을
     * 고칠 수 있으면 "내가 나를 관리"하는 셈이라, 감사에서 견제가 성립하지 않는다.
     * 조직관리자에게 이미 있는 자기보호 규약(`ADMIN_SELF_ACTION`)과 같은 취지다.
     */
    @Transactional(readOnly = true)
    fun requireNotSelfWhenDelegated(
        orgId: UUID,
        actorId: UUID?,
        targetUserId: UUID,
    ) {
        if (actorId == null || actorId != targetUserId) return
        if (delegationRepository.existsByOrgIdAndUserId(orgId, actorId)) {
            throw AuthException(ErrorCode.FORBIDDEN, "자기 자신에 대해서는 위임 권한을 쓸 수 없습니다")
        }
    }

    /** [candidate] 가 [root] 서브트리 안인지 — 조상을 타고 올라가며 확인(순환 방어 포함). */
    private fun isWithinSubtree(
        orgId: UUID,
        root: UUID,
        candidate: UUID,
    ): Boolean {
        val byId = departmentRepository.findByOrgId(orgId).associateBy { it.id!! }
        val seen = mutableSetOf<UUID>()
        var cursor: UUID? = candidate
        while (cursor != null && seen.add(cursor)) {
            if (cursor == root) return true
            cursor = byId[cursor]?.parentId
        }
        return false
    }

    /**
     * 상호배제 ② — ORG_ADMIN 으로 승격되면 위임을 **자동 해제**한다.
     *
     * 역할 변경 트랜잭션 **안에서** 호출해야 한다. 나중에 별도로 정리하면 그 사이의 요청이 두 권한을
     * 동시에 보고, 감사 로그에서도 어느 근거로 통과했는지가 모호해진다.
     */
    @Transactional
    fun detachOnPromotion(
        orgId: UUID,
        userId: UUID,
    ) {
        if (delegationRepository.existsByOrgIdAndUserId(orgId, userId)) {
            delegationRepository.deleteByOrgIdAndUserId(orgId, userId)
        }
    }
}
