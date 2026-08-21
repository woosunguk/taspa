package com.taspa.server.enterprise

import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * 소셜(정적 3종) + 조직 OIDC(DB) 통합 ClientRegistrationRepository.
 *
 * findByRegistrationId: 소셜을 먼저 조회하고, 없으면 DB 의 enabled 조직 OIDC 커넥션을 조회한다
 * (SsoConnectionService 가 짧은 캐시로 변환) — 관리자가 추가한 조직 OIDC 커넥션이 재기동 없이 동작한다.
 *
 * iterator: 소셜 등록만 노출한다 — 로그인 페이지 소셜 버튼은 소셜 3종에 한정하고, 조직 OIDC 는
 * HRD(도메인 매칭 → /oauth2/authorization/{regId} 리다이렉트) 또는 enforced=false 보조 버튼으로 진입한다.
 */
class CompositeClientRegistrationRepository(
    private val socialRegistrations: List<ClientRegistration>,
    private val ssoConnectionService: SsoConnectionService,
) : ClientRegistrationRepository,
    Iterable<ClientRegistration> {
    private val socialById = socialRegistrations.associateBy { it.registrationId }

    override fun findByRegistrationId(registrationId: String): ClientRegistration? =
        socialById[registrationId] ?: ssoConnectionService.findOidcClientRegistration(registrationId)

    override fun iterator(): Iterator<ClientRegistration> = socialRegistrations.iterator()
}
