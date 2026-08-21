package com.taspa.server.meal

import com.taspa.server.domain.meal.MealPolicyValues
import java.time.LocalTime
import java.util.UUID

/**
 * 정책 필드의 단위. **끼니창은 쌍이 원자 단위**다 — 시작만 재정의하고 끝은 상위값을 쓰면
 * `start >= end` 인 "열리는 순간이 없는 창"이 조용히 만들어진다(MealPolicyCalculus.openWindows 가
 * 그런 창을 후보에서 빼므로, 사용자에겐 "저장했는데 점심이 사라졌다"로 나타난다).
 */
enum class MealPolicyField {
    PER_MEAL_LIMIT,
    DAILY_MEAL_COUNT,
    MONTHLY_CAP,
    BREAKFAST_WINDOW,
    LUNCH_WINDOW,
    DINNER_WINDOW,
}

/** 값이 어디서 왔는지. 우선순위는 DEPARTMENT > SITE > ORG > CODE_DEFAULT(구체적인 쪽이 이긴다). */
enum class PolicyScopeType {
    /** 조직이 정책 행을 만든 적이 없어 코드 기본값(MealPolicy 생성자 기본값)을 쓴 경우. */
    CODE_DEFAULT,
    ORG,
    SITE,
    DEPARTMENT,
}

/**
 * 한 필드의 출처. 화면이 "이 값은 개발팀 재정의입니다"라고 말할 수 있어야 하는 이유는 단순하다 —
 * 상속이 붙는 순간 "왜 내 한도가 옆자리와 다른가"가 가장 흔한 문의가 되고, 출처를 못 보여주면
 * 조직관리자가 설정 화면 세 곳을 뒤져야 답할 수 있다.
 */
data class PolicySource(
    val scope: PolicyScopeType,
    val scopeId: UUID? = null,
    val label: String? = null,
    /** 기간 한정 재정의로 값이 정해졌는지(후속 단계). 지금은 항상 false. */
    val temporary: Boolean = false,
)

/**
 * 해석 결과 값 묶음.
 *
 * ★**엔티티가 아니다.** Hibernate 가 관리하지 않으므로 여기에 무엇을 담아도 dirty checking 이
 * `meal_policies` 행을 건드리지 않는다. 상속 해석이 붙으면 "조직 기본을 읽어 부서값으로 덮는" 코드가
 * 자연스럽게 나오는데, 그 대상이 엔티티였다면 커밋 시점에 **조직 기본 정책이 부서값으로 UPDATE** 된다.
 * 이 타입이 그 사고를 구조적으로 막는다([MealPolicyValues] KDoc 참고).
 */
data class EffectiveMealPolicy(
    override val perMealLimitMinor: Long,
    override val dailyMealCount: Int,
    override val monthlyCapMinor: Long,
    override val breakfastStart: LocalTime,
    override val breakfastEnd: LocalTime,
    override val lunchStart: LocalTime,
    override val lunchEnd: LocalTime,
    override val dinnerStart: LocalTime,
    override val dinnerEnd: LocalTime,
) : MealPolicyValues {
    companion object {
        /** 어떤 [MealPolicyValues] 든 순수 값으로 복사한다 — 엔티티를 계산 경로 밖으로 내보내는 유일한 문. */
        fun from(values: MealPolicyValues): EffectiveMealPolicy =
            EffectiveMealPolicy(
                perMealLimitMinor = values.perMealLimitMinor,
                dailyMealCount = values.dailyMealCount,
                monthlyCapMinor = values.monthlyCapMinor,
                breakfastStart = values.breakfastStart,
                breakfastEnd = values.breakfastEnd,
                lunchStart = values.lunchStart,
                lunchEnd = values.lunchEnd,
                dinnerStart = values.dinnerStart,
                dinnerEnd = values.dinnerEnd,
            )
    }
}

/** 값 + 필드별 출처. 승인/자격 계산은 [policy] 만 쓰고, [sources] 는 표시 전용이다. */
data class ResolvedMealPolicy(
    val policy: EffectiveMealPolicy,
    val sources: Map<MealPolicyField, PolicySource>,
) {
    fun sourceOf(field: MealPolicyField): PolicySource = sources[field] ?: PolicySource(PolicyScopeType.CODE_DEFAULT)
}

/**
 * 정책 해석에 필요한 멤버의 소속 축. 멤버십 행에서 그대로 온다.
 *
 * 조직 전체 기본값을 볼 때처럼 **멤버가 없는 해석**도 있으므로 두 축 모두 nullable 이다.
 */
data class MemberPolicyScope(
    val departmentId: UUID? = null,
    val siteId: UUID? = null,
) {
    companion object {
        val ORG_DEFAULT = MemberPolicyScope()
    }
}
