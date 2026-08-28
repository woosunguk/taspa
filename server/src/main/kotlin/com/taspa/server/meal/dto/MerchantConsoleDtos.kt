package com.taspa.server.meal.dto

import com.taspa.server.forecast.dto.BacktestSummary
import com.taspa.server.forecast.dto.ForecastMethod
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 내가 관리하는 가맹점 1건(매장 선택 화면). timezone 은 이 매장의 하루 경계 앵커라 화면이 "오늘"을
 * 서버와 같은 기준으로 그릴 수 있게 함께 내려준다.
 */
data class MyMerchantView(
    val merchantId: UUID,
    val name: String,
    val category: String,
    val status: String,
    val timezone: String,
    val role: String,
)

/**
 * "관리자로 지정돼 있지만 **지금은 열 수 없는**" 매장 1건.
 *
 * ★이 DTO 는 [MyMerchantView] 와 **의도적으로 분리**돼 있다. 콘솔 진입 집합(`listMyMerchants`)과 인가
 * 판정(`isActiveMerchantAdmin`)은 정확히 같은 조건이어야 "목록에 보이는데 열면 403" 이 생기지 않는데,
 * 그 불변식을 지키려고 비활성 매장을 **응답에서 통째로 지웠더니** 더 나쁜 일이 생겼다: 플랫폼 관리자가
 * 절차대로 매장을 만들고 담당자를 지정해도(양쪽 다 성공을 보고한다) 그 담당자에게는 매장이 **존재하지
 * 않는 것과 똑같이** 보이고, 빈 화면은 "권한은 플랫폼 운영자가 부여합니다" 라며 **이미 가진 권한을 다시
 * 요청하라**고 안내했다. 사장은 플랫폼에 문의하고 관리자 화면에는 담당자가 ACTIVE 로 보이니, 양쪽이
 * 어긋난 채 신규 가맹 온보딩이 그 자리에서 멈춘다(등록 모달 기본값이 PENDING 이라 **기본 경로**였다).
 *
 * 그래서 진입 가능 집합은 그대로 두고 **사유를 갖는 별도 목록**으로 내려보낸다 — 화면은 이 항목을
 * 링크가 아니라 안내로 그린다(열 수 있는 것처럼 보이지 않으면서, 존재는 숨기지 않는다).
 */
data class BlockedMerchantView(
    val merchantId: UUID,
    val name: String,
    /** 매장 상태 원문(PENDING|SUSPENDED). 화면이 사유 문구를 고르는 근거. */
    val status: String,
)

/**
 * POS 단말이 자기 결속을 확인하는 최소 신원(`GET /api/merchant/me`).
 *
 * 계산원이 화면에서 "이 단말은 어느 가게 것인가"를 볼 수 있게 하는 것이 유일한 목적이다 —
 * 손님 정보도 금액도 담지 않는다.
 */
data class MerchantIdentityView(
    val merchantId: UUID,
    val name: String,
    val category: String,
    val timezone: String,
    /**
     * 정액 단가(원). null 이면 POS 가 금액을 직접 입력받는다.
     *
     * ★이 값은 **금액이 아니라 설정**이다 — 서버는 여전히 redeem 요청의 `amountMinor` 로 승인한다.
     *   가격 결정을 서버로 옮기지 않는 이유: POS 는 이미 merchant 결속 M2M 신원이라 금액을 정할 권한이
     *   있고(오늘도 그렇다), 서버가 가격을 강제하면 품목별 가격·행사가를 넣을 자리가 사라진다.
     *   여기서 내려보내는 것은 "이 매장은 정액이다"라는 사실뿐이다.
     */
    val defaultPriceMinor: Long?,
)

/** [MyMerchantView] 목록 + 열 수 없는 매장의 사유. 화면 진입점이 한 번에 필요한 사실 전부. */
data class MyMerchantsResponse(
    val merchants: List<MyMerchantView>,
    val blocked: List<BlockedMerchantView>,
)

/**
 * 가맹 거래 로그 1행 — **손님 개인 식별 최소화**.
 *
 * 가맹점은 "누가 먹었는지"를 알 필요가 없다: 매장이 하는 일은 인분 준비와 정산 대사(對査)이고, 그 둘 다
 * 조직·끼니·금액·시각으로 충분하다. 따라서 userId·이메일·표시이름은 이 응답에 **존재하지 않는다**
 * (필드를 비우는 것이 아니라 DTO 에 자리가 없다 — 실수로 채워질 여지를 없앤다).
 *
 * authId·posTxnId 는 손님 정보가 아니라 **매장 자신의 거래 참조키**다(POS 가 이미 보유). 대사·이의제기
 * 때 이 키가 없으면 매장이 특정 건을 지목할 수 없어 포함한다.
 * orgPaidMinor = amountMinor − selfPaidMinor(조직 부담 = 청구 대상), selfPaidMinor = 한도 초과 개인부담.
 */
data class MerchantTransactionView(
    val authId: String,
    val posTxnId: String,
    val orgName: String?,
    val mealWindow: String,
    val amountMinor: Long,
    val orgPaidMinor: Long,
    val selfPaidMinor: Long,
    val status: String,
    val approvedAt: Instant,
    val voidedAt: Instant?,
    /**
     * 환불 누계와 주머니별 분담. 매장은 정산 대사에서 "이 거래로 결국 얼마를 받는가"를 알아야 하는데,
     * 부분 환불이 `amountMinor` 를 소급 변경하므로 원금과 환불액이 없으면 영수증·POS 기록과 맞춰볼 수
     * 없다. `refundCount` 는 여러 번 나눠 환불한 거래를 한 건으로 착각하지 않게 한다.
     *
     * 손님 개인정보는 여전히 싣지 않는다 — 매장에 필요한 건 금액과 조직이지 누구인지가 아니다.
     */
    val refundedMinor: Long = 0,
    val orgRefundedMinor: Long = 0,
    val selfRefundedMinor: Long = 0,
    /** 환불 전 원금(= amountMinor + refundedMinor). */
    val originalAmountMinor: Long = 0,
    val refundCount: Int = 0,
    val lastRefundedAt: Instant? = null,
)

/**
 * 거래 로그 응답. from/to 는 **실제 조회에 쓰인** 매장-로컬 날짜이고, requestedFrom/requestedTo 는 요청값
 * 그대로다 — 창 상한에 걸려 좁혀졌으면 windowTruncated=true 와 함께 그 사실이 응답에 드러난다(조용한 절단 금지).
 */
data class MerchantTransactionsResponse(
    val merchantId: UUID,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val requestedFrom: LocalDate,
    val requestedTo: LocalDate,
    val windowTruncated: Boolean,
    val limit: Int,
    val rowsTruncated: Boolean,
    val rows: List<MerchantTransactionView>,
)

/**
 * 가맹 예측 산출 근거(설명가능성). lastWeekActual = 전주 동요일 실적(인분 수량 합),
 * sampleWeeks = 폴백 평균에 실제로 쓰인 주 수(SEASONAL_NAIVE 는 1, FOUR_WEEK_AVG 는 1~3, NO_DATA 는 0).
 *
 * ★조직 예측의 ForecastBasis 와 달리 재실 인원(headcount) 항목이 없다 — 가맹점에는 "재실 인원"이라는
 * 모수 자체가 없기 때문이다(불특정 다수 손님). 필드를 null 로 두는 대신 아예 두지 않는다.
 */
data class MerchantForecastBasis(
    val lastWeekActual: Long?,
    val sampleWeeks: Int,
)

/**
 * 가맹 예측 셀의 **조직 분해** 한 조각. 매장 총합은 조직별 예측의 합이고, 캘린더·연차 신호는
 * 이 조각 단위로만 적용된다(매장 총합에 A 조직 휴일을 곱하면 B 조직 손님까지 깎인다).
 */
data class MerchantOrgSlice(
    val orgId: UUID,
    val orgName: String,
    val predicted: Long?,
    val method: ForecastMethod,
    /** 그 조직 캘린더가 이 날을 휴일/행사로 선언했는가 — 총합이 낮은 이유를 화면이 설명하는 근거. */
    val holiday: Boolean,
    val holidayName: String?,
    val event: Boolean,
    val eventName: String?,
    /** 그 조직의 이 날짜 부재(연차·반차 가중 합). 신호를 껐으면 0. */
    val absentWeight: Double,
)

/** 가맹 예측 셀 — (날짜 × 끼니) 그레인. predicted=null 은 "0 인분"이 아니라 "데이터 없음"(NO_DATA)이다. */
data class MerchantForecastCell(
    val date: LocalDate,
    val mealWindow: String,
    val predicted: Long?,
    val method: ForecastMethod,
    val basis: MerchantForecastBasis,
    /**
     * 조직별 분해. 합 = predicted 가 원칙이나, 일부 조직이 NO_DATA 면 그 조직 몫을 알 수 없어
     * [partial] 이 true 가 된다 — 그때 predicted 는 **아는 조직의 합**이고 하한으로 읽어야 한다
     * (모르는 몫을 0 으로 위장하는 것보다 하한임을 드러내는 것이 정직하다).
     */
    val orgs: List<MerchantOrgSlice> = emptyList(),
    val partial: Boolean = false,
    /** 매장-로컬 **오늘** 셀에서, 지금까지 이미 나간 인분(nowcast). 다른 날짜는 null. */
    val soFar: Long? = null,
)

/** 가맹 예측 응답 — 집계 파생값만(개별 이벤트·손님 식별자 없음). 창 절단은 응답에 정직하게 드러난다. */
data class MerchantForecastResponse(
    val merchantId: UUID,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val requestedFrom: LocalDate,
    val requestedTo: LocalDate,
    val windowTruncated: Boolean,
    val mealWindow: String?,
    val cells: List<MerchantForecastCell>,
    /** 이 매장을 이용하는 조직(실적 기준). 예측 분해와 같은 판정을 쓴다. */
    val orgs: List<MerchantOrgInfo> = emptyList(),
)

/** 이 매장을 이용 중인 조직 한 줄 — 가맹 콘솔 "이용 조직" 섹션. */
data class MerchantOrgInfo(
    val orgId: UUID,
    val name: String,
    /** 최근 28일 인분 수(이 매장에서). */
    val recentPortions: Long,
    /** 앞으로 14일 내 이 조직의 휴일·행사 수(조직 캘린더 선언 기준). */
    val upcomingHolidays: Int,
    val upcomingEvents: Int,
    /** 앞으로 14일 부재 인일(person-day, 가중) 합. */
    val upcomingAbsentWeight: Double,
)

/** 가맹 백테스트 셀 — "그 시점에 예측했을 값"(predicted) vs 실적(actual). 실적이 없으면 actual=0. */
data class MerchantBacktestCell(
    val date: LocalDate,
    val mealWindow: String,
    val predicted: Long?,
    val method: ForecastMethod,
    val actual: Long,
    val basis: MerchantForecastBasis,
)

/**
 * 가맹 백테스트 응답. summary 는 조직 백테스트와 같은 지표 정의를 재사용한다(BacktestSummary) —
 * 지표 해석이 조직/가맹에서 갈리지 않게 하기 위해서다.
 */
data class MerchantBacktestResponse(
    val merchantId: UUID,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val requestedFrom: LocalDate,
    val requestedTo: LocalDate,
    val windowTruncated: Boolean,
    val mealWindow: String?,
    val cells: List<MerchantBacktestCell>,
    val summary: BacktestSummary,
)

/**
 * 정산 명세의 조직별 한 줄. 매장은 어느 고객사에서 얼마가 나왔는지로 자기 영업을 보고, 이의제기 때
 * 조직 단위로 특정한다. 조직이 삭제된 과거 거래는 이름이 null 이다(장부는 불변).
 */
data class MerchantSettlementLine(
    val orgId: UUID,
    val orgName: String?,
    val approvedCount: Long,
    /** 이 조직 손님의 결제 중 **조직이 부담**하는 금액 = 우리가 매장에 줄 몫. */
    val orgPaidMinor: Long,
    /** 손님이 계산대에서 **이미 낸** 금액(한도 초과분). 우리가 줄 돈이 아니다. */
    val selfPaidMinor: Long,
    val refundedMinor: Long,
)

/**
 * 가맹 월 정산 명세.
 *
 * ★**실제 자금이동은 이 시스템에 없다.** 이 명세는 "얼마를 주고받아야 하는가"의 집계이고, 실 지불은
 * 별도 절차다. 화면도 그 사실을 말한다 — 정산서를 지급 완료로 오해하면 매장이 입금을 기다리지 않는다.
 *
 * ★`payableMinor`(= Σ 조직부담)만이 우리가 매장에 줄 돈이다. `selfPaidTotalMinor` 는 손님이 계산대에서
 * 직접 낸 돈이라 **매장이 이미 받았다** — 둘을 더하면 매장은 받을 돈을 두 배로 기대하게 된다. 그래서
 * 이 DTO 는 총결제액(gross)을 따로 두지 않고 두 주머니를 갈라서만 보여준다.
 *
 * 창은 **매장 타임존** 앵커다. 조직 청구서는 조직 타임존 앵커라 경계일 거래만큼 **정당하게 다를 수 있다**
 * (한 매장이 여러 조직 손님을 받으므로 어느 조직 달력도 빌릴 수 없다 — 예측 그레인과 같은 이유).
 */
data class MerchantSettlementView(
    val merchantId: UUID,
    val merchantName: String,
    val period: String,
    val timezone: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val approvedCount: Long,
    val voidedCount: Long,
    /** 우리가 매장에 지급할 금액(조직 부담 합). **실 지불은 별도 절차다.** */
    val payableMinor: Long,
    /** 손님이 계산대에서 이미 낸 금액 합 — 참고용(지급 대상 아님). */
    val selfPaidTotalMinor: Long,
    val refundedTotalMinor: Long,
    val lines: List<MerchantSettlementLine>,
)

/** 전역 지급 현황의 매장별 한 줄 — 그 매장의 정산 명세를 운영자 시선으로 압축한 것. */
data class PlatformPayableLine(
    val merchantId: UUID,
    val merchantName: String,
    val timezone: String,
    val approvedCount: Long,
    /** 그 매장에 지급할 금액(= 그 매장 정산 명세의 payableMinor). */
    val payableMinor: Long,
    val refundedMinor: Long,
)

/**
 * 플랫폼 전역 지급 현황 — "이번 달 우리가 전 매장에 얼마 나가나".
 *
 * 매장별 정산은 그 매장 사장이 자기 몫을 확인할 때 연다. 이건 반대 방향이다 — 매장이 100개면 하나씩
 * 열어 볼 수 없고, 열어 보지 않으면 총액을 아무도 모른다.
 *
 * ★`scanned`·`skipped` 를 함께 낸다: 총액 0 이 "지급할 게 없다"인지 "**아무것도 안 봤다**"인지 구분되지
 * 않으면 이 화면은 안심시키는 역할만 하고 계획 도구가 되지 못한다(전역 대사와 같은 이유).
 *
 * ★창은 **매장마다 그 매장의 타임존** 월 경계다. 그래서 이 총액은 조직 청구서 총액과 경계일 거래만큼
 * 정당하게 다르다 — 두 문서가 서로 다른 달력을 쓴다는 사실이지 결함이 아니다.
 */
data class PlatformPayablesView(
    val period: String,
    /** 집계를 **시도한** 매장 수. */
    val scanned: Int,
    /** 상한에 걸려 시도조차 못 한 수. */
    val skipped: Int,
    /**
     * 시도했으나 **집계에 실패한** 매장 수(금액을 모른다).
     *
     * ★이 값이 없으면 일시적 오류로 빠진 매장의 지급액이 총액에서 조용히 사라지고, 화면은 "N개 매장
     * 전부 집계됨"이라고 말한다 — 자금 담당이 **실제보다 적은 금액**으로 이체 계획을 세운다.
     */
    val failed: Int,
    val totalPayableMinor: Long,
    val totalRefundedMinor: Long,
    val totalApprovedCount: Long,
    val lines: List<PlatformPayableLine>,
)

/**
 * POS 가 배식 코너를 고르기 위해 받는 오늘의 식단.
 *
 * **매장이 연결된 사업장의 조직 식단**이다(`merchants.site_id` → `sites.org_id`). 연결이 없으면 빈
 * 목록이다 — 여러 조직 손님을 받는 매장은 "누구의 식단인가"가 결정 불가라 추측하지 않는다(타임존을
 * 빌릴 수 없는 것과 같은 이유). 그 경우 POS 는 메뉴 버튼을 띄우지 않고, 그 끼니 메뉴가 하나뿐인
 * 조직이면 서버가 자동 귀속한다.
 */
data class MerchantMenuView(
    val menuId: java.util.UUID,
    val name: String,
    val category: String,
    val corner: String?,
)

data class MerchantMenusResponse(
    val mealWindow: String,
    val menuDate: java.time.LocalDate,
    val menus: List<MerchantMenuView>,
)

/** 셀 상세의 basis 한 점 — "이 날짜의 이 실적을 근거로 썼다". */
data class MerchantBasisPoint(
    val date: java.time.LocalDate,
    val actual: Long,
)

/** 셀 상세의 조직 조각 — 목록 응답의 조각 + 근거 날짜들. */
data class MerchantOrgSliceDetail(
    val slice: MerchantOrgSlice,
    /** 실제로 채택된 basis(같은 성격의 날만). 비어 있으면 근거 없음(NO_DATA). */
    val basis: List<MerchantBasisPoint>,
    /** 그 조직의 현재 재직 인원(부재 비율의 분모). 신호 OFF 면 null. */
    val headcount: Long?,
)

/** 셀 상세의 메뉴 한 줄 — "어떤 메뉴가 몇 인분인가"에 대한 답. */
data class MerchantMenuShare(
    val name: String,
    val corner: String?,
    val category: String,
    val plannedPortions: Int?,
    /**
     * 이 매장 실적(menu_ref)에서 배운 선택 비율. **근거가 없으면 null** — 균등 분배를 지어내지 않는다
     * (메뉴가 둘이니 반반"을 내려보내면 화면은 그것을 예측으로 표시한다).
     */
    val share: Double?,
    /** 사업장 조직 몫 예측 × 비율. share 또는 조직 예측이 없으면 null. */
    val predicted: Long?,
    /** 학습 표본(그 메뉴로 기록된 인분 수 합) — 비율의 신뢰 근거를 화면이 밝힐 수 있게. */
    val sampleQuantity: Long,
)

/**
 * (날짜 × 끼니) 셀 하나의 **근거 전체** — 화면의 숫자를 클릭했을 때 "왜 이 숫자인가"에 답하는 응답.
 * 셀 자체는 목록 API 와 같은 계산(compositeCell)을 그대로 쓴다 — 상세가 목록과 다른 숫자를 말하면
 * 상세가 아니라 두 번째 의견이 된다.
 */
data class MerchantCellDetail(
    val date: java.time.LocalDate,
    val mealWindow: String,
    val timezone: String,
    val cell: MerchantForecastCell,
    val orgs: List<MerchantOrgSliceDetail>,
    /** 사업장 조직의 그 끼니 식단 + 메뉴별 분해. 식단이 없거나 사업장 미연결이면 빈 목록. */
    val menus: List<MerchantMenuShare>,
    /** 메뉴 비율 학습 창(있을 때만) — "언제부터의 실적으로 배운 비율인가". */
    val menuLearnFrom: java.time.LocalDate?,
    val menuLearnTo: java.time.LocalDate?,
)
