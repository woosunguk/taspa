package com.taspa.server.federation

import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.enterprise.SsoConnectionService
import com.taspa.server.login.LoginFlowSupport
import com.taspa.server.login.PendingAuthStage
import com.taspa.server.org.OrgAutoJoinService
import com.taspa.server.org.OrganizationService
import com.taspa.server.stepup.StepUp
import com.taspa.server.verification.EmailVerificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

/**
 * oauth2Login 성공 핸들러 — 계정 연결 정책(A-2, Keycloak/Auth0 모델) 분기.
 *
 * 이 시점에는 필터가 이미 OAuth2AuthenticationToken 을 세션에 저장한 뒤다. 어떤 분기든
 * 그 토큰을 세션에 남기지 않는다: 게이트 분기는 startPending(내부에서 clearSecurityContext),
 * 완전 인증 분기는 completeAuthentication(로컬 UserDetails 토큰으로 덮어씀)으로 수렴한다.
 *
 * 분기:
 *  0. 계정 연결 의도(SocialLinkIntent — 로그인된 세션에서 시작) → 연결만 수행하고 /account 복귀
 *  1. (provider, providerId) 연결 존재 → 그 사용자로 게이트 판정 후 로그인
 *  2. 부재 + email 있음 + 같은 email 로컬 계정 존재:
 *     - 공급자·로컬 이메일 둘 다 검증됨 → 자동 연결 + 게이트 판정
 *       (근거: 미검증 로컬 계정 자동 연결은 계정 선점 탈취로 이어진다 — better-auth 사례)
 *     - 아니면(네이버 전부 포함) → SOCIAL_LINK 게이트: 코드 발송 후 /login/link-confirm
 *  3. 부재 + email 있음 + 로컬 계정 없음 → 신규 계정 생성(password_hash NULL)
 *     - 공급자 검증 이메일 → 연결 + 게이트 판정
 *     - 미검증 → EMAIL_VERIFICATION 게이트. 연결은 소유 증명(코드 확인) 후에만 수행한다(계정 선점 방지)
 *  4. 부재 + email 없음(카카오 미동의) → SOCIAL_EMAIL 게이트: /login/social-email
 */
@Component
class FederatedLoginSuccessHandler(
    private val socialAttributesExtractor: SocialAttributesExtractor,
    private val federationService: FederationService,
    private val userRepository: UserRepository,
    private val loginFlowSupport: LoginFlowSupport,
    private val emailVerificationService: EmailVerificationService,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
    private val ssoConnectionService: SsoConnectionService,
    private val organizationService: OrganizationService,
    private val orgAutoJoinService: OrgAutoJoinService,
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(FederatedLoginSuccessHandler::class.java)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        // 성공 핸들러는 필터 체인에서 실행돼 @RestControllerAdvice 가 예외를 잡지 못한다 —
        // 어떤 예외(UNIQUE 경합 포함)도 원시 500 대신 일반 소셜 실패로 수렴시킨다.
        try {
            handle(request, response, authentication)
        } catch (ex: Exception) {
            log.error("social login success handling failed", ex)
            if (!response.isCommitted) {
                failSocial(request, response)
            }
        }
    }

    private fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val token = authentication as OAuth2AuthenticationToken
        val extracted = socialAttributesExtractor.extract(token.authorizedClientRegistrationId, token.principal)

        // 조직 OIDC(Stage E) 도메인 일치 강제(정책 5, 보안 핵심): 커넥션이 조직 OIDC 면 공급자 이메일의
        // 도메인이 커넥션의 verified 도메인과 일치할 때만 진행한다 — 조직 IdP 가 타 도메인 이메일을 주장해
        // 남의 계정을 탈취하는 것을 차단. 소셜(google/kakao/naver)은 조회가 null 이라 영향 없음.
        val orgConnection = ssoConnectionService.findByRegistrationId(token.authorizedClientRegistrationId)
        if (orgConnection != null && !ssoConnectionService.isEmailDomainVerified(orgConnection, extracted.email)) {
            auditEventService.record(
                "SSO_DOMAIN_REJECTED",
                null,
                mapOf("provider" to extracted.provider, "email" to extracted.email),
            )
            failSocial(request, response)
            return
        }

        // 조직 OIDC: 위 도메인 일치 강제가 이미 이메일 소유를 증명했으므로(정책 5) SAML 경로와 동일하게
        // 이메일을 검증됨으로 승격한다 — email_verified 클레임을 생략/false 로 주는 기업 IdP(Azure AD v2 등)
        // 사용자도 불필요한 이메일 인증/링크 게이트 없이 무마찰 로그인한다(소셜은 orgConnection=null 이라 불변).
        val attributes = if (orgConnection != null) extracted.copy(emailVerifiedByProvider = true) else extracted

        // 0) 로그인된 세션에서 시작한 "계정 연결" 플로우.
        val intent = consumeLinkIntent(request)
        if (intent != null) {
            handleAccountLink(request, response, intent, attributes)
            return
        }

        // 0.5) 로그인된 세션에서 시작한 step-up "소셜 재인증" 플로우(/reauth).
        val reauthIntent = consumeReauthIntent(request)
        if (reauthIntent != null) {
            handleSocialReauth(request, response, reauthIntent, attributes)
            return
        }

        // 1) 이미 연결된 소셜/조직 신원 → 그 사용자로 로그인(게이트 판정). 조직 OIDC 는 커넥션 id 로
        //    스코프해 조회한다 — registration_id 재사용으로 남은 옛 신원(connection_id=NULL)이 새 조직
        //    커넥션에 상속돼 계정 탈취로 이어지는 것을 차단한다(정책 5 보강). 소셜은 기존 전역 조회 유지.
        val identity =
            if (orgConnection != null) {
                federationService.findIdentityForConnection(
                    attributes.provider,
                    attributes.providerUserId,
                    orgConnection.id!!,
                )
            } else {
                federationService.findIdentity(attributes.provider, attributes.providerUserId)
            }
        if (identity != null) {
            val user = userRepository.findById(identity.userId).orElse(null)
            if (user == null) {
                failSocial(request, response)
                return
            }
            gateOrComplete(request, response, user, attributes, orgConnection?.orgId)
            return
        }

        // User.MAX_EMAIL_LENGTH 초과 공급자 이메일은 미제공과 동일하게 처리한다 — 그대로 계정을
        // 만들면 세션 PRINCIPAL_NAME(VARCHAR 100) 초과로 로그인이 영구 실패한다. SOCIAL_EMAIL
        // 게이트에서 사용자가 상한 이내 이메일을 직접 입력·확인하게 한다.
        val email = attributes.email?.takeIf { it.length <= User.MAX_EMAIL_LENGTH }
        if (email == null) {
            // 4) 이메일 미제공(카카오 동의 거부) → 이메일 입력·확인 게이트. 아직 로컬 계정이 없다(userId=null).
            loginFlowSupport.startPending(request, response, null, PendingAuthStage.SOCIAL_EMAIL)
            savePendingLink(request, attributes)
            auditEventService.record("LOGIN_GATE", null, mapOf("stage" to "SOCIAL_EMAIL", "provider" to attributes.provider))
            redirect(request, response, "/login/social-email")
            return
        }

        val localUser = userRepository.findByEmail(email)
        if (localUser != null) {
            // 2) 같은 이메일의 로컬 계정 존재. 비활성 계정은 링크 확인 코드 발송 전에 차단한다 —
            // SOCIAL_LINK 게이트가 status 검사 없이 완결되면 정지(SUSPENDED) 제어가 우회된다.
            if (localUser.status != UserStatus.ACTIVE.name) {
                auditEventService.record(
                    "LOGIN_REJECTED",
                    localUser.id,
                    mapOf("provider" to attributes.provider, "reason" to localUser.status),
                )
                failSocial(request, response)
                return
            }
            // 조직 OIDC(orgConnection != null): 위 도메인 강제가 이메일 소유를 증명했으므로 SAML 경로와
            // 동일하게 로컬 계정에 자동 연결한다(로컬 emailVerified 여부와 무관 — 미검증 로컬 이메일은 아래에서 승격).
            if ((attributes.emailVerifiedByProvider && localUser.emailVerified) || orgConnection != null) {
                if (!federationService.linkOrConverge(localUser.id!!, attributes)) {
                    // UNIQUE 경합에서 같은 신원이 다른 계정에 먼저 연결됨 → 일반 실패.
                    failSocial(request, response)
                    return
                }
                // 조직 OIDC + 도메인 강제로 소유가 증명됐으므로 미검증 로컬 이메일을 검증 처리한다(SAML 미러링).
                if (orgConnection != null && !localUser.emailVerified) {
                    localUser.emailVerified = true
                    userRepository.save(localUser)
                    // 이메일 인증 성공 전이 — 도메인 자동 조직 가입 판정(실패 비전파, 멱등).
                    orgAutoJoinService.evaluate(localUser)
                }
                gateOrComplete(request, response, localUser, attributes, orgConnection?.orgId)
            } else {
                // 자동 연결 금지 → 기존 계정 확인 플로우(이메일 코드).
                emailVerificationService.issue(localUser.id!!)
                loginFlowSupport.startPending(
                    request,
                    response,
                    localUser.id,
                    PendingAuthStage.SOCIAL_LINK,
                    methodOf(attributes),
                )
                savePendingLink(request, attributes)
                auditEventService.record(
                    "LOGIN_GATE",
                    localUser.id,
                    mapOf("stage" to "SOCIAL_LINK", "provider" to attributes.provider),
                )
                redirect(request, response, "/login/link-confirm")
            }
            return
        }

        // 3) 신규 계정 생성. users.email UNIQUE 경합(같은 이메일 동시 첫 로그인/가입)은 일반 실패로 수렴.
        val created =
            try {
                federationService.createSocialUser(email, attributes)
            } catch (ex: DataIntegrityViolationException) {
                failSocial(request, response)
                return
            }
        if (!created.emailVerified) {
            // 미검증(네이버 전부 포함): 이메일 소유가 증명되지 않았으므로 소셜 신원을 아직 연결하지 않는다.
            // 소유 증명 전에 (provider, providerUserId)→계정 연결을 영속화하면, 공격자가 남의 이메일로
            // 미검증 계정을 선점(스쿼팅)하고 실소유자가 나중에 그 계정에 입장했을 때 공격자의 소셜
            // 로그인이 살아 있는 계정 탈취가 된다. 연결은 EMAIL_VERIFICATION 게이트 통과 후
            // (LoginFlowController.submitVerifyEmail → linkAndFinish) 수행한다.
            emailVerificationService.issue(created.id!!)
            loginFlowSupport.startPending(
                request,
                response,
                created.id,
                PendingAuthStage.EMAIL_VERIFICATION,
                methodOf(attributes),
            )
            savePendingLink(request, attributes)
            auditEventService.record(
                "LOGIN_GATE",
                created.id,
                mapOf("stage" to "EMAIL_VERIFICATION", "provider" to attributes.provider),
            )
            redirect(request, response, "/login/verify-email")
            return
        }
        // 공급자 검증 이메일 → 즉시 연결 후 게이트 판정.
        if (!federationService.linkOrConverge(created.id!!, attributes)) {
            failSocial(request, response)
            return
        }
        // 공급자 검증 이메일로 생성된 계정(emailVerified=true 전이) — 도메인 자동 조직 가입 판정.
        orgAutoJoinService.evaluate(created)
        gateOrComplete(request, response, created, attributes, orgConnection?.orgId)
    }

    /**
     * 게이트 판정 후 로그인 완결. 게이트 판정(신뢰 기기 확인 지점 포함)은 LoginFlowSupport.requiredGate 공용 함수.
     *
     * [jitOrgId] 가 non-null 이면(=조직 IdP 커넥션에 org 가 연결된 경우) 활성 계정 확인 직후 JIT 멤버십을
     * upsert 한다(Phase 0-A). 도메인 일치 강제(정책 5)가 이미 조직 소속을 증명했으므로 이 시점에서 안전하다.
     * 게이트(MFA 등) 유무와 무관하게 멤버십을 보장한다. JIT 실패는 로그인을 깨지 않는다(로그만).
     */
    private fun gateOrComplete(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: User,
        attributes: SocialAttributes,
        jitOrgId: java.util.UUID? = null,
    ) {
        // 소셜 로그인도 로컬 계정 상태를 따른다: 비활성 계정은 로그인 불가(일반 실패로 응답).
        if (user.status != UserStatus.ACTIVE.name) {
            auditEventService.record(
                "LOGIN_REJECTED",
                user.id,
                mapOf("provider" to attributes.provider, "reason" to user.status),
            )
            failSocial(request, response)
            return
        }

        // JIT 멤버십(조직 IdP + org_id 연결 시에만). org_id 가 없으면 아무 것도 하지 않는다(잘못된 자동가입 금지).
        if (jitOrgId != null && user.id != null) {
            try {
                if (organizationService.ensureJitMembership(jitOrgId, user.id!!)) {
                    auditEventService.record(
                        "ORG_JIT_MEMBERSHIP_CREATED",
                        user.id,
                        mapOf("orgId" to jitOrgId.toString(), "provider" to attributes.provider),
                    )
                }
            } catch (ex: Exception) {
                // JIT 은 부가 기능 — 실패해도 로그인은 진행한다(경합·일시 오류가 인증을 막지 않게).
                log.warn("JIT membership upsert failed for org={} user={}", jitOrgId, user.id, ex)
            }
        }

        val gate = loginFlowSupport.requiredGate(request, response, user)
        if (gate != null) {
            loginFlowSupport.startPending(request, response, user.id!!, gate, methodOf(attributes))
            auditEventService.record(
                "LOGIN_GATE",
                user.id,
                mapOf("stage" to gate.name, "provider" to attributes.provider),
            )
            val target =
                when (gate) {
                    PendingAuthStage.EMAIL_VERIFICATION -> "/login/verify-email"
                    else -> "/login/mfa"
                }
            redirect(request, response, target)
            return
        }

        accountLockoutService.recordSuccessfulLogin(user)
        auditEventService.record(
            "LOGIN_SUCCESS",
            user.id,
            mapOf("email" to user.email, "method" to "social:${attributes.provider}"),
        )
        clearPendingLink(request)
        val target = loginFlowSupport.completeAuthentication(request, response, user.id!!, methodOf(attributes))
        response.sendRedirect(target.removePrefix("redirect:"))
    }

    /** login_events / pending 기록용 수단 라벨 — audit 의 "social:{provider}" 와 동일 형식. */
    private fun methodOf(attributes: SocialAttributes): String = "social:${attributes.provider}"

    /** 로그인된 세션의 "연결 추가": 연결만 수행하고 원래 사용자 principal 로 복귀한다. 게이트 없음. */
    private fun handleAccountLink(
        request: HttpServletRequest,
        response: HttpServletResponse,
        intent: SocialLinkIntent,
        attributes: SocialAttributes,
    ) {
        val user = userRepository.findById(intent.userId).orElse(null)
        if (user == null || intent.provider != attributes.provider) {
            failSocial(request, response)
            return
        }
        // 필터가 세워둔 OAuth2AuthenticationToken 을 원래 로컬 principal 로 되돌린다.
        loginFlowSupport.establishSecurityContext(request, response, user.id!!)

        // linkOrConverge 가 사전 존재(멱등)·UNIQUE 경합을 모두 수렴한다:
        // 이 계정에 연결돼 있으면 성공, 다른 계정에 선점돼 있으면 inuse.
        if (federationService.linkOrConverge(user.id, attributes)) {
            redirect(request, response, "/account?linked=1")
        } else {
            redirect(request, response, "/account?linkError=inuse")
        }
    }

    /**
     * 로그인된 세션의 step-up "소셜 재인증"(/reauth → GET /reauth/social/{provider}):
     * 이 사용자에게 이미 연결된 소셜 신원으로 본인임을 확인하면 auth_time 만 갱신하고 복귀한다.
     * 새 로그인이 아니므로 login_events 기록/새 로그인 알림/세션 ID 교체는 하지 않는다.
     */
    private fun handleSocialReauth(
        request: HttpServletRequest,
        response: HttpServletResponse,
        intent: SocialReauthIntent,
        attributes: SocialAttributes,
    ) {
        val user = userRepository.findById(intent.userId).orElse(null)
        if (user == null || user.status != UserStatus.ACTIVE.name) {
            failSocial(request, response)
            return
        }
        val identity = federationService.findIdentity(attributes.provider, attributes.providerUserId)
        val verified = intent.provider == attributes.provider && identity?.userId == user.id
        // 어느 쪽이든 원래 로컬 principal 을 복구한다(필터가 OAuth2 토큰으로 교체해 둔 상태).
        loginFlowSupport.establishSecurityContext(request, response, user.id!!)
        if (!verified) {
            // 다른 소셜 계정으로 확인 시도 → 재인증 실패로 안내(세션은 원래 사용자로 유지).
            redirect(request, response, "/reauth?error=social")
            return
        }
        StepUp.markAuthTime(request, user.id)
        request.getSession(false)?.removeAttribute(StepUp.CONTINUE_SESSION_KEY)
        auditEventService.record("REAUTH_SUCCESS", user.id, mapOf("method" to methodOf(attributes)))
        redirect(request, response, intent.continuePath)
    }

    private fun consumeLinkIntent(request: HttpServletRequest): SocialLinkIntent? {
        val session = request.getSession(false) ?: return null
        val intent = session.getAttribute(SocialLinkIntent.SESSION_KEY) as? SocialLinkIntent ?: return null
        session.removeAttribute(SocialLinkIntent.SESSION_KEY)
        return if (intent.isExpired()) null else intent
    }

    private fun consumeReauthIntent(request: HttpServletRequest): SocialReauthIntent? {
        val session = request.getSession(false) ?: return null
        val intent = session.getAttribute(SocialReauthIntent.SESSION_KEY) as? SocialReauthIntent ?: return null
        session.removeAttribute(SocialReauthIntent.SESSION_KEY)
        return if (intent.isExpired()) null else intent
    }

    private fun savePendingLink(
        request: HttpServletRequest,
        attributes: SocialAttributes,
    ) {
        request.getSession(true).setAttribute(
            PendingSocialLink.SESSION_KEY,
            PendingSocialLink(
                provider = attributes.provider,
                providerUserId = attributes.providerUserId,
                email = attributes.email,
                displayName = attributes.displayName,
            ),
        )
    }

    private fun clearPendingLink(request: HttpServletRequest) {
        request.getSession(false)?.removeAttribute(PendingSocialLink.SESSION_KEY)
    }

    /** 실패 수렴점: 세션에 남은 OAuth2 토큰을 걷어내고 일반 실패 메시지로 보낸다(계정 상태 비노출). */
    private fun failSocial(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        loginFlowSupport.clearSecurityContext(request, response)
        clearPendingLink(request)
        redirect(request, response, "/login?error=social")
    }

    private fun redirect(
        request: HttpServletRequest,
        response: HttpServletResponse,
        target: String,
    ) {
        response.sendRedirect(request.contextPath + target)
    }
}
