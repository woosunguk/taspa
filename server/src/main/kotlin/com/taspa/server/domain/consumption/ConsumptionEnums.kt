package com.taspa.server.domain.consumption

/**
 * 소비 이벤트 생산자(Phase 0ب-C). 결제·POS·수동입력·배치 임포트 등 어떤 생산자든 같은 로그에 적재된다.
 * 값은 소문자로 정규화해 저장한다(멱등키 (source, external_id) 의 안정성 확보).
 */
enum class ConsumptionSource {
    PAYMENT,
    POS,
    MANUAL,
    IMPORT,
    ;

    companion object {
        fun parse(value: String): ConsumptionSource =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("source 는 payment·pos·manual·import 중 하나여야 합니다")
    }
}

/** 식사 시간대. 집계 그룹핑 축. */
enum class MealWindow {
    BREAKFAST,
    LUNCH,
    DINNER,
    ;

    companion object {
        fun parse(value: String): MealWindow =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("meal_window 는 BREAKFAST·LUNCH·DINNER 중 하나여야 합니다")
    }
}

/** 소비 이벤트 상태. VOIDED(환불·취소)는 집계에서 제외된다. */
enum class ConsumptionEventStatus {
    CONFIRMED,
    VOIDED,
    ;

    companion object {
        fun parse(value: String): ConsumptionEventStatus =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("status 는 CONFIRMED 또는 VOIDED 여야 합니다")
    }
}
