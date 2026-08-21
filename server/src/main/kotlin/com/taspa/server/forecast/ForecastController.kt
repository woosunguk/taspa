package com.taspa.server.forecast

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.forecast.dto.BacktestResponse
import com.taspa.server.forecast.dto.ForecastResponse
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 식수예측 P0 API — /api/orgs 이하 전용 리소스 서버 체인(SecurityConfig)에서 인증된다. 조회 전용(GET).
 *
 * 이중 인가(소비 집계 read 패턴 복제 + 관리 표면 강화):
 *  1) 세션 사용자: 플랫폼 ADMIN 또는 해당 org 활성 ORG_ADMIN 만(발주·조리 결정용 관리 표면 —
 *     일반 멤버는 403. OrgMemberController.authorize 의 판정 대상과 동일).
 *  2) 서비스 베어러(client_credentials): `meal.forecast.read` 스코프 + **org 결속**(org_id/orgs 클레임이
 *     경로 org 포함) 필수. 사용자 대면 토큰(sub 이 실제 users.id)은 스코프가 있어도 거부한다 —
 *     consumption write 의 M2M 전용 규칙과 동일(과대개방 차단). 비결속은 fail-closed.
 * 응답은 집계 파생값(예측·지표)만 — 개별 이벤트·user_sub 는 존재하지 않는다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/forecast")
class ForecastController(
    private val forecastService: ForecastService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    @GetMapping
    fun forecast(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) siteId: UUID?,
        @RequestParam(required = false) mealWindow: String?,
    ): ResponseEntity<ForecastResponse> {
        authorizeWithShadow(authentication, orgId, IamActions.FORECAST_READ, "ForecastController.forecast")
        return ResponseEntity.ok(
            forecastService.forecast(orgId, parseDate(from, "from"), parseDate(to, "to"), siteId, mealWindow),
        )
    }

    @GetMapping("/backtest")
    fun backtest(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) siteId: UUID?,
        @RequestParam(required = false) mealWindow: String?,
    ): ResponseEntity<BacktestResponse> {
        authorizeWithShadow(authentication, orgId, IamActions.FORECAST_BACKTEST, "ForecastController.backtest")
        return ResponseEntity.ok(
            forecastService.backtest(orgId, parseDate(from, "from"), parseDate(to, "to"), siteId, mealWindow),
        )
    }

    // ---- 인가 ----

    /**
     * **판정자는 정책 엔진 하나다.** 레거시 판정 계산은 없고, 엔진에 넘길 사실(역할·스코프·결속)만 해석한다.
     * 거부 예외의 종류·메시지는 전과 동일하게 유지한다 — 아래 `*DenialMessage` 는 판정이 아니라
     * **거부 사유 메시지 선택**이며 거부된 경우에만 호출된다.
     */
    private fun authorizeWithShadow(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        label: String,
    ) {
        if (authentication is JwtAuthenticationToken) {
            authorizeBearer(authentication, orgId, action, label)
            return
        }
        authorizeSession(authentication, orgId, action, label)
    }

    /**
     * 세션 경로 — 엔진 주체를 구성해 판정을 맡긴다(플랫폼 ADMIN 여부·해당 org ORG_ADMIN 여부가 입력).
     *
     * 규칙 4(판정 없는 탈출 금지): 과거에는 ROLE_ADMIN 권한만으로 users 조회 없이 통과하는 경로가 있었다.
     * 이제 users 행을 해석하지 못하면 엔진 주체를 만들 수 없으므로 **거부**한다(fail-closed) —
     * 인가 엔진이 판정하지 못한 요청을 통과시키는 것 자체가 우회 경로다.
     */
    private fun authorizeSession(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        label: String,
    ) {
        val isGlobalAdmin = authentication.authorities.any { it.authority == "ROLE_ADMIN" }
        // ★조회 실패를 요청에 전파하지 않는다(500 대신 403 — 인가 실패의 정직한 표현).
        val user = runCatching { userRepository.findByEmail(authentication.name) }.getOrNull()
        val userId = user?.id ?: throw sessionDenialMessage(isGlobalAdmin, user)
        val platformAdmin = isGlobalAdmin || user.role == UserRole.ADMIN.name
        // 판정이 아니라 **엔진에 넘길 사실**이다. 플랫폼 ADMIN 이면 조회를 건너뛴다(질의 수 불변).
        val orgAdmin = !platformAdmin && organizationService.isOrgAdmin(orgId, userId)

        val request = AuthorizationRequest(action, Trn.forecast(orgId), iamContextFactory.build(IamPrincipalKind.SESSION, orgId))
        val allowed =
            iamShadowService.authorize(request, label) {
                // 예측은 관리 표면이라 단순 멤버십으로는 통과하지 않는다 — memberOf 는 ORG_ADMIN 일 때만
                // (isActiveMember 추가 질의가 판정을 바꾸지 않는다).
                iamShadowService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = if (orgAdmin) orgId else null,
                    memberOf = if (orgAdmin) orgId else null,
                    request = request,
                )
            }
        if (!allowed) throw sessionDenialMessage(isGlobalAdmin, user)
    }

    /**
     * 베어러 경로 — scope·주체종류·org 결속은 전부 엔진 입력이다(M2M 브리지가 scope 로 문장을 만들고,
     * 위임 토큰은 `taspa:PrincipalType` 조건에서 걸린다). 이 함수는 판정을 계산하지 않는다.
     */
    private fun authorizeBearer(
        authentication: JwtAuthenticationToken,
        orgId: UUID,
        action: String,
        label: String,
    ) {
        val userToken = isUserToken(authentication.token)
        val bound = boundOrgs(authentication.token)
        val kind = if (userToken) IamPrincipalKind.DELEGATED else IamPrincipalKind.M2M
        val request = AuthorizationRequest(action, Trn.forecast(orgId), iamContextFactory.build(kind, orgId))
        val allowed =
            iamShadowService.authorize(request, label) {
                iamShadowService.decideM2m(
                    scopes = scopesOf(authentication),
                    boundOrgs = bound,
                    merchantId = null,
                    request = request,
                )
            }
        if (!allowed) throw bearerDenialMessage(authentication, userToken, bound, orgId)
    }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택**이다 — 이 함수가 틀려도 권한이 열리지 않는다
     * (이미 엔진이 거부한 뒤에만 호출된다). 기존 메시지·상태를 그대로 보존하기 위해 존재한다.
     */
    private fun sessionDenialMessage(
        isGlobalAdmin: Boolean,
        user: User?,
    ): AuthException =
        if (!isGlobalAdmin && user == null) {
            AuthException(ErrorCode.FORBIDDEN)
        } else {
            AuthException(ErrorCode.FORBIDDEN, "조직 관리자만 예측을 조회할 수 있습니다")
        }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택**이다 — 이 함수가 틀려도 권한이 열리지 않는다.
     * 레거시와 같은 우선순위(scope → 사용자토큰 → org 결속)로 사유를 고른다.
     */
    private fun bearerDenialMessage(
        authentication: JwtAuthenticationToken,
        userToken: Boolean,
        bound: Set<UUID>,
        orgId: UUID,
    ): AuthException {
        val authorities = authentication.authorities.mapTo(HashSet()) { it.authority }
        return when {
            "SCOPE_$FORECAST_READ_SCOPE" !in authorities ->
                AuthException(ErrorCode.FORBIDDEN, "$FORECAST_READ_SCOPE scope 가 필요합니다")
            userToken ->
                AuthException(ErrorCode.FORBIDDEN, "예측 조회 베어러는 M2M 서비스 토큰만 가능합니다 (사용자 토큰 불가)")
            orgId !in bound -> AuthException(ErrorCode.FORBIDDEN, "이 조직에 결속되지 않은 토큰입니다")
            else -> AuthException(ErrorCode.FORBIDDEN)
        }
    }

    /** `SCOPE_` 접두사를 걷어낸 순수 scope 집합(브리지가 기대하는 형태). */
    private fun scopesOf(authentication: JwtAuthenticationToken): Set<String> =
        authentication.authorities
            .asSequence()
            .map { it.authority }
            .filter { it.startsWith(SCOPE_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(SCOPE_PREFIX) }

    /**
     * 사용자 대면 토큰 판별(fail-closed) — 발급 규칙상 사용자 토큰의 sub 은 users.id(UUID,
     * TokenCustomizerConfig 가 고정)이고 client_credentials 의 sub 은 client_id 문자열이다.
     * users 존재 여부로 판정하면 탈퇴(하드 삭제) 직후 TTL 이 남은 잔여 사용자 토큰의 sub 이 해석되지 않아
     * M2M 으로 오판(fail-open)되므로, UUID 형태 sub 은 DB 조회 없이 무조건 사용자 토큰으로 거부한다 —
     * UUID 형태 client_id 가 등록돼 있어도 오판 방향이 거부(fail-closed)라 안전하다.
     */
    private fun isUserToken(jwt: Jwt): Boolean = jwt.subject?.let { runCatching { UUID.fromString(it) }.isSuccess } ?: false

    /** M2M 토큰의 org 결속 클레임(org_id + orgs[].id)을 UUID 집합으로 추출한다(소비 집계와 동일 규칙). */
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

    /**
     * ISO-8601 파싱 + **업무 범위 가드**. LocalDate 는 ±999999999년까지 파싱되므로 `-999999999-01-01`
     * 같은 극단값이 그대로 통과하면 예측 창 계산의 `plusDays`/`minusDays` 가 오버플로해
     * 처리되지 않은 DateTimeException → 500 이 된다. 전역 핸들러가 그걸 400 으로 받아 주긴 하지만
     * 문구가 일반적이라, 어느 파라미터가 왜 틀렸는지는 여기서만 말해 줄 수 있다.
     */
    private fun parseDate(
        value: String?,
        field: String,
    ): LocalDate? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed =
            try {
                LocalDate.parse(raw)
            } catch (ex: DateTimeParseException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "$field 는 ISO-8601 날짜여야 합니다 (예: 2026-07-20)")
            }
        if (parsed < MIN_QUERY_DATE || parsed > MAX_QUERY_DATE) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "$field 는 $MIN_QUERY_DATE ~ $MAX_QUERY_DATE 범위여야 합니다",
            )
        }
        return parsed
    }

    private companion object {
        const val FORECAST_READ_SCOPE = "meal.forecast.read"
        const val SCOPE_PREFIX = "SCOPE_"
        val MIN_QUERY_DATE: LocalDate = LocalDate.of(2000, 1, 1)
        val MAX_QUERY_DATE: LocalDate = LocalDate.of(2100, 12, 31)
    }
}
