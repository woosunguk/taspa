package com.taspa.server.meal

import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyOverrideRepository
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.SiteRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Optional
import java.util.UUID

/**
 * 정책 해석기 — 승인(POS)과 자격 조회(직원 화면)가 공유하는 단일 입구.
 *
 * 여기서 잠그는 것은 두 가지다: **폴백이 하나뿐**이라는 것(예전엔 두 경로가 각자 `orElseGet` 을 갖고
 * 있어 갈라질 수 있었다)과, 해석 결과가 **엔티티가 아니라 값**이라는 것. 후자는 상속이 붙을 때
 * "조직 기본을 읽어 부서값으로 덮는" 코드가 Hibernate dirty checking 으로 조직 정책 행 자체를
 * UPDATE 해 버리는 사고를 구조적으로 막는 장치다.
 *
 * DB 없이 도는 순수 단위 테스트다(리포지터리만 mock).
 */
class MealPolicyResolverTest {
    private val repository = mockk<MealPolicyRepository>()

    // 재정의 없는 조직(대다수)의 동작을 잠근다 — 이 경로가 도입 전과 정확히 같아야 회귀가 아니다.
    private val overrideRepository =
        mockk<MealPolicyOverrideRepository>().also {
            every { it.findByOrgId(any()) } returns emptyList()
        }
    private val departmentRepository = mockk<DepartmentRepository>()
    private val siteRepository = mockk<SiteRepository>()
    private val resolver = MealPolicyResolver(repository, overrideRepository, departmentRepository, siteRepository)
    private val orgId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 7, 27)

    @Test
    fun `정책 행이 있으면 그 값을 쓰고 출처는 ORG 다`() {
        every { repository.findById(orgId) } returns
            Optional.of(
                MealPolicy(orgId = orgId, perMealLimitMinor = 15000, dailyMealCount = 2, monthlyCapMinor = 300000),
            )

        val resolved = resolver.resolve(orgId, MemberPolicyScope.ORG_DEFAULT, today)

        assertThat(resolved.policy.perMealLimitMinor).isEqualTo(15000)
        assertThat(resolved.policy.dailyMealCount).isEqualTo(2)
        assertThat(resolved.policy.monthlyCapMinor).isEqualTo(300000)
        assertThat(resolved.sourceOf(MealPolicyField.PER_MEAL_LIMIT).scope).isEqualTo(PolicyScopeType.ORG)
        assertThat(resolved.sourceOf(MealPolicyField.PER_MEAL_LIMIT).scopeId).isEqualTo(orgId)
    }

    @Test
    fun `정책 행이 없으면 코드 기본값이고 출처가 CODE_DEFAULT 로 드러난다`() {
        every { repository.findById(orgId) } returns Optional.empty()

        val resolved = resolver.resolve(orgId, MemberPolicyScope.ORG_DEFAULT, today)

        // 코드 기본값(MealPolicy 생성자) — redeem 과 자격 조회가 각자 갖고 있던 폴백을 하나로 모았다.
        assertThat(resolved.policy.perMealLimitMinor).isEqualTo(12000)
        assertThat(resolved.policy.dailyMealCount).isEqualTo(1)
        assertThat(resolved.policy.monthlyCapMinor).isEqualTo(200000)
        assertThat(resolved.policy.lunchStart).isEqualTo(LocalTime.of(10, 30))
        // ★"기본값을 쓰는 중"이 화면에 드러나야 조직관리자가 설정해야 할 이유를 안다.
        MealPolicyField.entries.forEach {
            assertThat(resolved.sourceOf(it).scope).isEqualTo(PolicyScopeType.CODE_DEFAULT)
        }
    }

    @Test
    fun `★해석 결과는 엔티티가 아니라 값이다 — 상속이 붙어도 조직 정책 행을 오염시킬 수 없다`() {
        val stored = MealPolicy(orgId = orgId, perMealLimitMinor = 12000)
        every { repository.findById(orgId) } returns Optional.of(stored)

        val resolved = resolver.resolve(orgId, MemberPolicyScope.ORG_DEFAULT, today)

        // 값 타입이라 copy 로 "부서 재정의를 얹은 결과"를 만들 수 있고, 그 어떤 조작도 원본 엔티티에
        // 닿지 않는다. 만약 해석 결과가 MealPolicy 였다면 아래 한 줄이 커밋 시점에 meal_policies 행을
        // UPDATE 해서 **한 부서의 재정의가 전사 정책을 갈아엎었을** 것이다.
        val departmentOverride = resolved.policy.copy(perMealLimitMinor = 20000)

        assertThat(departmentOverride.perMealLimitMinor).isEqualTo(20000)
        assertThat(stored.perMealLimitMinor).isEqualTo(12000)
        assertThat(resolved.policy).isNotInstanceOf(MealPolicy::class.java)
    }

    @Test
    fun `재정의가 없으면 소속 축을 넘겨도 조직 기본값이 그대로 나온다(회귀 방지의 핵심)`() {
        // ★재정의를 한 번도 만들지 않은 조직 — 즉 대다수 — 에서 동작이 도입 전과 **정확히** 같아야 한다.
        //   부서·사업장이 있어도 재정의 행이 없으면 결과도 출처도 달라지지 않는다.
        every { repository.findById(orgId) } returns Optional.of(MealPolicy(orgId = orgId, perMealLimitMinor = 13000))
        val scoped = MemberPolicyScope(departmentId = UUID.randomUUID(), siteId = UUID.randomUUID())

        val resolved = resolver.resolve(orgId, scoped, today)

        assertThat(resolved.policy.perMealLimitMinor).isEqualTo(13000)
        assertThat(resolved.sourceOf(MealPolicyField.PER_MEAL_LIMIT).scope).isEqualTo(PolicyScopeType.ORG)
    }
}
