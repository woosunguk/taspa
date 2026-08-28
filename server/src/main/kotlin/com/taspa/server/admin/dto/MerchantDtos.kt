package com.taspa.server.admin.dto

import com.taspa.server.domain.meal.Merchant
import java.time.Instant
import java.util.UUID

/**
 * 가맹 등록/수정 요청. category/status 미지정 시 기본(RESTAURANT/ACTIVE). siteId 는 존재하는 사업장만.
 *
 * timezone 은 가맹 그레인 집계·예측의 하루 경계 앵커(V29)다. **생략(null/공백) 시 생성은 UTC, 수정은
 * 기존 값 유지**로 다룬다 — 다른 필드와 달리 full-replace 하지 않는 이유는, 이 값이 바뀌면 과거 집계의
 * 날짜 버킷이 통째로 이동해 화면의 "어제 몇 인분"이 소급 변경되기 때문이다(timezone 을 모르는 기존
 * 클라이언트의 부분 전송이 매장 달력을 UTC 로 되돌리는 사고를 막는다).
 */
data class MerchantUpsertRequest(
    val name: String = "",
    val category: String? = null,
    val status: String? = null,
    val siteId: UUID? = null,
    val timezone: String? = null,
    /**
     * 정액 단가(원). POS 가 이 값으로 **금액 입력 없이 즉시 승인**한다. 없으면 계산원이 직접 입력한다.
     *
     * ★timezone 과 같은 이유로 **미전송(null) = 기존 유지**다. full-replace 로 두면 이 필드를 모르는
     *   기존 클라이언트의 수정 요청이 매장 단가를 조용히 지워, 다음 결제부터 계산원이 금액을 손으로
     *   넣게 된다(화면 어디에도 이유가 없다). 해제는 [clearDefaultPrice] 로 **명시**한다 — 0 은 CHECK 가
     *   막으므로 "0 으로 지우기" 같은 우회 경로는 없다.
     */
    val defaultPriceMinor: Long? = null,
    /** true 면 정액 단가를 해제한다(금액 직접 입력으로 되돌림). `defaultPriceMinor` 보다 우선한다. */
    val clearDefaultPrice: Boolean = false,
)

data class MerchantView(
    val id: UUID,
    val name: String,
    val category: String,
    val status: String,
    val siteId: UUID?,
    val timezone: String,
    /** 정액 단가(원). null 이면 POS 가 금액을 직접 입력받는다. */
    val defaultPriceMinor: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(merchant: Merchant): MerchantView =
            MerchantView(
                id = merchant.id!!,
                name = merchant.name,
                category = merchant.category,
                status = merchant.status,
                siteId = merchant.siteId,
                timezone = merchant.timezone,
                defaultPriceMinor = merchant.defaultPriceMinor,
                createdAt = merchant.createdAt,
                updatedAt = merchant.updatedAt,
            )
    }
}

/**
 * 가맹 직원(사람 신원) 부여 요청 — 이메일로 기존 사용자를 찾는다. 역할은 MERCHANT_ADMIN 고정이며
 * 요청으로 지정할 수 없다(권한 확장 경로를 만들지 않는다 — SCIM 의 역할 고정과 같은 사상).
 */
data class MerchantMemberAddRequest(
    val email: String = "",
)

/**
 * 가맹 직원 1행(플랫폼 관리자 화면). 여기 email 은 **매장 직원**의 것이지 손님 정보가 아니다 —
 * 손님 개인정보를 가맹점에 노출하지 않는 규칙(MerchantTransactionView)과 무관한 표면이다.
 */
data class MerchantMemberView(
    val userId: UUID,
    val email: String?,
    val displayName: String?,
    val role: String,
    val status: String,
    val createdAt: Instant,
)
