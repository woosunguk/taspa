package com.taspa.server.domain.org

/** 멤버십 이력(SCD) 변경 유형. 각 스냅샷이 어떤 사건으로 남았는지 표시한다. */
enum class MembershipChangeType {
    JOINED,
    ROLE_CHANGED,
    ASSIGNED,
    ATTRIBUTES_UPDATED,
    REMOVED,
}
