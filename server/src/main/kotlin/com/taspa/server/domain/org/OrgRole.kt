package com.taspa.server.domain.org

/** 조직 내 역할. ORG_ADMIN 은 조직 스코프 관리(멤버·캘린더 피드), MEMBER 는 일반 소속. */
enum class OrgRole {
    MEMBER,
    ORG_ADMIN,
}
