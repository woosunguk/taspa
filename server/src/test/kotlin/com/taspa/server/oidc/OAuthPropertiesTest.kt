package com.taspa.server.oidc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * scope 화이트리스트 설정화(Phase 0-B) 순수 단위 테스트 — effectiveAllowedScopes 의 폴백/정규화.
 */
class OAuthPropertiesTest {
    @Test
    fun `미설정(빈 집합)이면 OIDC 표준 3개로 폴백한다`() {
        val props = OAuthProperties(allowedScopes = emptySet())
        assertThat(props.effectiveAllowedScopes())
            .containsExactlyInAnyOrder("openid", "profile", "email")
    }

    @Test
    fun `설정된 scope 를 그대로 반영하고 공백을 정리한다`() {
        val props = OAuthProperties(allowedScopes = setOf("openid", " calendar.read ", "", "meal.forecast.write"))
        assertThat(props.effectiveAllowedScopes())
            .containsExactlyInAnyOrder("openid", "calendar.read", "meal.forecast.write")
    }

    @Test
    fun `모두 공백뿐이면 폴백한다`() {
        val props = OAuthProperties(allowedScopes = setOf("", "  "))
        assertThat(props.effectiveAllowedScopes())
            .containsExactlyInAnyOrder("openid", "profile", "email")
    }
}
