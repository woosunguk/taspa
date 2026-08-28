package com.taspa.server.meal

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.meal.MerchantStatus
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.meal.dto.MerchantIdentityView
import com.taspa.server.meal.dto.MerchantMenusResponse
import com.taspa.server.meal.dto.RedeemRequest
import com.taspa.server.meal.dto.RedeemResponse
import com.taspa.server.meal.dto.RefundRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 가맹 redeem API(식권 L1) — /api/merchant 전용 STATELESS 베어러 체인(SecurityConfig @Order(-1))에서
 * 인증된다. 세션 쿠키는 이 체인에서 아예 인증되지 않는다(401).
 *
 * 인가(SCIM 패턴 — 전 조건 충족 필수, fail-closed):
 *  1) JwtAuthenticationToken(베어러) + `meal.redeem` scope.
 *  2) 사용자(위임) 토큰 거부 — subject 가 실제 users.id 로 해석되면 거부(M2M 만).
 *  3) **merchant_id 클레임 = 가맹 앵커**(CLIENT_MERCHANT_ID_SETTING → TokenCustomizer). 미결속 403.
 *  4) merchant 존재 + ACTIVE — PENDING/SUSPENDED 는 403(MERCHANT_SUSPENDED).
 * 통과한 merchant 가 모든 효과의 스코프다(타 가맹 거래는 void 에서 404).
 */
@RestController
@RequestMapping("/api/merchant")
class MerchantRedeemController(
    private val mealRedeemService: MealRedeemService,
    private val merchantRepository: MerchantRepository,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
    private val merchantMenuLookup: MerchantMenuLookup,
) {
    /**
     * **이 단말이 어느 매장으로 결제하는가.**
     *
     * ★POS 화면 어디에도 매장 이름이 없었다. 자격증명(client_id/secret)이 어느 가맹에 결속됐는지는
     * 환경변수에만 있어서, 계산원은 **눈앞의 화면이 자기 가게 것인지 확인할 방법이 없었다**. 매장이
     * 여럿인 사업자나 단말을 옮겨 설치하는 현장에서 이건 곧 "옆 가게 이름으로 승인"이고, 발견은
     * 월말 정산 때다(그때는 이미 손님도 영수증도 없다).
     *
     * 인가는 **승인과 같은 action**(`meal:Redeem`)을 쓴다. 새 action 을 만들면 이미 발급된 가맹
     * 클라이언트가 전부 거부되고, 무엇보다 "결제할 수 있는 단말이 그 매장 이름을 아는 것"은
     * 이미 가진 능력보다 좁다 — 권한을 넓히지 않는다.
     *
     * 응답에는 매장 식별과 하루 경계만 담는다. POS 는 손님 정보도 정산 금액도 필요 없다.
     */
    @GetMapping("/me")
    fun me(authentication: Authentication): ResponseEntity<MerchantIdentityView> {
        val merchant = authorizeWithShadow(authentication, IamActions.MEAL_REDEEM, "MerchantRedeemController.me")
        return ResponseEntity.ok(
            MerchantIdentityView(
                merchantId = merchant.id!!,
                name = merchant.name,
                category = merchant.category,
                timezone = merchant.timezone,
                defaultPriceMinor = merchant.defaultPriceMinor,
            ),
        )
    }

    /**
     * 오늘 이 시각 끼니의 식단 — POS 가 배식 코너 버튼을 그리기 위한 목록.
     *
     * 인가는 [me] 와 같은 이유로 **승인과 같은 action**(`meal:Redeem`)이다. 새 action 을 만들면 이미
     * 발급된 단말 클라이언트가 전부 거부되고, "결제할 수 있는 단말이 오늘 메뉴 이름을 아는 것"은 이미
     * 가진 능력보다 좁다.
     */
    @GetMapping("/menus")
    fun menus(authentication: Authentication): ResponseEntity<MerchantMenusResponse> {
        val merchant = authorizeWithShadow(authentication, IamActions.MEAL_REDEEM, "MerchantRedeemController.menus")
        return ResponseEntity.ok(merchantMenuLookup.today(merchant))
    }

    @PostMapping("/redeem")
    fun redeem(
        authentication: Authentication,
        @RequestBody request: RedeemRequest,
    ): ResponseEntity<RedeemResponse> {
        val merchant = authorizeWithShadow(authentication, IamActions.MEAL_REDEEM, "MerchantRedeemController.redeem")
        val outcome = mealRedeemService.redeem(merchant, request)
        // @Transactional redeem 이 반환됐다는 건 커밋 성공 — 멱등 재전송(mutated=false)은 audit 를 남기지 않는다.
        if (outcome.mutated) {
            auditEventService.record(
                type = "MEAL_TX_APPROVED",
                userId = outcome.userId,
                orgId = outcome.orgId,
                detail =
                    mapOf(
                        "authId" to outcome.response.authId,
                        "merchantId" to merchant.id.toString(),
                        "clientId" to clientIdOf(authentication),
                        "amountMinor" to outcome.response.approvedAmountMinor + outcome.response.selfPaidMinor,
                        "selfPaidMinor" to outcome.response.selfPaidMinor,
                        "mealWindow" to outcome.response.mealWindow,
                    ),
            )
        }
        return ResponseEntity.ok(outcome.response)
    }

    @PostMapping("/redeem/{authId}/void")
    fun void(
        authentication: Authentication,
        @PathVariable authId: String,
    ): ResponseEntity<RedeemResponse> {
        val merchant = authorizeWithShadow(authentication, IamActions.MEAL_VOID_REDEEM, "MerchantRedeemController.void")
        val outcome = mealRedeemService.void(merchant, authId)
        if (outcome.mutated) {
            auditEventService.record(
                type = "MEAL_TX_VOIDED",
                userId = outcome.userId,
                orgId = outcome.orgId,
                detail =
                    mapOf(
                        "authId" to outcome.response.authId,
                        "merchantId" to merchant.id.toString(),
                        "clientId" to clientIdOf(authentication),
                    ),
            )
        }
        return ResponseEntity.ok(outcome.response)
    }

    /**
     * 부분 환불 — 승인은 유지하고 금액만 줄인다. 전액을 환불하면 void 와 같은 상태로 수렴한다.
     *
     * void 와 별도 엔드포인트인 이유는 현장의 일이 다르기 때문이다: 취소는 "이 결제를 없던 일로",
     * 환불은 "식사는 했는데 금액이 틀렸다". 후자를 void + 재승인으로 흉내 내면 손님이 QR 을 다시
     * 받아야 하고(토큰 단일 사용) 장부에 거래가 둘로 남는다.
     */
    @PostMapping("/redeem/{authId}/refund")
    fun refund(
        authentication: Authentication,
        @PathVariable authId: String,
        @RequestBody request: RefundRequest,
    ): ResponseEntity<RedeemResponse> {
        val merchant = authorizeWithShadow(authentication, IamActions.MEAL_REFUND_REDEEM, "MerchantRedeemController.refund")
        val outcome = mealRedeemService.refund(merchant, authId, request)
        if (outcome.mutated) {
            auditEventService.record(
                type = "MEAL_TX_REFUNDED",
                userId = outcome.userId,
                orgId = outcome.orgId,
                detail =
                    mapOf(
                        "authId" to outcome.response.authId,
                        "merchantId" to merchant.id.toString(),
                        "refundedMinor" to request.amountMinor,
                        "posRefundId" to request.posRefundId,
                        "clientId" to clientIdOf(authentication),
                    ),
            )
        }
        return ResponseEntity.ok(outcome.response)
    }

    /**
     * **판정자는 정책 엔진 하나다.** scope·주체종류·가맹 결속은 판정이 아니라 엔진에 넘길 사실이다
     * (M2M 브리지가 scope+merchant 앵커로 문장을 만들고, 사용자 위임 토큰은 `taspa:PrincipalType`
     * 조건에서 걸린다). 가맹 앵커는 org 가 아니라 merchant 이므로 리소스는 merchant TRN 이고
     * boundOrgs 는 비운다(거래의 org 는 인가 시점에 아직 알 수 없다).
     *
     * merchant 존재·ACTIVE 는 엔진이 모델링하지 않는 행 단위 가드다(LegacyPolicyBridge 경계) —
     * 엔진 통과 후에 적용하며, 거부 예외의 종류·메시지는 전과 동일하다.
     */
    private fun authorizeWithShadow(
        authentication: Authentication,
        action: String,
        label: String,
    ): Merchant {
        val jwtAuth = authentication as? JwtAuthenticationToken
        val authorities = authentication.authorities.mapTo(HashSet()) { it.authority }
        val merchantId = jwtAuth?.let { merchantIdClaim(it) }
        val hasScope = "SCOPE_$REDEEM_SCOPE" in authorities
        // 주체 종류는 엔진 입력이다. 레거시와 같은 단락 평가를 유지해 앞 조건이 걸리면 users 조회를 하지 않는다.
        val delegated = jwtAuth != null && hasScope && isUserToken(jwtAuth.token)

        val resource =
            merchantId?.let { Trn.merchant(it) }
                ?: Trn.build(Trn.SERVICE_MEAL, null as String?, "merchant", UNBOUND_MERCHANT)
        val kind = if (delegated) IamPrincipalKind.DELEGATED else IamPrincipalKind.M2M
        val request = AuthorizationRequest(action, resource, iamContextFactory.build(kind))
        val allowed =
            iamShadowService.authorize(request, label) {
                iamShadowService.decideM2m(
                    scopes =
                        authorities
                            .asSequence()
                            .filter { it.startsWith(SCOPE_PREFIX) }
                            .mapTo(HashSet()) { it.removePrefix(SCOPE_PREFIX) },
                    boundOrgs = emptySet(),
                    merchantId = merchantId,
                    request = request,
                )
            }
        if (!allowed) throw denialMessage(jwtAuth, hasScope, delegated, merchantId)

        // 행 단위 가드(엔진 경계 밖): 결속된 가맹이 실재하고 ACTIVE 여야 한다.
        val merchant =
            merchantId?.let { merchantRepository.findById(it).orElse(null) }
                ?: throw AuthException(ErrorCode.FORBIDDEN, "결속된 가맹을 찾을 수 없습니다")
        if (merchant.statusEnum() != MerchantStatus.ACTIVE) throw AuthException(ErrorCode.MERCHANT_SUSPENDED)
        return merchant
    }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택**이다 — 이 함수가 틀려도 권한이 열리지 않는다
     * (엔진이 이미 거부한 뒤에만 호출된다). 레거시와 같은 우선순위로 사유를 고른다.
     */
    private fun denialMessage(
        jwtAuth: JwtAuthenticationToken?,
        hasScope: Boolean,
        delegated: Boolean,
        merchantId: UUID?,
    ): AuthException =
        when {
            jwtAuth == null -> AuthException(ErrorCode.FORBIDDEN, "redeem 은 M2M 베어러 토큰으로만 가능합니다")
            !hasScope -> AuthException(ErrorCode.FORBIDDEN, "meal.redeem scope 가 필요합니다")
            delegated -> AuthException(ErrorCode.FORBIDDEN, "redeem 은 M2M 서비스 토큰만 가능합니다 (사용자 토큰 불가)")
            merchantId == null -> AuthException(ErrorCode.FORBIDDEN, "가맹에 결속되지 않은 토큰입니다")
            else -> AuthException(ErrorCode.FORBIDDEN)
        }

    /** merchant_id 클레임(널 안전) — 미결속/형식 오류면 null. 인가 인자 조립은 절대 예외를 던지지 않는다. */
    private fun merchantIdClaim(authentication: JwtAuthenticationToken): UUID? =
        (authentication.token.claims["merchant_id"] as? String)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** subject 가 실제 사용자(users.id)로 해석되면 사용자 위임 토큰(ScimAuthorization 과 동일 판별). */
    private fun isUserToken(jwt: Jwt): Boolean =
        jwt.subject
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { userRepository.findById(it).isPresent }
            ?: false

    /** 행위자(클라이언트) 식별 — SAS 는 sub=client_id. client_id 클레임 우선, 없으면 sub. */
    private fun clientIdOf(authentication: Authentication): String? =
        (authentication as? JwtAuthenticationToken)
            ?.token
            ?.let { it.getClaimAsString("client_id") ?: it.subject }

    private companion object {
        const val REDEEM_SCOPE = "meal.redeem"
        const val SCOPE_PREFIX = "SCOPE_"
        const val UNBOUND_MERCHANT = "unbound"
    }
}
