package com.taspa.server.domain.org

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface DepartmentRepository : JpaRepository<Department, UUID> {
    fun findByOrgId(orgId: UUID): List<Department>

    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): Department?

    fun existsByParentId(parentId: UUID): Boolean

    fun existsByOrgIdAndParentIdAndName(
        orgId: UUID,
        parentId: UUID?,
        name: String,
    ): Boolean

    /**
     * 대상 부서 행을 PESSIMISTIC_WRITE(SELECT ... FOR UPDATE)로 잠근 뒤 반환한다. delete()의
     * existsByParentId check-then-act 와 create()의 부모 결속이 동일 행(부모=삭제 대상)을 잠가 직렬화되므로,
     * 동시 "자식 생성 + 부모 삭제"가 parent_id ON DELETE CASCADE 로 방금 만든 자식을 조용히 지우는
     * write-skew(TOCTOU)를 막는다(guardLastAdmin 의 lockByOrgIdAndRoleForUpdate 패턴과 정합).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Department d where d.id = :id and d.orgId = :orgId")
    fun findByIdAndOrgIdForUpdate(
        @Param("id") id: UUID,
        @Param("orgId") orgId: UUID,
    ): Department?
}
