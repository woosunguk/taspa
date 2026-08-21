package com.taspa.server.org.dto

import com.taspa.server.domain.org.Site
import java.util.UUID

// ---- 부서(조직도) ----

/** 부서 생성 요청. parentId 가 null 이면 루트 부서, 아니면 그 부모의 서브부서(같은 org 소속이어야 함). */
data class DepartmentCreateRequest(
    val name: String = "",
    val parentId: UUID? = null,
)

/** 부서 이름 변경 요청. */
data class DepartmentRenameRequest(
    val name: String = "",
)

/**
 * 부서 트리 flat 뷰 — 콘솔이 parentId 로 트리를 구성한다. memberCount 는 이 부서에 **직접 배정된** 멤버 수
 * (서브부서 롤업은 이 단계 범위 밖 — 콘솔이 트리에서 합산).
 */
data class DepartmentView(
    val id: UUID,
    val parentId: UUID?,
    val name: String,
    val memberCount: Long,
)

// ---- 사업장(사이트) ----

/** 사업장 생성 요청. timezone 미지정 시 UTC. */
data class SiteCreateRequest(
    val name: String = "",
    val address: String? = null,
    val timezone: String? = null,
)

/**
 * 사업장 수정 요청 — name·address·timezone. 필드가 null 이면 미변경(address 는 빈 문자열로 명시적 해제 가능).
 */
data class SiteUpdateRequest(
    val name: String? = null,
    val address: String? = null,
    val timezone: String? = null,
)

data class SiteView(
    val id: UUID,
    val name: String,
    val address: String?,
    val timezone: String,
    val memberCount: Long,
) {
    companion object {
        fun from(
            s: Site,
            memberCount: Long = 0,
        ) = SiteView(
            id = s.id!!,
            name = s.name,
            address = s.address,
            timezone = s.timezone,
            memberCount = memberCount,
        )
    }
}

// ---- 멤버 구조적 배정 ----

/**
 * 멤버 배정 요청 — departmentId·siteId(둘 다 nullable). null 은 **해제**를 뜻한다(부분 갱신 아님 — full replace).
 * 배정 대상은 그 org 소속이어야 한다(타 org 배정 금지 — 격리).
 */
data class MemberAssignmentRequest(
    val departmentId: UUID? = null,
    val siteId: UUID? = null,
)
