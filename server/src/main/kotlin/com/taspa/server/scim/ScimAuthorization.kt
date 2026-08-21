package com.taspa.server.scim

import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.util.UUID

/** SCIM 응답 미디어 타입(RFC 7644 §3.1). 요청은 application/json 도 수용한다(*+json 컨버터 기본 지원). */
object ScimMediaType {
    const val SCIM_JSON = "application/scim+json"
}

/** authorize() 결과 — 테넌트 앵커 org 와 감사 귀속용 클라이언트 식별자. */
data class ScimClientContext(
    val orgId: UUID,
    val clientId: String?,
)

/**
 * SCIM 인가(조직 3c) — **테넌트 앵커는 M2M 토큰의 org_id 클레임**(URL 아님). 허용 조건 전부 충족 필수:
 *  1) JwtAuthenticationToken(베어러) — 세션 인증은 체인(STATELESS)에서 이미 401 이지만 여기서도 fail-closed.
 *  2) `org.scim` scope.
 *  3) 사용자(위임) 토큰 거부 — subject 가 실제 users.id 로 해석되면 authorization_code 사용자 토큰이다
 *     (client_credentials 는 sub=client_id — ConsumptionEventController.isUserToken 과 동일 판별).
 *  4) org_id 클레임(UUID) 존재 — 미결속 토큰은 fail-closed 403.
 *  5) org 존재 + ACTIVE — 정지(SUSPENDED)된 조직의 SCIM 클라이언트는 403(정지 제어 실효성: 정지 중에도
 *     전역 users 행 신규 생성·멤버십 변경이 계속되는 것을 차단. 세션 경로 isActiveMember/isOrgAdmin 과 정합).
 * 통과 시 그 org 가 모든 효과의 스코프가 된다(타 org 데이터는 도달 불가).
 */
@Component
class ScimAuthorization(
    private val userRepository: UserRepository,
    private val organizationRepository: OrganizationRepository,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    /**
     * 모든 SCIM 엔드포인트의 인가 수렴점. **판정자는 정책 엔진 하나다** — 여기서 해석하는 주체 종류·org_id
     * 클레임·org 상태는 판정이 아니라 엔진에 넘길 사실이며, 거부 시의 SCIM 오류(상태·문구)는
     * `denialMessage` 가 고른다(RFC 7644 형식 불변).
     */
    fun authorize(authentication: Authentication?): ScimClientContext {
        val jwtAuth = authentication as? JwtAuthenticationToken
        val orgId = jwtAuth?.let { claimOrgId(it.token) }
        // SCIM 은 M2M 전용이지만 주체 종류는 컨텍스트에 정직하게 채운다(사용자 위임 토큰 = DELEGATED).
        val kind =
            when {
                jwtAuth == null -> IamPrincipalKind.SESSION
                isUserToken(jwtAuth.token) -> IamPrincipalKind.DELEGATED
                else -> IamPrincipalKind.M2M
            }
        val resource =
            orgId?.let { Trn.scimDirectory(it) }
                ?: Trn.build(Trn.SERVICE_SCIM, null as String?, "directory")
        val request =
            AuthorizationRequest(
                IamActions.SCIM_MANAGE_DIRECTORY,
                resource,
                iamContextFactory.build(kind, orgId),
            )
        val allowed =
            iamShadowService.authorize(request, "ScimAuthorization.authorize") {
                iamShadowService.decideM2m(
                    scopes = scopesOf(jwtAuth),
                    boundOrgs = setOfNotNull(orgId),
                    merchantId = null,
                    request = request,
                    // ★앵커는 org 가 **존재하고 ACTIVE** 일 때만 넘긴다 — 앵커를 무조건 넘기면 엔진이 정지 조직
                    // 프로비저닝을 허용해 over-grant 가 된다. 세션 경로의 isOrgAdmin/isActiveMember 가 org 상태를
                    // 접는 것과 동일한 계약을 M2M 앵커에도 적용한다(정지 제어의 실효성).
                    scimOrg = activeOrgAnchor(orgId),
                )
            }
        // 거부 사유 메시지는 **거부된 경우에만** 지연 계산한다(허용 경로에 불필요한 질의가 없다).
        if (!allowed) throw denialMessage(jwtAuth, orgId)
        // 허용 = 엔진이 SCIM 문장을 매치시켰다는 뜻이고, 그 문장은 활성 org 앵커로만 만들어지므로 orgId 는
        // 반드시 존재한다. 그래도 앵커 없이 컨텍스트를 조립하지 않는다(fail-closed — 대상 org 특정 불가).
        val anchor = orgId ?: throw denialMessage(jwtAuth, null)
        return ScimClientContext(orgId = anchor, clientId = jwtAuth?.token?.let { clientIdOf(it) })
    }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택용.** 엔진이 이미 거부를 결정한 뒤, 그 거부를 RFC 7644 오류의
     * 어떤 상태/문구로 표현할지만 고른다 — 이 함수가 틀려도 권한이 열리지 않는다(허용 경로에서는 호출되지
     * 않는다). 검사 순서와 상태·문구는 원본 그대로 유지한다(401 1종 + 403 4종).
     */
    private fun denialMessage(
        jwtAuth: JwtAuthenticationToken?,
        orgId: UUID?,
    ): ScimException {
        if (jwtAuth == null) {
            return ScimException(HttpStatus.UNAUTHORIZED, null, "Bearer token is required")
        }
        val authorities = jwtAuth.authorities.mapTo(HashSet()) { it.authority }
        if ("SCOPE_$SCIM_SCOPE" !in authorities) {
            return ScimException(HttpStatus.FORBIDDEN, null, "org.scim scope is required")
        }
        if (isUserToken(jwtAuth.token)) {
            return ScimException(HttpStatus.FORBIDDEN, null, "SCIM provisioning requires an M2M client token")
        }
        if (orgId == null) {
            return ScimException(HttpStatus.FORBIDDEN, null, "Token is not bound to an organization")
        }
        val org =
            organizationRepository.findById(orgId).orElse(null)
                ?: return ScimException(HttpStatus.FORBIDDEN, null, "Token is not bound to a valid organization")
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            return ScimException(HttpStatus.FORBIDDEN, null, "Organization is suspended")
        }
        return ScimException(HttpStatus.FORBIDDEN, null, "Request is not authorized")
    }

    /** 토큰의 테넌트 앵커 클레임(org_id) — 파싱 실패/부재는 null(레거시가 403 으로 접는다). */
    private fun claimOrgId(jwt: Jwt): UUID? = (jwt.claims["org_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun scopesOf(jwtAuth: JwtAuthenticationToken?): Set<String> =
        jwtAuth
            ?.authorities
            .orEmpty()
            .map { it.authority }
            .filter { it.startsWith(SCOPE_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(SCOPE_PREFIX) }

    /** org 가 존재하고 ACTIVE 일 때만 앵커로 인정한다(레거시 authorizeLegacy 의 org 상태 검사와 등가). */
    private fun activeOrgAnchor(orgId: UUID?): UUID? {
        if (orgId == null) return null
        val org = organizationRepository.findById(orgId).orElse(null) ?: return null
        return orgId.takeIf { org.statusEnum() == OrgStatus.ACTIVE }
    }

    /** subject 가 실제 사용자(users.id)로 해석되면 사용자 위임 토큰 — SCIM 에서는 거부한다. */
    private fun isUserToken(jwt: Jwt): Boolean =
        jwt.subject
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { userRepository.findById(it).isPresent }
            ?: false

    /** 행위자(클라이언트) 식별 — SAS 는 sub=client_id. client_id 클레임 우선, 없으면 sub. */
    private fun clientIdOf(jwt: Jwt): String? = jwt.getClaimAsString("client_id") ?: jwt.subject

    companion object {
        const val SCIM_SCOPE = "org.scim"

        /** SAS 가 scope 를 노출하는 권한 접두사 — 엔진 판정용 scope 집합 추출에 쓴다. */
        const val SCOPE_PREFIX = "SCOPE_"
    }
}
