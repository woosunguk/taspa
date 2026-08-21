package com.taspa.server.consumption

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.consumption.dto.ConsumptionAggregateResponse
import com.taspa.server.consumption.dto.ConsumptionIngestResult
import com.taspa.server.consumption.dto.ConsumptionWriteBatch
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.org.OrganizationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 소비 이벤트 수집/집계 API(Phase 0ب-C) — /api/orgs 이하 전용 리소스 서버 체인(SecurityConfig)에서 인증된다.
 * 이 체인은 CSRF 를 끄므로(베어러 토큰 전제) **적재(POST)는 베어러 M2M 전용**으로 강제한다(세션 쿠키 쓰기 금지).
 *
 * 적재(write): `meal.consumption.write` 스코프 + **org 결속**(토큰의 org_id/orgs 클레임이 경로 org 포함) 필수.
 *   세션·비결속 토큰은 403. 본문에 org 를 두지 않아(경로만 권위) 타 org 적재를 원천 차단한다.
 * 집계 조회(read): 캘린더 조회와 동일한 3계층 인가 —
 *   1) 세션 사용자: 전역 ADMIN 또는 해당 org 활성 멤버.
 *   2) 사용자 베어러(authorization_code): sub 이 실제 사용자면 해당 org 활성 멤버여야 한다(전조회 금지).
 *   3) 서비스 베어러(client_credentials): `meal.consumption.read.all`(신뢰 플랫폼 전조회) 또는 org 결속 +
 *      `meal.consumption.read`. 결속 없는 일반 read 는 거부(fail-closed).
 * 집계만 노출한다 — 개별 이벤트·user_sub 를 반환하는 원시 조회 API 는 존재하지 않는다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/consumption-events")
class ConsumptionEventController(
    private val consumptionEventService: ConsumptionEventService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    @PostMapping
    fun ingest(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody batch: ConsumptionWriteBatch,
    ): ResponseEntity<ConsumptionIngestResult> {
        authorizeWrite(authentication, orgId)
        // ★장부 예약 source 가드 — source=payment 는 내부 거래 서비스(redeem/void)의 전유 네임스페이스다.
        //   auth_id 는 비밀이 아니므로(POS 응답·사용자 거래 이력에 노출) org 결속 생산자가 (payment, auth_id)
        //   멱등키로 장부 유래 이벤트를 full-replace 개변(예: VOIDED→CONFIRMED 복원, quantity 부풀림)해
        //   장부-seam 정합을 깨는 것을 API 경계에서 차단한다. 외부 생산자는 pos·manual·import 를 쓴다.
        if (batch.events.any { it.source.trim().equals(RESERVED_LEDGER_SOURCE, ignoreCase = true) }) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "source=payment 는 내부 거래 서비스 전용입니다 (pos·manual·import 를 사용하세요)",
            )
        }
        // ★가맹 귀속(merchant_id)은 **장부 전용**이다 — 외부 생산자는 지정할 수 없다.
        //   merchants 는 플랫폼 전역이고 가맹 그레인 집계(aggregateByMerchantDateWindow)는 테넌시 앵커가
        //   org_id 가 아니라 merchant_id 라서 **org 를 넘어 합산**한다. 즉 본문의 merchant_id 를 그대로 믿으면
        //   A 조직의 write 스코프 생산자가 B 매장의 UUID 로 이벤트를 적재해 그 매장의 식수예측을 오염시킬 수
        //   있다(매장은 오지도 않은 손님 인분을 준비하게 된다). merchant_id 를 세우는 유일한 경로는 실제 승인이
        //   일어난 redeem/void 트랜잭션이며, 거기서는 토큰의 merchant 클레임이 매장을 증명한다.
        if (batch.events.any { it.merchantId != null }) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "merchant_id 는 지정할 수 없습니다 (가맹 귀속은 식권 승인 거래에서만 기록됩니다)",
            )
        }
        // ingest 는 @Transactional — 여기서 반환됐다는 건 커밋 성공(멱등 충돌 409·검증 400 은 예외로 이 줄에 못 옴).
        // 따라서 아래 배치 audit 는 자연히 커밋 후(afterCommit) 성공 배치에만 남는다.
        val result = consumptionEventService.ingest(orgId, batch.events)
        recordIngestAudit(authentication, orgId, batch, result)
        return ResponseEntity.ok(result)
    }

    /**
     * 배치 단위 provenance audit(요청 1건당 1건 — per-event 아님). 소비 이벤트는 예측의 정답데이터라
     * "누가/어느 클라이언트가 언제 얼마나 적재했는지"를 org 스코프 활동로그에 남긴다.
     *  - userId=null(M2M 는 사용자 없음), org 결속(orgId 오버로드)으로 조직 활동로그/관리자 audit 에 잡힌다.
     *  - detail 은 clientId·집계 카운트·source 집합만 — user_sub·개별 이벤트 내용은 절대 넣지 않는다(집계만 노출 규칙).
     *  - 적재 표면은 M2M 전용이라 통과한 authentication 은 사실상 JwtAuthenticationToken 이다(행위자 귀속의 근거).
     *    정책 추가로 다른 주체가 통과할 여지에 대비해 캐스팅은 안전 캐스팅으로 두고 clientId 는 null 을 허용한다.
     */
    private fun recordIngestAudit(
        authentication: Authentication,
        orgId: UUID,
        batch: ConsumptionWriteBatch,
        result: ConsumptionIngestResult,
    ) {
        val clientId = (authentication as? JwtAuthenticationToken)?.let { clientIdOf(it.token) }
        val sources =
            batch.events
                .mapNotNull {
                    it.source
                        .trim()
                        .lowercase()
                        .takeIf { s -> s.isNotEmpty() }
                }.distinct()
        auditEventService.record(
            type = "CONSUMPTION_INGESTED",
            userId = null,
            orgId = orgId,
            detail =
                mapOf(
                    "clientId" to clientId,
                    "received" to result.received,
                    "inserted" to result.inserted,
                    "updated" to result.updated,
                    "sources" to sources,
                ),
        )
    }

    /** client_credentials 토큰의 행위자(클라이언트) 식별 — SAS 는 sub=client_id. client_id 클레임 우선, 없으면 sub. */
    private fun clientIdOf(jwt: Jwt): String? = jwt.getClaimAsString("client_id") ?: jwt.subject

    @GetMapping("/aggregate")
    fun aggregate(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "date,meal_window") groupBy: String,
    ): ResponseEntity<ConsumptionAggregateResponse> {
        authorizeRead(authentication, orgId)
        val groups = groupBy.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val includeMenu = groups.contains("menu")
        val fromInstant = parseInstant(from, "from") ?: Instant.now().minus(java.time.Duration.ofDays(30))
        val requestedTo = parseInstant(to, "to") ?: Instant.now()
        // 조회 창 폭 상한(캘린더 listEvents 와 동일 사상) — from/to 를 무제한으로 주는 전체-이력 GROUP BY 스캔을
        // 막는다. span 이 상한을 넘으면 to 를 from+MAX_WINDOW 로 절단한다(응답에도 실효 to 를 반영해 정직하게 노출).
        val maxSpan = java.time.Duration.ofDays(MAX_AGGREGATE_WINDOW_DAYS)
        val toInstant =
            if (java.time.Duration.between(fromInstant, requestedTo) > maxSpan) {
                fromInstant.plus(maxSpan)
            } else {
                requestedTo
            }
        val rows = consumptionEventService.aggregate(orgId, fromInstant, toInstant, includeMenu)
        // 응답 groupBy 는 항상 date+meal_window 를 축으로 하고 menu 는 요청 시에만 포함한다(집계만 노출).
        val effectiveGroupBy = if (includeMenu) listOf("date", "meal_window", "menu") else listOf("date", "meal_window")
        return ResponseEntity.ok(
            ConsumptionAggregateResponse(orgId, fromInstant, toInstant, effectiveGroupBy, rows),
        )
    }

    /**
     * 적재 인가 — 생산자(M2M client_credentials) 베어러 + write 스코프 + org 결속만 허용한다.
     *  - 세션 쿠키 인증(비 JWT)은 이 CSRF-off 체인에서 쓰기를 못 하게 거부한다(CSRF 방어).
     *  - ★사용자 대면 토큰(subject 가 실제 users.id 로 해석되는 authorization_code 베어러)은 write 스코프가
     *    부여되었더라도 거부한다 — 소비 이벤트는 예측의 정답데이터라, write 는 프로그램적 생산자(결제·POS 등
     *    client_credentials)만 수행해야 하고 일반 org 멤버가 사용자 클라이언트로 정답데이터를 위조 주입하지
     *    못하게 한다(finding #3: 과대개방 차단). 비결속 토큰은 fail-closed.
     */
    private fun authorizeWrite(
        authentication: Authentication,
        orgId: UUID,
    ) {
        val jwt = (authentication as? JwtAuthenticationToken)?.token
        // 주체 종류는 컨텍스트 조건키(taspa:PrincipalType)로 엔진에 넘기는 **사실**이다 — 이 값이 M2M 이 아니면
        // LegacyPolicyBridge 의 machineOnly 조건에서 걸려 엔진이 거부한다(판정은 전적으로 엔진이 한다).
        val kind = principalKind(jwt)
        val request =
            AuthorizationRequest(
                IamActions.CONSUMPTION_WRITE,
                Trn.consumptionLog(orgId),
                iamContextFactory.build(kind, orgId),
            )
        val allowed =
            iamShadowService.authorize(request, "ConsumptionEventController.ingest") {
                // 적재는 M2M 전용 표면이라 m2m 판정으로 평가한다(비 M2M 주체는 scope·결속 없음으로 자연 거부).
                iamShadowService.decideM2m(
                    scopes = scopesOf(authentication),
                    boundOrgs = jwt?.let { boundOrgs(it) } ?: emptySet(),
                    merchantId = null,
                    request = request,
                )
            }
        // 거부 사유 메시지는 **거부된 경우에만** 지연 계산한다(허용 경로에 불필요한 질의가 없다).
        if (!allowed) throw writeDenialMessage(authentication, orgId, jwt, kind) ?: AuthException(ErrorCode.FORBIDDEN)
    }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택용.** 엔진이 이미 거부를 결정한 뒤, 그 거부를 어떤 문구로
     * 표현할지만 고른다 — 이 함수가 틀려도 권한이 열리지 않는다(허용 경로에서는 호출되지 않는다).
     * 조건·순서·메시지는 원본 그대로 유지한다(응답 상태·문구 불변).
     */
    private fun writeDenialMessage(
        authentication: Authentication,
        orgId: UUID,
        jwt: Jwt?,
        kind: IamPrincipalKind,
    ): AuthException? {
        if (jwt == null) {
            return AuthException(ErrorCode.FORBIDDEN, "적재는 M2M 베어러 토큰으로만 가능합니다")
        }
        val authorities = authentication.authorities.mapTo(HashSet()) { it.authority }
        if ("SCOPE_$CONSUMPTION_WRITE_SCOPE" !in authorities) {
            return AuthException(ErrorCode.FORBIDDEN, "meal.consumption.write scope 가 필요합니다")
        }
        if (kind == IamPrincipalKind.DELEGATED) {
            return AuthException(ErrorCode.FORBIDDEN, "적재는 M2M 서비스 토큰만 가능합니다 (사용자 토큰 불가)")
        }
        if (orgId !in boundOrgs(jwt)) {
            return AuthException(ErrorCode.FORBIDDEN, "이 조직에 결속되지 않은 토큰입니다")
        }
        return null
    }

    /** subject 가 실제 사용자(users.id)로 해석되면 사용자 대면 토큰이다(client_credentials 는 client_id subject). */
    private fun isUserToken(jwt: Jwt): Boolean =
        jwt.subject
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { userRepository.findById(it).isPresent }
            ?: false

    /**
     * 집계 조회 인가 — 캘린더 조회와 동일한 org 격리 규칙. 위반 시 403.
     * **판정자는 정책 엔진 하나다.** 여기서 모으는 것(주체 해석·멤버십·스코프)은 판정이 아니라 엔진에 넘길 사실이다.
     */
    private fun authorizeRead(
        authentication: Authentication,
        orgId: UUID,
    ) {
        val jwt = (authentication as? JwtAuthenticationToken)?.token
        // 주체 해석(세션=이메일, 베어러=sub UUID) — 판정이 아니라 엔진에 넘길 사실이다.
        val userId = subjectUserId(authentication, jwt)
        // ROLE_ADMIN 우회는 세션 주체에만 적용한다(베어러 위임 토큰에는 주지 않는다).
        val platformAdmin = jwt == null && authentication.authorities.any { it.authority == "ROLE_ADMIN" }
        val kind =
            when {
                jwt == null -> IamPrincipalKind.SESSION
                userId != null -> IamPrincipalKind.DELEGATED
                else -> IamPrincipalKind.M2M
            }
        // 멤버십 조회는 엔진 입력(사실)이지만 필요할 때만 하도록 lazy 로 묶는다 — 불필요한 DB 질의 방지.
        val activeMember: Boolean by lazy { userId != null && organizationService.isActiveMember(orgId, userId) }
        val orgAdmin: Boolean by lazy { userId != null && organizationService.isOrgAdmin(orgId, userId) }

        val request =
            AuthorizationRequest(
                IamActions.CONSUMPTION_READ_AGGREGATE,
                Trn.consumptionLog(orgId),
                iamContextFactory.build(kind, orgId),
            )
        val allowed =
            iamShadowService.authorize(request, "ConsumptionEventController.aggregate") {
                if (kind == IamPrincipalKind.M2M) {
                    iamShadowService.decideM2m(
                        scopes = scopesOf(authentication),
                        boundOrgs = jwt?.let { boundOrgs(it) } ?: emptySet(),
                        merchantId = null,
                        request = request,
                    )
                } else {
                    val member = orgAdmin || activeMember
                    // 세션 주체인데 users 행이 없는 경우에도 판정을 건너뛰지 않는다 — 저장 정책 조회 키가 없을 뿐이므로
                    // nil UUID 로 평가하고 플랫폼 ADMIN 브리지가 결정한다(판정 없이 빠져나가는 경로를 만들지 않는다).
                    val subject = userId ?: NO_USER_PRINCIPAL
                    val orgAdminOf = orgId.takeIf { orgAdmin }
                    val memberOf = orgId.takeIf { member }
                    if (kind == IamPrincipalKind.DELEGATED) {
                        // 위임 토큰은 신원 권한 ∩ 동의(scope) 경계 — 캘린더 조회와 동일한 위임 의미론이다.
                        iamShadowService.decideDelegated(
                            userId = subject,
                            platformAdmin = platformAdmin,
                            orgAdminOf = orgAdminOf,
                            memberOf = memberOf,
                            scopes = scopesOf(authentication),
                            request = request,
                        )
                    } else {
                        iamShadowService.decideSession(
                            userId = subject,
                            platformAdmin = platformAdmin,
                            orgAdminOf = orgAdminOf,
                            memberOf = memberOf,
                            request = request,
                        )
                    }
                }
            }
        // 거부 사유 메시지는 **거부된 경우에만** 지연 계산한다(허용 경로에 불필요한 질의가 없다).
        if (!allowed) {
            throw readDenialMessage(authentication, orgId, jwt, userId, platformAdmin) { activeMember }
                ?: AuthException(ErrorCode.FORBIDDEN)
        }
    }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택용.** 엔진이 이미 거부를 결정한 뒤 문구만 고른다 —
     * 이 함수가 틀려도 권한이 열리지 않는다(허용 경로에서는 호출되지 않는다).
     * 조건·순서·메시지는 원본 그대로 유지한다(응답 상태·문구 불변).
     */
    private fun readDenialMessage(
        authentication: Authentication,
        orgId: UUID,
        jwt: Jwt?,
        userId: UUID?,
        platformAdmin: Boolean,
        activeMember: () -> Boolean,
    ): AuthException? {
        if (jwt == null) {
            if (platformAdmin) return null
            if (userId == null) return AuthException(ErrorCode.FORBIDDEN)
            if (activeMember()) return null
            return AuthException(ErrorCode.FORBIDDEN, "해당 조직의 멤버가 아닙니다")
        }
        val authorities = authentication.authorities.mapTo(HashSet()) { it.authority }
        val hasRead = "SCOPE_$CONSUMPTION_READ_SCOPE" in authorities
        val hasReadAll = "SCOPE_$CONSUMPTION_READ_ALL_SCOPE" in authorities
        if (!hasRead && !hasReadAll) {
            return AuthException(ErrorCode.FORBIDDEN, "meal.consumption.read scope 가 필요합니다")
        }
        if (userId != null) {
            // 사용자 토큰 — 해당 org 활성 멤버여야 한다(전조회 금지, read.all 무관).
            if (activeMember()) return null
            return AuthException(ErrorCode.FORBIDDEN, "해당 조직의 멤버가 아닙니다")
        }
        // 서비스(M2M) 토큰 — 전조회 스코프이거나 org 결속 클레임이 경로 org 를 포함해야 한다.
        if (hasReadAll) return null
        if (orgId in boundOrgs(jwt)) return null
        return AuthException(ErrorCode.FORBIDDEN, "이 조직에 결속되지 않은 토큰입니다")
    }

    /** 주체 종류 — 세션(비 베어러)=SESSION, sub 이 users.id 로 해석되는 위임 토큰=DELEGATED, 그 외 서비스 토큰=M2M. */
    private fun principalKind(jwt: Jwt?): IamPrincipalKind =
        when {
            jwt == null -> IamPrincipalKind.SESSION
            isUserToken(jwt) -> IamPrincipalKind.DELEGATED
            else -> IamPrincipalKind.M2M
        }

    /** 주체 해석 — 세션은 이메일로, 베어러는 subject UUID 로 사용자 행을 찾는다(없으면 서비스 토큰). */
    private fun subjectUserId(
        authentication: Authentication,
        jwt: Jwt?,
    ): UUID? =
        if (jwt == null) {
            userRepository.findByEmail(authentication.name)?.id
        } else {
            jwt.subject
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { userRepository.findById(it).orElse(null) }
                ?.id
        }

    private fun scopesOf(authentication: Authentication): Set<String> =
        authentication.authorities
            .map { it.authority }
            .filter { it.startsWith(SCOPE_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(SCOPE_PREFIX) }

    /** M2M 토큰의 org 결속 클레임(org_id + orgs[].id)을 UUID 집합으로 추출한다(캘린더와 동일 규칙). */
    private fun boundOrgs(jwt: Jwt): Set<UUID> {
        val ids = mutableSetOf<UUID>()
        (jwt.claims["org_id"] as? String)?.let { runCatching { ids.add(UUID.fromString(it)) } }
        (jwt.claims["orgs"] as? List<*>)?.forEach { entry ->
            val id =
                when (entry) {
                    is Map<*, *> -> entry["id"]?.toString()
                    else -> entry?.toString()
                }
            id?.let { runCatching { ids.add(UUID.fromString(it)) } }
        }
        return ids
    }

    private fun parseInstant(
        value: String?,
        field: String,
    ): Instant? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(raw)
        } catch (ex: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$field 은 ISO-8601 Instant 여야 합니다 (예: 2026-01-01T00:00:00Z)")
        }
    }

    private companion object {
        /** 장부(meal_transactions) 유래 이벤트의 예약 source — 공개 적재 API 로는 쓸 수 없다(MealRedeemService 전유). */
        const val RESERVED_LEDGER_SOURCE = "payment"

        const val CONSUMPTION_WRITE_SCOPE = "meal.consumption.write"
        const val CONSUMPTION_READ_SCOPE = "meal.consumption.read"
        const val CONSUMPTION_READ_ALL_SCOPE = "meal.consumption.read.all"

        /** SAS 가 scope 를 노출하는 권한 접두사 — 엔진 판정용 scope 집합 추출에 쓴다. */
        const val SCOPE_PREFIX = "SCOPE_"

        /**
         * 세션 주체이지만 users 행을 찾지 못했을 때 쓰는 자리표시 principal(nil UUID).
         * 저장 정책 조회 키로만 쓰이며(해당 행 없음 → 정책 0건), 판정은 플랫폼 ADMIN 브리지가 결정한다.
         * 판정 자체를 건너뛰면 집행 모드에서 "판정 불가 = 거부"가 되어 락아웃이 되므로 반드시 평가한다.
         */
        val NO_USER_PRINCIPAL: UUID = UUID(0L, 0L)

        /** 집계 조회 창 폭(from/to span) 상한(일). 무제한 범위 full-table GROUP BY 스캔을 막는다(캘린더와 동일 사상). */
        const val MAX_AGGREGATE_WINDOW_DAYS = 400L
    }
}
