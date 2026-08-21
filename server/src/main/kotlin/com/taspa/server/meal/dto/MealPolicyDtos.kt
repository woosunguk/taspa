package com.taspa.server.meal.dto

import java.time.Instant
import java.util.UUID

/**
 * 정책 편집 요청.
 *
 * 전 필드가 필수다(부분 갱신이 아니다). 이유: 끼니창은 쌍이 원자 단위이고 세 창이 서로 겹치면 안 되는데,
 * 부분 갱신이면 "점심 끝시각만" 같은 요청이 다른 창과의 관계를 모르는 채 들어온다. 화면이 전체 폼을
 * 보여주고 전체를 저장하는 편이 검증도 단순하고 사용자에게도 무엇이 바뀌는지 분명하다.
 *
 * 시각은 `HH:mm` 또는 `HH:mm:ss` 문자열(org 로컬 시각).
 */
data class MealPolicyUpdateRequest(
    val perMealLimitMinor: Long = 0,
    val dailyMealCount: Int = 0,
    val monthlyCapMinor: Long = 0,
    val breakfastStart: String = "",
    val breakfastEnd: String = "",
    val lunchStart: String = "",
    val lunchEnd: String = "",
    val dinnerStart: String = "",
    val dinnerEnd: String = "",
)

/** 정책 조회 응답. `source` 는 값이 어디서 왔는지(지금은 ORG 또는 CODE_DEFAULT). */
data class MealPolicyView(
    val orgId: UUID,
    val timezone: String,
    val perMealLimitMinor: Long,
    val dailyMealCount: Int,
    val monthlyCapMinor: Long,
    val breakfastStart: String,
    val breakfastEnd: String,
    val lunchStart: String,
    val lunchEnd: String,
    val dinnerStart: String,
    val dinnerEnd: String,
    /** 조직이 아직 한 번도 저장하지 않아 코드 기본값을 쓰는 상태인지. 화면이 "기본값"을 표시한다. */
    val usingDefaults: Boolean,
    /** 편집 폼이 사용자에게 상한을 미리 알려 주기 위한 값 — 서버도 같은 값으로 거절한다. */
    val ceilingPerMealLimitMinor: Long,
    val ceilingDailyMealCount: Int,
    val ceilingMonthlyCapMinor: Long,
    val updatedAt: Instant?,
)

/** 정책 변경 이력 한 줄. */
data class MealPolicyRevisionView(
    val id: UUID,
    val scopeType: String,
    val scopeLabel: String?,
    val changeType: String,
    /** 변경 후 전체 스냅샷(JSON 문자열 그대로 — 화면이 필요한 필드만 읽는다). */
    val document: String,
    /** false 면 플랫폼 운영자가 바꾼 것. 조직 화면에서 이 사실을 감추지 않는다. */
    val actorIsOrgMember: Boolean,
    val actorEmail: String?,
    val recordedAt: Instant,
)

/**
 * 부서·사업장 재정의 생성/수정 요청.
 *
 * ★전 필드가 nullable 이고 **null = 재정의하지 않음(상위값 물려받음)** 이다. 이 단순한 규칙 덕분에
 * 조직이 1식 한도를 올리면 그 값을 재정의하지 않은 부서는 자동으로 따라 오른다.
 *
 * `scopeType`/`scopeId` 로 붙일 축을 지정한다(DEPARTMENT | SITE). 시각은 `HH:mm`.
 */
data class MealPolicyOverrideRequest(
    val scopeType: String = "",
    val scopeId: String = "",
    val perMealLimitMinor: Long? = null,
    val dailyMealCount: Int? = null,
    val monthlyCapMinor: Long? = null,
    val breakfastStart: String? = null,
    val breakfastEnd: String? = null,
    val lunchStart: String? = null,
    val lunchEnd: String? = null,
    val dinnerStart: String? = null,
    val dinnerEnd: String? = null,
    /** 둘 다 null 이면 상시 재정의(노드당 1행). 하나라도 있으면 기간 한정이고 상시보다 우선한다. */
    val effectiveFrom: String? = null,
    val effectiveTo: String? = null,
    val reason: String? = null,
)

/** 재정의 목록 한 줄. `scopeLabel` 은 조회 시점 이름(이력과 달리 스냅샷이 아니다 — live 는 정합이 옳다). */
data class MealPolicyOverrideView(
    val id: UUID,
    val scopeType: String,
    val scopeId: UUID,
    val scopeLabel: String?,
    val perMealLimitMinor: Long?,
    val dailyMealCount: Int?,
    val monthlyCapMinor: Long?,
    val breakfastStart: String?,
    val breakfastEnd: String?,
    val lunchStart: String?,
    val lunchEnd: String?,
    val dinnerStart: String?,
    val dinnerEnd: String?,
    val effectiveFrom: String?,
    val effectiveTo: String?,
    val reason: String?,
    val updatedAt: Instant,
)

/** 미리보기 — "이 부서 사람에게는 실제로 얼마가 적용되는가"와 그 값이 어디서 왔는가. */
data class MealPolicyPreview(
    val scopeType: String,
    val scopeId: UUID?,
    val scopeLabel: String?,
    val perMealLimitMinor: Long,
    val dailyMealCount: Int,
    val monthlyCapMinor: Long,
    val breakfastStart: String,
    val breakfastEnd: String,
    val lunchStart: String,
    val lunchEnd: String,
    val dinnerStart: String,
    val dinnerEnd: String,
    /** 필드 → 출처(`CODE_DEFAULT`|`ORG`|`SITE`|`DEPARTMENT`). 화면이 "이 값은 개발팀 재정의"를 말하게 한다. */
    val sources: Map<String, String>,
    val sourceLabels: Map<String, String?>,
)
