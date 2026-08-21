package com.taspa.server.domain.org

/** 재직 상태(HR 속성). 기본 EMPLOYED. ON_LEAVE(휴직)·TERMINATED(퇴직)는 예측 모집단에서 제외 판정에 쓰인다. */
enum class EmploymentStatus {
    EMPLOYED,
    ON_LEAVE,
    TERMINATED,
}
