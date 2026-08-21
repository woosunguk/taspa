package com.taspa.server.domain.meal

import java.time.LocalTime

/**
 * 식대 정책의 **값**(한도 3 + 끼니창 3쌍). 엔티티가 아니라 계산 입력의 계약이다.
 *
 * ★이 인터페이스를 뽑은 이유는 재사용이 아니라 **오염 차단**이다. 상속 해석기가 등장하면 "조직 기본값을
 * 읽어서 부서 재정의를 얹은 결과"가 필요해지는데, 그걸 만들기 가장 쉬운 방법이 `MealPolicy` 엔티티를
 * 읽어 필드를 덮어쓰는 것이다. 그 순간 Hibernate 의 dirty checking 이 트랜잭션 커밋 때 **조직 기본
 * 정책 행 자체를 부서값으로 UPDATE** 한다 — 한 부서의 재정의가 전사 정책을 조용히 갈아엎는다.
 * 계산 경로가 이 인터페이스만 받으면 해석 결과는 [com.taspa.server.meal.EffectiveMealPolicy] 같은
 * 순수 data class 로 만들 수밖에 없고(엔티티가 아니므로 영속성 컨텍스트가 관여하지 않는다),
 * 그 사고가 구조적으로 불가능해진다.
 *
 * 금액 단위는 minor(KRW 원). 시각은 org 로컬 시각으로 판정한다.
 */
interface MealPolicyValues {
    /** 1식 조직 부담 상한. 초과분은 거절이 아니라 self_paid 로 분리 승인된다. */
    val perMealLimitMinor: Long

    /** 하루 승인 거래수 상한. */
    val dailyMealCount: Int

    /** 월 조직 부담 누적 상한. */
    val monthlyCapMinor: Long

    val breakfastStart: LocalTime
    val breakfastEnd: LocalTime
    val lunchStart: LocalTime
    val lunchEnd: LocalTime
    val dinnerStart: LocalTime
    val dinnerEnd: LocalTime
}
