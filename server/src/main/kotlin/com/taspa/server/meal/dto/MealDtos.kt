package com.taspa.server.meal.dto

import java.time.Instant
import java.util.UUID

/** QR 발급 요청 — 사용자가 소속 중 어느 조직의 식대로 결제할지 선택한다(활성 멤버십 필수). */
data class MealQrIssueRequest(
    val orgId: UUID? = null,
)

/** QR 발급 응답 — token 원문은 이 응답에만 존재한다(저장은 SHA-256 해시만). */
data class MealQrIssueResponse(
    val token: String,
    val expiresAt: Instant,
)

/**
 * 본인 거래 이력 1행(계정/모바일 화면용).
 *
 * `orgId` 가 있어야 화면이 "이 거래는 어느 조직 식대였나"를 말할 수 있다. 한도·집계는 전부
 * (사용자 × 조직) 단위인데(MealRedeemService) 이 필드가 없으면 소속이 둘 이상인 사람의 이력을
 * 조직에 귀속시킬 방법이 없어, 화면이 조직 구분 없는 합계만 보여주게 된다.
 */
data class MealTransactionView(
    val authId: String,
    val orgId: UUID,
    val merchantName: String?,
    val amountMinor: Long,
    val selfPaidMinor: Long,
    val mealWindow: String,
    val status: String,
    val approvedAt: Instant,
    val voidedAt: Instant?,
    /**
     * 환불 누계(0 이면 환불 없음)와 그중 **본인이 돌려받은 금액**.
     *
     * ★이 필드가 없으면 화면은 거짓말을 한다. 부분 환불은 `amountMinor`·`selfPaidMinor` 를 **소급
     * 변경**하므로(V36 — 기존 집계가 쿼리 수정 없이 맞도록 한 의도적 설계), 15,000원을 쓰고 3,000원을
     * 돌려받은 사람의 이력에는 그냥 12,000원만 남는다. 영수증과 숫자가 다른데 화면은 이유를 말하지
     * 못하고, 사용자는 시스템이 금액을 잘못 기록했다고 의심하게 된다.
     */
    val refundedMinor: Long = 0,
    val selfRefundedMinor: Long = 0,
    /** 환불 전 원금(= amountMinor + refundedMinor). 화면이 돈 계산을 하지 않게 서버가 준다. */
    val originalAmountMinor: Long = 0,
    val lastRefundedAt: Instant? = null,
)

/**
 * 끼니창 1회차 — 정책의 로컬 시각 구간과 그 회차의 절대 시각 구간.
 *
 * `start`/`end` 는 org 로컬 벽시계 문자열("11:30")이라 화면이 타임존 변환 없이 정책을 그대로 보여줄 수
 * 있고, `startsAt`/`endsAt` 는 절대 시각이라 카운트다운·정렬에 쓴다. 둘 다 주는 이유는 화면이
 * 기기 시계로 로컬 시각을 **재계산하지 않게** 하기 위해서다(기기 타임존이 org 와 다를 수 있다).
 */
data class MealWindowView(
    val window: String,
    val start: String,
    val end: String,
    val startsAt: Instant,
    val endsAt: Instant,
)

/**
 * 내 식대 자격 — "지금 결제되나 / 이번 달 얼마 남았나"에 대한 **서버의 답**.
 *
 * 모든 값은 `MealRedeemService.redeem` 이 승인 시 쓰는 것과 같은 계산(MealPolicyCalculus + 같은 집계
 * 쿼리)에서 나온다. 화면이 근사하거나 재구현하면 "화면은 가능인데 POS 는 거절"이 생기기 때문이다.
 * 집계 창(periodStart/periodEnd, dayStart/dayEnd)을 함께 실어 화면이 "무슨 기간의 숫자"인지 밝히게 한다.
 */
data class MealEntitlementView(
    val orgId: UUID,
    val orgName: String,
    /** 모든 경계 판정의 앵커가 된 org 타임존(IANA). */
    val timezone: String,
    /** 서버 기준 현재 시각 — 화면이 기기 시계 오차를 보정할 유일한 근거. */
    val serverNow: Instant,
    /** 지금 열려 있는 끼니창. null 이면 지금 redeem 은 MEAL_WINDOW_CLOSED 로 거절된다. */
    val currentWindow: MealWindowView?,
    /** 다음에 열릴 끼니창(정책에 유효한 창이 하나도 없으면 null). */
    val nextWindow: MealWindowView?,
    val perMealLimitMinor: Long,
    val dailyMealCount: Int,
    val todayApprovedCount: Long,
    val dailyRemaining: Long,
    val monthlyCapMinor: Long,
    val monthOrgPaidMinor: Long,
    val monthSelfPaidMinor: Long,
    val monthRemainingMinor: Long,
    val monthApprovedCount: Long,
    /** 월 누계 집계 창(org 로컬 달력 [periodStart, periodEnd)). */
    val periodStart: Instant,
    val periodEnd: Instant,
    /** 오늘 횟수 집계 창(org 로컬 일 경계). */
    val dayStart: Instant,
    val dayEnd: Instant,
    /**
     * 지금 발급해도 승인될 조건인지 — 끼니창 열림 AND 일 횟수 잔여.
     * 월 cap 소진은 여기에 넣지 않는다: redeem 은 cap 초과를 거절이 아니라 **개인부담 분리 승인**으로
     * 처리하므로(설계 §6), cap 이 0 이어도 결제 자체는 된다.
     */
    val canIssueNow: Boolean,
    /**
     * 각 값의 출처(CODE_DEFAULT | ORG | SITE | DEPARTMENT). **표시 전용**이다 — 계산에는 쓰지 않는다.
     *
     * 지금은 전부 ORG(또는 정책 미설정 조직의 CODE_DEFAULT)라 화면에 큰 의미가 없지만, 부서 재정의가
     * 붙는 순간 "왜 내 한도가 옆자리와 다른가"가 가장 흔한 문의가 된다. 그때 직원이 스스로 답을 볼 수
     * 있게 자리를 지금 만들어 둔다. 기본값이 있어 기존 응답 계약은 그대로다.
     */
    val perMealLimitSource: String? = null,
    val dailyMealCountSource: String? = null,
    val monthlyCapSource: String? = null,
    /** 끼니창은 쌍이 원자 단위라 창 3개의 출처를 한 값으로 묶는다(지금은 셋이 항상 같다). */
    val windowSource: String? = null,
)

/** POS redeem 요청. 금액은 POS 가 결정한다(QR 에 금액 없음 — 위조 무의미). */
data class RedeemRequest(
    val token: String = "",
    val amountMinor: Long = 0,
    val posTxnId: String = "",
    /**
     * 손님이 받은 메뉴(선택). 한 끼니에 메뉴가 여러 개일 때 **단말만이 알 수 있는 정보**다 —
     * 서버가 추측하면 절반의 확률로 틀린 메뉴의 인기가 올라가고 그 왜곡은 집계에만 나타난다.
     *
     * ★해석에 실패해도(다른 조직 메뉴·그 끼니에 없는 메뉴·삭제된 메뉴) **결제를 거절하지 않는다.**
     * 메뉴는 분석 축이고 결제는 돈이다 — 메타데이터 불일치로 손님을 계산대에 세워 둘 이유가 없다.
     * 대신 응답의 `menuName` 이 null 로 와서 단말이 "메뉴 미기록"을 알 수 있다(조용히 삼키지 않는다).
     */
    val menuId: java.util.UUID? = null,
)

/**
 * redeem 응답. approvedAmountMinor = 조직 부담(청구 대상), selfPaidMinor = 한도 초과 개인부담
 * (POS 가 현장에서 별도 수취 — 실 자금이동은 플랫폼 밖). POS 멱등 재전송은 같은 authId 를 재반환한다.
 */
data class RedeemResponse(
    val authId: String,
    val approvedAmountMinor: Long,
    val selfPaidMinor: Long,
    val mealWindow: String,
    val status: String,
    /**
     * 이 거래에 귀속된 메뉴 이름. null 이면 기록되지 않았다는 뜻이다(그 끼니에 식단이 없거나,
     * 메뉴가 여럿인데 단말이 고르지 않았거나, 보낸 menuId 가 그 끼니의 메뉴가 아니었다).
     */
    val menuName: String? = null,
    /**
     * 이번 환불에서 **각자에게 돌아간 금액**. 환불 응답에만 채워진다(승인·취소는 null).
     *
     * ★`selfRefundedMinor` 가 계산원이 손님에게 **현금으로 돌려줄 금액**이다. 이게 없으면 단말은
     * 환불 후의 `selfPaidMinor`(줄어든 개인부담)만 알게 되는데, 그건 "앞으로 받을 금액"이지
     * "지금 돌려줄 금액"이 아니다 — 손님은 이미 옛 금액을 냈다. 단말이 두 값의 차로 유추하게 두면
     * 분담 결정이 서버 몫이라는 계약이 흐려지고, 유추가 틀리면 현금이 틀린다.
     */
    val orgRefundedMinor: Long? = null,
    val selfRefundedMinor: Long? = null,
)

/**
 * POS 부분 환불 요청.
 *
 * `posRefundId` 는 **단말이 생성해 재시도에서 재사용**하는 멱등키다(승인의 `posTxnId` 와 같은 형태).
 * 서버가 매번 새로 만들면 통신 단절 후 재시도가 이중 환불이 되고, 그건 그대로 회사·직원의 손실이다.
 */
data class RefundRequest(
    val amountMinor: Long = 0,
    val posRefundId: String = "",
    val reason: String? = null,
)
