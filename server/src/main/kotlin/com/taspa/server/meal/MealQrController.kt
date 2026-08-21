package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.meal.dto.MealEntitlementView
import com.taspa.server.meal.dto.MealQrIssueRequest
import com.taspa.server.meal.dto.MealQrIssueResponse
import com.taspa.server.meal.dto.MealTransactionView
import com.taspa.server.org.OrganizationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 식권 QR API(사용자 세션 — default 체인 anyRequest().authenticated()). 부분 인증(pending)은
 * SecurityContext 에 없으므로 여기 도달하지 못한다(로그인 플로우 불변식). /api/ 이하 경로라 CSRF 는
 * 면제된다(default 체인 표준 — 파괴적 세션/관리 API 아님).
 */
@RestController
@RequestMapping("/api/meal")
class MealQrController(
    private val mealQrService: MealQrService,
    private val mealEntitlementService: MealEntitlementService,
    private val userRepository: UserRepository,
    private val organizationService: OrganizationService,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    /**
     * QR 발급 — 소속 org 의 활성 멤버만(403). 응답의 token 원문이 QR 페이로드가 된다(60초 TTL).
     *
     * 인가 판정은 **서비스 호출 전에** 끝난다: 엔진이 집행하려면 판정이 부수효과 이전에 나와야 한다.
     * `isActiveMember` 로 멤버십 사실을 읽어 엔진에 넘기고, 통과한 뒤에만 서비스를 호출한다
     * (서비스의 기존 검사는 방어층으로 유지 — 다른 호출자·경합 대비).
     * 쿨다운(429)은 인가 통과 후의 거절이라 이 판정에 섞이지 않는다.
     */
    @PostMapping("/qr")
    fun issue(
        authentication: Authentication,
        @RequestBody request: MealQrIssueRequest,
    ): ResponseEntity<MealQrIssueResponse> {
        val orgId =
            request.orgId
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "orgId 를 입력하세요")
        val userId = currentUserId(authentication)
        authorizeIssue(userId, orgId)
        return ResponseEntity.ok(mealQrService.issue(orgId, userId))
    }

    /**
     * 본인 거래 이력 최근 N 건(기본 20, 상한 100).
     *
     * **엔진 이관 대상 아님**: org 앵커가 없어 리소스 TRN 을 만들 수 없고, 인가는 userId 필터에 의한
     * 행 단위 소유권이 담당한다(정책 엔진이 모델링하는 역할·테넌시 판정이 아니다).
     */
    @GetMapping("/transactions")
    fun myTransactions(
        authentication: Authentication,
        @RequestParam(required = false, defaultValue = "$DEFAULT_LIMIT") limit: Int,
    ): ResponseEntity<List<MealTransactionView>> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIMIT)
        return ResponseEntity.ok(mealQrService.myTransactions(currentUserId(authentication), effectiveLimit))
    }

    /**
     * 내 식대 자격 — 지금 결제 가능한지(끼니창·일 횟수)와 이번 달 소진분을 서버 계산으로 돌려준다.
     * 읽기 전용이라 step-up(@RequireRecentAuth)은 붙이지 않는다(발급·해제 같은 상태변경이 아니다).
     *
     * 화면이 이 값을 직접 재계산하지 않게 하는 것이 요점이다 — 계산이 갈라지면 화면은 "가능"인데
     * POS 가 MEAL_WINDOW_CLOSED/DAILY_MEAL_LIMIT 로 거절한다(MealEntitlementService KDoc).
     */
    @GetMapping("/entitlement")
    fun entitlement(
        authentication: Authentication,
        @RequestParam(required = false) orgId: UUID?,
    ): ResponseEntity<MealEntitlementView> {
        val org = orgId ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "orgId 를 입력하세요")
        val user = currentUser(authentication)
        authorizeEntitlement(user, org)
        return ResponseEntity.ok(mealEntitlementService.entitlement(org, user.id!!))
    }

    /** 세션 principal(name=이메일) → users 행. 완전 인증 세션 전제(default 체인). */
    private fun currentUser(authentication: Authentication): User =
        userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)

    /** 세션 principal(name=이메일) → users.id. 완전 인증 세션 전제(default 체인). */
    private fun currentUserId(authentication: Authentication): UUID =
        currentUser(authentication).id ?: throw AuthException(ErrorCode.FORBIDDEN)

    /**
     * QR 발급 인가 — **판정자는 정책 엔진 하나다.** 활성 멤버십은 판정이 아니라 엔진에 넘길 사실이며,
     * `isActiveMember` 가 org SUSPENDED·멤버십 비활성을 함께 접는다(MealQrService 와 같은 판정식).
     *
     * memberOf 만 채우면 충분하다: 발급은 멤버 능력(ORG_MEMBER_ACTIONS)이고 ORG_ADMIN 은 정의상 활성
     * 멤버라 isOrgAdmin 추가 질의가 판정을 바꾸지 않는다. 플랫폼 ADMIN 은 브리지의 명시 Deny 대상이라
     * (멤버가 아니면) 거부된다.
     */
    private fun authorizeIssue(
        userId: UUID,
        orgId: UUID,
    ) {
        val activeMember = organizationService.isActiveMember(orgId, userId)
        val request =
            AuthorizationRequest(
                action = IamActions.MEAL_ISSUE_QR,
                resource = Trn.mealQr(orgId),
                context = iamContextFactory.build(IamPrincipalKind.SESSION, orgId),
            )
        val allowed =
            iamShadowService.authorize(request, "MealQrController.issue") {
                iamShadowService.decideSession(
                    userId = userId,
                    // ★platformAdmin=false 로 고정한다. 이 표면의 레거시 판정(MealQrService.issue)은 users.role 을
                    // **아예 참조하지 않고** 활성 멤버십만 본다. platformAdmin=true 를 넘기면 브리지의
                    // PlatformAdminExclusions(명시 Deny — meal:IssueQr 포함)가 합류하는데, 명시 Deny 는 어떤 Allow 도
                    // 이기므로 **멤버인 플랫폼 관리자까지 거부**되어 파리티가 역전된다.
                    // false 로 두면 비멤버 ADMIN=암묵적 거부(레거시와 동일), 멤버=orgMember Allow(레거시와 동일).
                    platformAdmin = false,
                    orgAdminOf = null,
                    memberOf = if (activeMember) orgId else null,
                    request = request,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN, "해당 조직의 활성 멤버가 아닙니다")
    }

    /**
     * 자격 조회 인가 — 발급과 같은 조건(해당 org 활성 멤버)이지만 **판정 인자가 하나 다르다.**
     *
     * 리소스로 `Trn.mealQr(orgId)` 를 쓰는 이유: 이 응답은 "이 조직에서 발급한 QR 이 승인될 조건"
     * 그 자체이므로 대상 자원이 식권과 같다. 읽기/쓰기 구분은 action 축(`meal:ReadEntitlement` vs
     * `meal:IssueQr`)이 지고, 자원을 같이 두면 "이 조직의 식권 자원"을 겨냥한 정책 한 줄이 발급과
     * 조회를 함께 덮는다(조회만 열고 발급은 막는 정책도 action 으로 표현 가능하다).
     *
     * ★platformAdmin 은 **사실 그대로** 넘긴다 — MealQrController.issue 의 `false` 고정을 복사하지 않는다.
     * 그 고정은 `meal:IssueQr` 이 PLATFORM_ADMIN_EXCLUDED_ACTIONS(명시 Deny)에 있어서 생기는 예외
     * 처리이고, 명시 Deny 는 멤버 Allow 도 이기므로 true 를 넘기면 멤버인 플랫폼 관리자까지 거부된다.
     * `meal:ReadEntitlement` 는 읽기 전용이라 실지출을 만들지 않아 제외 목록에 없다 — 다른 조회 표면
     * (InvoiceController·ForecastController)과 같이 사실을 넘기면 된다.
     */
    private fun authorizeEntitlement(
        user: User,
        orgId: UUID,
    ) {
        val userId = user.id ?: throw AuthException(ErrorCode.FORBIDDEN)
        val platformAdmin = user.role == UserRole.ADMIN.name
        // 판정이 아니라 **엔진에 넘길 사실**이다. isActiveMember 가 org SUSPENDED·멤버십 비활성을 함께
        // 접는다(발급 경로와 같은 판정식). 플랫폼 ADMIN 이면 조회를 건너뛴다(질의 수 불변).
        val activeMember = !platformAdmin && organizationService.isActiveMember(orgId, userId)
        val request =
            AuthorizationRequest(
                action = IamActions.MEAL_READ_ENTITLEMENT,
                resource = Trn.mealQr(orgId),
                context = iamContextFactory.build(IamPrincipalKind.SESSION, orgId),
            )
        val allowed =
            iamShadowService.authorize(request, "MealQrController.entitlement") {
                iamShadowService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    // 멤버 능력이라 memberOf 만 채우면 충분하다 — ORG_ADMIN 은 정의상 활성 멤버다.
                    orgAdminOf = null,
                    memberOf = if (activeMember) orgId else null,
                    request = request,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN, "해당 조직의 활성 멤버가 아닙니다")
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
