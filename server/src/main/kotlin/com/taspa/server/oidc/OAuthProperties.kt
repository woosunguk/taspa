package com.taspa.server.oidc

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.oauth2.core.oidc.OidcScopes

/**
 * OAuth2 클라이언트 등록 정책(Phase 0-B). `allowedScopes` 는 관리 콘솔에서 클라이언트에 부여 가능한
 * scope 화이트리스트다 — 기존 하드코딩(openid/profile/email)을 설정으로 이관해 결제·예측·M2M 클라이언트가
 * 플랫폼 scope 로 등록될 수 있게 한다.
 *
 * 안전 폴백: 프로퍼티가 비어 있으면(미설정) 기존 3개(openid/profile/email)로 폴백한다 — 설정 실수로
 * 화이트리스트가 통째로 비어 어떤 scope 도 등록 불가가 되는 상황을 막고, 기존 동작을 보존한다.
 */
@ConfigurationProperties(prefix = "taspa.oauth")
data class OAuthProperties(
    val allowedScopes: Set<String> = emptySet(),
) {
    /** 유효 화이트리스트 — 미설정 시 OIDC 표준 3개로 폴백(안전 기본값). */
    fun effectiveAllowedScopes(): Set<String> =
        allowedScopes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { DEFAULT_ALLOWED_SCOPES }

    companion object {
        val DEFAULT_ALLOWED_SCOPES: Set<String> =
            setOf(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL)
    }
}
