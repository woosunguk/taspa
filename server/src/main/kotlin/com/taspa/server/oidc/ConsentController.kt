package com.taspa.server.oidc

import com.taspa.server.config.i18n.MessageResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.util.StringUtils
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.security.Principal

/**
 * 구글식 동의 화면. spring-authorization-server 공식 데모의 AuthorizationConsentController 패턴을 따른다.
 * 이미 동의한 scope 는 제외하고, 새로 요청된 scope 만 국문 설명과 함께 보여준다.
 * 폼은 POST /oauth2/authorize 로 제출된다(SAS endpointsMatcher 는 CSRF 가 자동 비활성이라 토큰 불필요).
 */
@Controller
class ConsentController(
    private val registeredClientRepository: RegisteredClientRepository,
    private val authorizationConsentService: OAuth2AuthorizationConsentService,
    private val messages: MessageResolver,
) {
    companion object {
        /** scope → 로케일화된 설명을 담은 메시지 키. 값은 요청 로케일로 [MessageResolver] 가 해석한다. */
        val SCOPE_DESCRIPTION_KEYS =
            mapOf(
                OidcScopes.OPENID to "consent.scope.openid",
                OidcScopes.PROFILE to "consent.scope.profile",
                OidcScopes.EMAIL to "consent.scope.email",
                /*
                 * ★**동의 화면에 나올 수 있는 scope 는 전부 여기 있어야 한다.**
                 *
                 * 표에 없으면 아래 [withDescriptions] 가 scope 문자열을 그대로 보여준다. 그래서 한동안
                 * 사용자는 "org.roles 을(를) 허용하시겠습니까?" 같은 화면에서 동의 여부를 판단해야 했다 —
                 * 무엇에 동의하는지 모르는 동의는 동의가 아니고, 이 화면이 존재하는 이유 자체가 사라진다.
                 * `taspa.allowed-scopes`(application.yml)에 scope 를 추가하면 여기에도 문구를 더할 것.
                 *
                 * M2M 전용 scope(org.scim·meal.consumption.write·*.read.all 등)도 문구를 둔다.
                 * 위임 토큰에는 실리지 않는 것이 정상이지만, 잘못 등록된 클라이언트가 사용자를 이 화면으로
                 * 보냈을 때 **원문 식별자보다는 뜻을 아는 편**이 사용자의 거절 판단에 낫다.
                 */
                "org.read" to "consent.scope.orgRead",
                "org.roles" to "consent.scope.orgRoles",
                "org.scim" to "consent.scope.orgScim",
                "meal.pay" to "consent.scope.mealPay",
                "meal.redeem" to "consent.scope.mealRedeem",
                "merchant.read" to "consent.scope.merchantRead",
                "merchant.write" to "consent.scope.merchantWrite",
                "settlement.read" to "consent.scope.settlementRead",
                "settlement.write" to "consent.scope.settlementWrite",
                "meal.consumption.read" to "consent.scope.consumptionRead",
                "meal.consumption.read.all" to "consent.scope.consumptionReadAll",
                "meal.consumption.write" to "consent.scope.consumptionWrite",
                "meal.forecast.read" to "consent.scope.forecastRead",
                "meal.forecast.write" to "consent.scope.forecastWrite",
                "calendar.read" to "consent.scope.calendarRead",
                "calendar.read.all" to "consent.scope.calendarReadAll",
            )
    }

    @GetMapping("/oauth2/consent")
    fun consent(
        principal: Principal,
        model: Model,
        @RequestParam(OAuth2ParameterNames.CLIENT_ID) clientId: String,
        @RequestParam(OAuth2ParameterNames.SCOPE) scope: String,
        @RequestParam(OAuth2ParameterNames.STATE) state: String,
        @RequestParam(name = OAuth2ParameterNames.USER_CODE, required = false) userCode: String?,
    ): String {
        val scopesToApprove = mutableSetOf<String>()
        val previouslyApprovedScopes = mutableSetOf<String>()
        val registeredClient =
            registeredClientRepository.findByClientId(clientId)
                ?: return "redirect:/login"
        val currentConsent = authorizationConsentService.findById(registeredClient.id, principal.name)
        val authorizedScopes = currentConsent?.scopes ?: emptySet()

        for (requestedScope in StringUtils.delimitedListToStringArray(scope, " ")) {
            if (OidcScopes.OPENID == requestedScope) {
                continue
            }
            if (authorizedScopes.contains(requestedScope)) {
                previouslyApprovedScopes.add(requestedScope)
            } else {
                scopesToApprove.add(requestedScope)
            }
        }

        model.addAttribute("clientId", clientId)
        model.addAttribute("clientName", registeredClient.clientName)
        model.addAttribute("state", state)
        model.addAttribute("scopes", withDescriptions(scopesToApprove))
        model.addAttribute("previouslyApprovedScopes", withDescriptions(previouslyApprovedScopes))
        model.addAttribute("principalName", principal.name)
        model.addAttribute("userCode", userCode)
        // 동의 제출 대상 엔드포인트. Device Authorization Grant(user_code 존재)는 device verification
        // 엔드포인트로, 일반 authorization_code 동의는 authorization 엔드포인트로 제출한다
        // (SAS 공식 1.4.2 샘플 AuthorizationConsentController 의 requestURI 분기와 동일).
        // 두 경로 모두 SAS endpointsMatcher 안이라 CSRF 토큰이 자동 비활성(불필요)이다.
        val deviceFlow = StringUtils.hasText(userCode)
        model.addAttribute("deviceFlow", deviceFlow)
        model.addAttribute(
            "formAction",
            if (deviceFlow) "/oauth2/device_verification" else "/oauth2/authorize",
        )
        return "oauth2/consent"
    }

    private fun withDescriptions(scopes: Set<String>): List<ScopeView> =
        scopes.map { scope ->
            val key = SCOPE_DESCRIPTION_KEYS[scope]
            ScopeView(scope, if (key != null) messages.get(key) else scope)
        }

    data class ScopeView(
        val scope: String,
        val description: String,
    )
}
