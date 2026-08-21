package com.taspa.server.domain.org

/** 고용 형태(HR 속성, nullable). 예측 모집단 분해·정산 자격 산정의 입력. */
enum class EmploymentType {
    FULL_TIME,
    PART_TIME,
    CONTRACT,
    INTERN,
}
