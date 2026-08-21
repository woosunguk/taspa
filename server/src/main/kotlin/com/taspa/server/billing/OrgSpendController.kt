package com.taspa.server.billing

import com.taspa.server.billing.dto.OrgSpendView
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.org.OrganizationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 식대 집계 API — /api/orgs 체인(세션 인증 전용). 조직관리자 대시보드의 첫 질문
 * "이번 달 식대가 얼마 나가고 있나"에 **청구서를 만들지 않고** 답한다.
 *
 * 읽기 전용이라 `@RequireRecentAuth`(step-up)를 붙이지 않는다 — step-up 은 상태변경(청구서 생성·확정)에
 * 붙는 가드이고, 대시보드를 열 때마다 재인증을 요구하면 화면이 쓰이지 않는다.
 *
 * 인가는 InvoiceController 와 동일하다: **위임 베어러 거부 + (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN)**,
 * 타 org 403. action 은 `billing:ReadSpend`(ORG_ADMIN_ACTIONS 에 명시 열거).
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/spend")
class OrgSpendController(
    private val orgSpendService: OrgSpendService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val iamAuthorizationService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    /**
     * @param period 'YYYY-MM'(org 타임존 달력). 생략하면 org 타임존 기준 이번 달.
     */
    @GetMapping
    fun spend(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<OrgSpendView> {
        authorize(authentication, orgId)
        return ResponseEntity.ok(orgSpendService.spend(orgId, period))
    }

    /**
     * 인가 격리 — InvoiceController.authorizeWithShadow 와 동일한 계약.
     *
     * **판정자는 정책 엔진 하나다.** 여기서는 엔진에 넘길 사실(플랫폼 ADMIN 여부·해당 org ORG_ADMIN 여부)만
     * 해석하고 allow/deny 를 직접 합성하지 않는다.
     */
    private fun authorize(
        authentication: Authentication,
        orgId: UUID,
    ) {
        if (authentication is JwtAuthenticationToken) {
            // 위임 베어러는 이 표면에서 무조건 닫힌다 — 청구서 표면과 같은 금액 데이터를 보여 주므로,
            // under-consented access_token 재사용(confused-deputy)에 열어 둘 이유가 없다.
            throw AuthException(ErrorCode.FORBIDDEN, "식대 집계 조회는 세션 인증에서만 가능합니다")
        }
        val user = userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)
        val userId = user.id ?: throw AuthException(ErrorCode.FORBIDDEN)
        val platformAdmin = user.role == UserRole.ADMIN.name
        // 판정이 아니라 엔진에 넘길 사실이다. 플랫폼 ADMIN 이면 조회를 건너뛴다(질의 수 불변).
        val orgAdmin = !platformAdmin && organizationService.isOrgAdmin(orgId, userId)

        // TRN 전용 헬퍼를 만들지 않고 조립한다 — `trn:taspa:billing:{org}:spend` 는 브리지의 org 스코프
        // 패턴(`trn:taspa:*:{org}:*`)에 그대로 매치하고, ResourceOrg 조건이 테넌시를 가른다.
        val resource = Trn.build(Trn.SERVICE_BILLING, orgId, "spend")
        val request =
            AuthorizationRequest(
                IamActions.BILLING_READ_SPEND,
                resource,
                iamContextFactory.build(IamPrincipalKind.SESSION, orgId),
            )
        val allowed =
            iamAuthorizationService.authorize(request, "OrgSpendController.spend") {
                // memberOf 는 ORG_ADMIN 일 때만 채운다: 단순 멤버로는 통과하지 않는 표면이라
                // isActiveMember 추가 질의가 판정을 바꾸지 않는다(ORG_ADMIN 은 정의상 활성 멤버).
                iamAuthorizationService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = if (orgAdmin) orgId else null,
                    memberOf = if (orgAdmin) orgId else null,
                    request = request,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN)
    }
}
