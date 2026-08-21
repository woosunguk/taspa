package com.taspa.server.meal

import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalTime

/**
 * 끼니창 시각의 **DB 왕복 정확도**.
 *
 * ★이 테스트가 존재하는 이유는 실제로 당한 일 때문이다. 테스트 픽스처가 "하루 끝"을
 * `23:59:59.999999`(마이크로초 최대)로 깔았는데, 그 값이 왕복하면서 **`00:00` 으로 넘어갔다**.
 * 그러면 저녁 창이 `[16:00, 00:00)` 이 되고 `resolveWindow` 의 반개구간 판정이 **항상 거짓**이라
 * 16시 이후의 모든 결제가 `MEAL_WINDOW_CLOSED` 로 거절된다.
 *
 * 더 나쁜 건 발현 조건이다: 테스트는 **UTC 16시~24시(KST 새벽 1시~9시)에만** 깨졌다. 낮에 돌리면
 * 초록불이라 아무도 눈치채지 못하고, 새벽에 CI 를 돌린 사람만 원인 모를 9건 실패를 본다.
 * 시각 의존 픽스처는 이렇게 조용히 썩는다 — 그래서 "안전한 값"을 추측하지 않고 **여기서 측정**한다.
 *
 * (프로덕션에는 이 값이 들어오지 않는다: 정책 편집 API 는 `start >= end` 를 거절하고 화면은 분 단위
 * `HH:mm` 만 보낸다. 순수하게 픽스처의 문제이지만, 그 픽스처가 결제 경로 전체의 회귀를 지탱한다.)
 */
class MealPolicyTimeRoundTripTest : IntegrationTestBase() {
    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    @Test
    fun `마이크로초 최대값은 왕복하면서 자정으로 넘어간다(픽스처가 쓰면 안 되는 값)`() {
        val stored = save(LocalTime.of(23, 59, 59, 999_999_000))

        // 넘어갔다는 사실 자체를 못박는다 — "지금은 괜찮겠지"로 다시 쓰는 것을 막는다.
        assertThat(stored).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun `초 단위 값은 정확히 왕복한다`() {
        assertThat(save(LocalTime.of(23, 59, 59))).isEqualTo(LocalTime.of(23, 59, 59))
        assertThat(save(LocalTime.of(16, 0))).isEqualTo(LocalTime.of(16, 0))
        assertThat(save(LocalTime.MIDNIGHT)).isEqualTo(LocalTime.MIDNIGHT)
    }

    /**
     * 픽스처가 "하루 끝"으로 쓸 값 — 밀리초 정밀도까지는 살아남는다.
     *
     * `23:59:59` 로 두면 `[23:59:59, 24:00)` 1초 사각이 생겨 그 순간에 도는 테스트가 깨진다.
     * 밀리초 최대값이 그 사각을 1000분의 1로 줄이면서 자정 넘김도 피하는 유일한 지점이다.
     */
    @Test
    fun `밀리초 최대값은 자정으로 넘어가지 않는다(픽스처가 쓸 값)`() {
        assertThat(save(END_OF_DAY)).isEqualTo(END_OF_DAY)
        assertThat(END_OF_DAY).isNotEqualTo(LocalTime.MIDNIGHT)
    }

    private fun save(dinnerEnd: LocalTime): LocalTime {
        val orgId =
            organizationRepository
                .save(
                    Organization(slug = "tz-probe-${System.nanoTime()}", name = "왕복 확인"),
                ).id!!
        policyRepository.saveAndFlush(
            MealPolicy(orgId = orgId, dinnerStart = LocalTime.of(16, 0), dinnerEnd = dinnerEnd),
        )
        // ★1차 캐시가 아니라 **DB 에서** 다시 읽어야 왕복을 본다. findById 는 영속성 컨텍스트에 남은
        // 인스턴스를 그대로 돌려줘 저장한 값이 그대로 나오고, 그러면 이 테스트가 아무것도 증명하지 않는다.
        return jdbcTemplate.queryForObject(
            "SELECT dinner_end FROM meal_policies WHERE org_id = ?",
            LocalTime::class.java,
            orgId,
        )!!
    }

    companion object {
        /** 테스트 픽스처의 "하루 끝". 여기서 측정한 값이라 추측이 아니다. */
        val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59, 999_000_000)
    }
}
