package com.taspa.server.domain.meal

/** 가맹 업종. */
enum class MerchantCategory {
    RESTAURANT,
    CONVENIENCE,
    CAFE,
    ;

    companion object {
        fun parse(value: String): MerchantCategory =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("category 는 RESTAURANT·CONVENIENCE·CAFE 중 하나여야 합니다")
    }
}

/** 가맹 상태. ACTIVE 만 redeem 가능(fail-closed). */
enum class MerchantStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    ;

    companion object {
        fun parse(value: String): MerchantStatus =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("status 는 PENDING·ACTIVE·SUSPENDED 중 하나여야 합니다")
    }
}

/** 거래 상태. 폐쇄루프 장부 — REFUNDED 없음(취소는 VOIDED 로 일원화, 실 자금이동이 없기 때문). */
enum class MealTransactionStatus {
    APPROVED,
    VOIDED,
}
