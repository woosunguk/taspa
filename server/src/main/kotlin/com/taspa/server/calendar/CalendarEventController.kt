package com.taspa.server.calendar

import com.taspa.server.calendar.dto.CalendarEventPage
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 조직 캘린더 이벤트 조회 API(Phase 0-E) — /api/orgs 이하 전용 리소스 서버 체인(SecurityConfig)에서
 * 인증된다. 인가는 **본인 org 만**(스펙 E)을 강제하며, 세 종류의 호출자를 구분한다:
 *  1) 세션 사용자 — 전역 ADMIN 또는 해당 org 의 활성 멤버(org 격리).
 *  2) 사용자 베어러 JWT(authorization_code) — sub 이 실제 사용자면 그 사용자의 활성 멤버십으로 org 를 강제한다.
 *     `calendar.read` 만으로 임의 org 를 읽던 테넌시 공백을 닫는다.
 *  3) 서비스 베어러 JWT(client_credentials) — `calendar.read.all`(신뢰 플랫폼 전조회) 이거나, 토큰의
 *     org 결속 클레임(org_id/orgs)이 경로 orgId 를 포함해야 한다. 결속 없는 `calendar.read` M2M 은 거부(fail-closed).
 * 그 외에는 403. SUSPENDED 조직 차단은 조회 서비스(CalendarService.listEvents)가 담당한다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/calendar")
class CalendarEventController(
    private val calendarService: CalendarService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val iamShadowService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    @GetMapping("/events")
    fun events(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<CalendarEventPage> {
        authorize(authentication, orgId)
        val fromInstant = parseInstant(from, "from")
        val toInstant = parseInstant(to, "to")
        return ResponseEntity.ok(calendarService.listEvents(orgId, fromInstant, toInstant, page, size))
    }

    /**
     * org 격리 + 권한 검사. 위반 시 403(FORBIDDEN).
     * **판정자는 정책 엔진 하나다.** 여기서 모으는 것(주체 해석·멤버십·스코프)은 판정이 아니라 엔진에 넘길 사실이다.
     */
    private fun authorize(
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
                IamActions.CALENDAR_READ_EVENTS,
                Trn.calendarEvents(orgId),
                iamContextFactory.build(kind, orgId),
            )
        val allowed =
            iamShadowService.authorize(request, "CalendarEventController.events") {
                if (kind == IamPrincipalKind.M2M) {
                    iamShadowService.decideM2m(
                        scopes = scopesOf(authentication),
                        boundOrgs = jwt?.let { boundOrgs(it) } ?: emptySet(),
                        merchantId = null,
                        request = request,
                    )
                } else {
                    val member = orgAdmin || activeMember
                    // 규칙 5: 세션 주체인데 users 행이 없는 경우(레거시는 ROLE_ADMIN 만 통과)에도 판정을 건너뛰지
                    // 않는다 — 저장 정책 조회 키가 없을 뿐이므로 nil UUID 로 평가하고 플랫폼 ADMIN 브리지가 결정한다.
                    val subject = userId ?: NO_USER_PRINCIPAL
                    val orgAdminOf = orgId.takeIf { orgAdmin }
                    val memberOf = orgId.takeIf { member }
                    if (kind == IamPrincipalKind.DELEGATED) {
                        // 위임 토큰은 신원 권한 ∩ 동의(scope) 경계 — scope 필수 규칙이 엔진 안에서 강제된다.
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
            throw denialMessage(authentication, orgId, jwt, userId, platformAdmin) { activeMember }
                ?: AuthException(ErrorCode.FORBIDDEN)
        }
    }

    /**
     * **인가 판정이 아니라 거부 사유 메시지 선택용.** 엔진이 이미 거부를 결정한 뒤, 그 거부를 어떤 문구로
     * 표현할지만 고른다 — 이 함수가 틀려도 권한이 열리지 않는다(허용 경로에서는 호출되지 않는다).
     * 세션 사용자 → 전역 ADMIN 또는 해당 org 활성 멤버, 베어러 → 스코프 확인 후 org 결속(사용자 토큰은 활성
     * 멤버십) 순으로 문구를 고르며, 조건·순서·메시지는 원본 그대로 유지한다(응답 상태·문구 불변).
     */
    private fun denialMessage(
        authentication: Authentication,
        orgId: UUID,
        jwt: Jwt?,
        userId: UUID?,
        platformAdmin: Boolean,
        activeMember: () -> Boolean,
    ): AuthException? {
        if (jwt == null) {
            // 세션 사용자 — 전역 ADMIN 또는 해당 org 활성 멤버.
            if (platformAdmin) return null
            if (userId == null) return AuthException(ErrorCode.FORBIDDEN)
            if (activeMember()) return null
            return AuthException(ErrorCode.FORBIDDEN, "해당 조직의 멤버가 아닙니다")
        }
        val authorities = authentication.authorities.mapTo(HashSet()) { it.authority }
        val hasRead = "SCOPE_$CALENDAR_READ_SCOPE" in authorities
        val hasReadAll = "SCOPE_$CALENDAR_READ_ALL_SCOPE" in authorities
        if (!hasRead && !hasReadAll) {
            return AuthException(ErrorCode.FORBIDDEN, "calendar.read scope 가 필요합니다")
        }
        if (userId != null) {
            // 사용자 토큰(authorization_code) — 반드시 해당 org 의 활성 멤버여야 한다(전조회 금지, read.all 무관).
            if (activeMember()) return null
            return AuthException(ErrorCode.FORBIDDEN, "해당 조직의 멤버가 아닙니다")
        }
        // 서비스(M2M) 토큰 — 전조회 스코프이거나 org 결속 클레임이 경로 org 를 포함해야 한다.
        if (hasReadAll) return null
        if (orgId in boundOrgs(jwt)) return null
        return AuthException(ErrorCode.FORBIDDEN, "이 조직에 결속되지 않은 토큰입니다")
    }

    private fun scopesOf(authentication: Authentication): Set<String> =
        authentication.authorities
            .map { it.authority }
            .filter { it.startsWith(SCOPE_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(SCOPE_PREFIX) }

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

    /** M2M 토큰의 org 결속 클레임(org_id + orgs[].id)을 UUID 집합으로 추출한다. */
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
        const val CALENDAR_READ_SCOPE = "calendar.read"
        const val CALENDAR_READ_ALL_SCOPE = "calendar.read.all"

        /** SAS 가 scope 를 노출하는 권한 접두사 — 엔진 판정용 scope 집합 추출에 쓴다. */
        const val SCOPE_PREFIX = "SCOPE_"

        /**
         * 세션 주체이지만 users 행을 찾지 못했을 때 쓰는 자리표시 principal(nil UUID).
         * 저장 정책 조회 키로만 쓰이며(해당 행 없음 → 정책 0건), 판정은 플랫폼 ADMIN 브리지가 결정한다.
         * 판정 자체를 건너뛰면 집행 모드에서 "판정 불가 = 거부"가 되어 락아웃이 되므로 반드시 평가한다.
         */
        val NO_USER_PRINCIPAL: UUID = UUID(0L, 0L)
    }
}
