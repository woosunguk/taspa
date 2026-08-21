package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 계층형 부서(조직도). org 스코프 자기참조 트리 — parentId 가 null 이면 루트, 아니면 서브부서.
 * 형제(같은 부모) 이름은 유일하다(V21 부분 유니크). 삭제 가드(자식 존재 시 차단)는 DepartmentService 가 담당한다.
 */
@Entity
@Table(name = "departments")
class Department(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "parent_id")
    var parentId: UUID? = null,
    @Column(name = "name", nullable = false, length = 120)
    var name: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}
