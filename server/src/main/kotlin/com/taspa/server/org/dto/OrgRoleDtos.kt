package com.taspa.server.org.dto

import java.util.UUID

/**
 * 조직 커스텀 역할 요약.
 *
 * `actions` 는 서버가 생성한 정책 문서에서 되읽은 값이다 — 화면이 따로 저장하지 않는다. 저장된 문서가
 * 유일한 진실이고, 화면은 그것을 되비칠 뿐이다(두 곳에 두면 편집 후 어긋난다).
 */
data class OrgRoleView(
    val id: UUID,
    val name: String,
    val description: String?,
    val actions: List<String>,
    val memberCount: Int,
)

/** 역할에 부여된 구성원. 탈퇴 등으로 users 행이 사라졌으면 email 은 null(부여 사실은 남는다). */
data class OrgRoleMemberView(
    val userId: UUID,
    val email: String?,
)

data class OrgRoleDetailView(
    val id: UUID,
    val name: String,
    val description: String?,
    val actions: List<String>,
    val members: List<OrgRoleMemberView>,
)

/**
 * 역할 생성·수정 요청.
 *
 * ★**정책 문서를 받지 않는다.** 조직관리자가 고르는 것은 `actions` 뿐이고 리소스·조건은 서버가 박는다 —
 * 원시 JSON 을 받으면 `trn:taspa:platform::*` 한 줄로 권한상승이 되기 때문이다(평가기는 정책의 org_id 를
 * 보지 않는다). 이 DTO 에 문서 필드가 **없다는 것 자체가** 그 통로를 닫는다.
 */
data class SaveOrgRoleRequest(
    val name: String = "",
    val description: String? = null,
    val actions: List<String> = emptyList(),
)

/** 부여 대상 지정 — 그 조직의 활성 구성원만 허용된다(서비스가 검증). */
data class OrgRoleAssignRequest(
    val userId: UUID? = null,
)

/**
 * 화면이 체크박스를 그릴 때 쓰는 능력 목록.
 *
 * `label` 은 서버가 준다 — 화면이 action 문자열을 자기 방식으로 번역하면, action 이 추가될 때 화면만
 * 모르는 항목이 생기고 그 항목은 목록에서 조용히 사라진다.
 */
data class GrantableActionView(
    val action: String,
    val group: String,
    val label: String,
)
