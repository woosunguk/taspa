package com.taspa.server.risk

/** 로그인 리스크 판정 결과. 선언 순서가 곧 심각도 순서다(Comparable — `>= MEDIUM` 비교에 사용). */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}
