package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.DepartmentDelegationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.DepartmentDelegationAnchor
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.ResourceScope
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * org 자율 콘솔(`/api/orgs` 이하) 세션 표면의 **단일 인가 진입점**. 8개 컨트롤러가 복붙하던 동일 로직을
 * 한 곳으로 모은 것으로, 인가 판정은 전적으로 정책 엔진(`IamAuthorizationService.authorize`)이 내린다 —
 * 이 클래스에는 "누가 통과하는가"를 결정하는 규칙이 남아 있지 않다.
 *
 * 여기 남는 것은 두 가지뿐이다:
 *  1. **베어러 하드 게이트** — 위임 access_token(JwtAuthenticationToken)은 엔진에 도달하기 전에 거부한다.
 *     under-consented 토큰 재사용에 의한 confused-deputy 를 표면 자체에서 닫는 fail-closed 선차단이라
 *     엔진 정책과 무관하게 유지한다. 거부 메시지는 표면마다 다르므로 [OrgConsoleSurface] 로 주입한다.
 *  2. **엔진 입력 사실 수집** — 주체 해석(email → User)과 org 관리자 여부. 이것들은 판정이 아니라
 *     엔진에 넘길 사실이다. 플랫폼 ADMIN 이면 `isOrgAdmin` 질의를 건너뛰는 단락 평가는 그대로 유지한다.
 *
 * 주체를 구성할 수 없으면(계정 미해석·id 없음) 판정 없이 통과시키지 않고 거부한다 — 인가를 우회하는
 * 경로를 하나도 남기지 않기 위해서다.
 */
@Component
class OrgSessionAuthorizer(
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
    private val delegationRepository: DepartmentDelegationRepository,
) {
    /**
     * @param surface 표면별 거부 메시지 묶음(베어러 거부·권한 부족). **인가 판정이 아니라 거부 사유 메시지
     *   선택용** — 이 값이 무엇이든 통과 여부는 달라지지 않는다.
     * @return 통과한 actor. 호출부는 audit 행위자로 쓴다.
     */
    fun authorize(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        resource: String,
        label: String,
        surface: OrgConsoleSurface,
        /**
         * 대상 자원의 부서 위치. **부서 축이 있는 표면만** 채운다 — 채우지 않으면 위임 정책의 양성
         * 조건이 미적용돼 위임자는 거부된다(닫히는 쪽으로 실패). 값은 반드시 대상을 DB 에서 조회해
         * [com.taspa.server.org.DepartmentPathService] 로 만들 것(호출자가 지어낸 값은 엔진이 무시한다).
         */
        scope: ResourceScope = ResourceScope.NONE,
    ): User {
        if (authentication is JwtAuthenticationToken) {
            throw AuthException(ErrorCode.FORBIDDEN, surface.bearerDenial)
        }
        val user = userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)
        val userId = user.id ?: throw AuthException(ErrorCode.FORBIDDEN)
        val platformAdmin = user.role == UserRole.ADMIN.name
        // 엔진 입력 수집(판정 아님). 플랫폼 ADMIN 이면 isOrgAdmin 질의를 하지 않는다(질의 수 불변).
        val orgAdmin = !platformAdmin && organizationService.isOrgAdmin(orgId, userId)
        val request =
            AuthorizationRequest(
                action,
                resource,
                iamContextFactory.build(IamPrincipalKind.SESSION, orgId),
                scope,
            )
        // 부서 위임 조회는 ORG_ADMIN·플랫폼 관리자가 아닐 때만 한다(그들은 이미 통과하므로 질의가 낭비다).
        val delegation =
            if (!platformAdmin && !orgAdmin) {
                delegationRepository
                    .findByOrgIdAndUserId(orgId, userId)
                    ?.let { DepartmentDelegationAnchor(orgId, it.departmentId) }
            } else {
                null
            }
        // memberOf 에는 ORG_ADMIN 일 때만 org 를 넘긴다: ORG_ADMIN 은 정의상 활성 멤버이고(isOrgAdmin 이
        // org SUSPENDED·멤버십 비활성을 이미 접는다), 이 표면은 단순 멤버십으로 통과시키지 않으므로
        // isActiveMember 추가 질의를 유발할 이유가 없다.
        val allowed =
            iamShadowService.authorize(request, label) {
                iamShadowService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = if (orgAdmin) orgId else null,
                    // 위임자는 ORG_ADMIN 이 아니지만 그 org 의 활성 멤버다 — 멤버 표면(식권 등)이 닫히면 안 된다.
                    memberOf = if (orgAdmin || delegation != null) orgId else null,
                    request = request,
                    departmentDelegation = delegation,
                )
            }
        if (!allowed) throw denial(surface)
        return user
    }

    /**
     * 같은 판정을 **던지지 않고** 돌려준다 — 한 요청 안에서 **추가 능력**을 물어봐야 할 때 쓴다.
     *
     * ★첫 용례가 초대의 ORG_ADMIN 승격이다. 초대는 수락 시 그대로 멤버십 역할이 되므로
     * `role=ORG_ADMIN` 초대는 사실상 역할 변경인데, 인가는 `org:CreateInvitation` 하나만 보고 있었다.
     * "초대는 되지만 승격은 안 되는" 역할을 표현하려면 두 능력을 **한 요청에서 따로** 물어야 한다.
     *
     * ★거부를 예외가 아니라 값으로 돌려주는 이유: 대량 초대는 행별 부분 성공 모델이라, 승격 행 하나가
     * 전체 요청을 깨뜨리면 나머지 정상 행까지 잃는다.
     */
    fun permits(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        resource: String,
        label: String,
        surface: OrgConsoleSurface,
        scope: ResourceScope = ResourceScope.NONE,
    ): Boolean = runCatching { authorize(authentication, orgId, action, resource, label, surface, scope) }.isSuccess

    /**
     * 인증 주체의 userId — **인가 판정이 아니라 조회용**이다(스코프 조립 등). 해석할 수 없으면 null 이며,
     * 그 경우 호출부는 스코프 없이 진행해 엔진이 거부하게 둔다(여기서 던지면 거부 사유가 뒤바뀐다).
     */
    fun resolveUserId(authentication: Authentication): UUID? {
        if (authentication is JwtAuthenticationToken) return null
        return userRepository.findByEmail(authentication.name)?.id
    }

    /**
     * 거부 사유 메시지 선택 — **인가 판정이 아니다.** 판정은 이미 엔진이 끝냈고, 이 함수는 사용자에게
     * 보여줄 문구만 고른다. 여기가 틀려도 권한이 열리지 않는다(문구만 달라진다).
     */
    private fun denial(surface: OrgConsoleSurface): AuthException =
        surface.forbidden?.let { AuthException(ErrorCode.FORBIDDEN, it) } ?: AuthException(ErrorCode.FORBIDDEN)
}

/**
 * 표면별 거부 문구. 인가 규칙이 아니라 **메시지 카탈로그**다 — 기존 통합테스트가 단언하는 문구를
 * 그대로 보존하기 위해 8종을 개별 상수로 유지한다.
 *
 * @param bearerDenial 위임 베어러가 이 표면에 도달했을 때의 403 메시지.
 * @param forbidden 권한 부족 403 메시지. null 이면 ErrorCode.FORBIDDEN 기본 문구를 쓴다.
 */
enum class OrgConsoleSurface(
    val bearerDenial: String,
    val forbidden: String? = null,
) {
    MEMBER("멤버 관리는 세션 인증에서만 가능합니다"),
    INVITATION("초대 관리는 세션 인증에서만 가능합니다", "이 조직의 초대를 관리할 권한이 없습니다"),
    PROFILE("조직 편집은 세션 인증에서만 가능합니다"),
    AUDIT("활동로그 조회는 세션 인증에서만 가능합니다"),
    DASHBOARD("콘솔은 세션 인증에서만 가능합니다"),
    DEPARTMENT("부서 관리는 세션 인증에서만 가능합니다"),
    SITE("사업장 관리는 세션 인증에서만 가능합니다"),
    DOMAIN("도메인 관리는 세션 인증에서만 가능합니다"),
    MEAL_POLICY("식대 정책 관리는 세션 인증에서만 가능합니다"),
    ROLE("역할 관리는 세션 인증에서만 가능합니다", "이 조직의 역할을 관리할 권한이 없습니다"),
}
