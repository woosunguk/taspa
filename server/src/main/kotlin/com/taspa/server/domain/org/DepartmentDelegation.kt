package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * 부서 서브트리 위임 한 건 — 이 사용자는 이 부서와 **그 하위 전체**를 관리한다.
 *
 * 권한의 실체는 이 행이 아니라 이 행에서 매 요청 합성되는 정책 문서다
 * ([com.taspa.server.iam.LegacyPolicyBridge.departmentDelegate]). 저장된 정책 문서를 두지 않는 이유는
 * 그것이 IAM 콘솔에서 편집 가능한 자원이기 때문이다 — 위임 경계가 정책 편집으로 넓혀질 수 있으면
 * "부서장은 자기 부서만"이라는 약속이 약속이 아니게 된다.
 */
@Entity
@Table(name = "org_department_delegations")
class DepartmentDelegation(
    @Id
    @GeneratedValue
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "department_id", nullable = false)
    var departmentId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "granted_by")
    var grantedBy: UUID? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

interface DepartmentDelegationRepository : JpaRepository<DepartmentDelegation, UUID> {
    /** 인가 경로 — 매 요청 1회. (org, user) 유니크라 결과는 0 또는 1건이다. */
    fun findByOrgIdAndUserId(
        orgId: UUID,
        userId: UUID,
    ): DepartmentDelegation?

    fun findByOrgId(orgId: UUID): List<DepartmentDelegation>

    fun deleteByOrgIdAndUserId(
        orgId: UUID,
        userId: UUID,
    )

    /** ORG_ADMIN 승격 시 상호배제 확인용(사용자 하나가 여러 org 에 위임을 가질 수 있다). */
    fun existsByOrgIdAndUserId(
        orgId: UUID,
        userId: UUID,
    ): Boolean
}
