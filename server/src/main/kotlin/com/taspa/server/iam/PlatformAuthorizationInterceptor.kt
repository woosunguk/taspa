package com.taspa.server.iam

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * 관리 콘솔(`/admin` 이하)의 **인가 집행 지점**. 판정은 IAM 엔진이 하고, 이 클래스는 요청에서 판정에
 * 필요한 좌표(action·resource·principal)를 뽑아 넘길 뿐이다.
 *
 * 이관 전 이 표면의 유일한 판정자는 시큐리티 체인의 `hasRole("ADMIN")` 이었다. 체인은 그대로 남지만
 * 역할이 바뀐다: **판정 권위는 엔진**이고, 체인은 (1) 엔진 배선이 빠진 경로의 백스톱과
 * (2) 미인증 진입점 결정(화면 302 / API 401)을 맡는 심층 방어다.
 *
 * ## 선언이 없을 때 왜 403 이 아니라 폴백 판정인가
 * 애노테이션이 없는 핸들러는 두 종류다 — 개발자가 빠뜨린 것과, Spring 이 합성한 것
 * (OPTIONS 요청에 대해 `RequestMappingInfoHandlerMapping` 이 만드는 `HttpOptionsHandler` 등).
 * 하드 403 을 주면 후자가 회귀가 되고(오늘 200 인 응답이 403), `return true` 를 주면 전자가 fail-open 이
 * 된다. 그래서 둘 다 **레거시와 등가인 판정**(`platform:AccessConsole`)으로 보낸다:
 *  - 합성 핸들러: 관리자면 통과(오늘과 같음), 비관리자는 체인이 이미 끊었다.
 *  - 누락 핸들러: 최악값이 "전 사용자 개방"이 아니라 "이관 전과 동일"이다.
 * 어느 쪽이든 `log.error` 로 드러내고, `PlatformSurfaceValidator` 가 기동 시점에 누락을 잡는다.
 */
@Component
class PlatformAuthorizationInterceptor(
    private val iamAuthorizationService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
    private val userRepository: UserRepository,
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(PlatformAuthorizationInterceptor::class.java)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val declaration =
            (handler as? HandlerMethod)?.let { method ->
                AnnotatedElementUtils.findMergedAnnotation(method.method, PlatformAction::class.java)
                    ?: AnnotatedElementUtils.findMergedAnnotation(method.beanType, PlatformAction::class.java)
            }
        if (declaration == null) {
            // 합성 핸들러(OPTIONS 등)와 선언 누락이 모두 여기로 온다. 후자는 배포 전에 validator 가 잡지만,
            // 런타임에도 흔적을 남겨 둔다 — 조용히 폴백으로 도는 표면이 생기지 않게.
            log.error("관리 표면 핸들러에 @PlatformAction 선언이 없다: {} {}", request.method, request.requestURI)
        }

        val action = declaration?.action ?: IamActions.PLATFORM_ACCESS_CONSOLE
        val template = declaration?.resource ?: Trn.platformConsole(FALLBACK_PAGE)

        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: throw AuthException(ErrorCode.FORBIDDEN)
        // 위임 베어러는 엔진 밖 하드 게이트로 먼저 끊는다 — 관리 콘솔은 사람 세션 전용이고, 이 거부를
        // 판정 입력으로 합성하지 않는다(다른 세션 전용 표면과 같은 규약).
        if (authentication is JwtAuthenticationToken) {
            throw AuthException(ErrorCode.FORBIDDEN, "관리 콘솔은 로그인 세션 전용입니다 (베어러 토큰 불가)")
        }

        val resource =
            renderResource(template, uriVariables(request))
                ?: throw AuthException(ErrorCode.FORBIDDEN)

        val user =
            runCatching { userRepository.findByEmail(authentication.name) }.getOrNull()
                ?: throw AuthException(ErrorCode.FORBIDDEN)
        val userId = user.id ?: throw AuthException(ErrorCode.FORBIDDEN)

        // ★세션 authority 에서 도출한다(users.role 재조회 금지). "역할은 로그인 시점에 세션에 굳는다"는
        //   기존 불변식을 체인이 아니라 엔진 안에 그대로 보존하기 위해서다 — 재조회하면 승격이 재로그인
        //   없이 즉시 유효해져 관측 동작이 달라진다.
        val platformAdmin = authentication.authorities.any { it.authority == ROLE_ADMIN }

        // orgId 는 **경로변수 이름이 아니라 렌더된 TRN 의 org 세그먼트**에서 뽑는다. 표면마다 변수명이
        // 다르기 때문이다(AdminOrgController 는 {id}, 도메인·캘린더는 {orgId}) — 이름으로 읽으면
        // 어떤 표면에서는 컨텍스트가 조용히 비어 조건 평가가 달라진다.
        val orgId =
            Trn
                .orgSegmentOf(resource)
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val authorizationRequest =
            AuthorizationRequest(
                action = action,
                resource = resource,
                context = iamContextFactory.build(IamPrincipalKind.SESSION, orgId),
            )
        // ★orgAdminOf/memberOf/merchantAdminOf 는 전부 null 이다. 관리 표면의 유일한 Allow 원천은
        //   플랫폼 관리자 브리지여야 한다 — 멤버십 사실을 섞으면 "누가 /api/admin 에 도달하는가"의 답이
        //   한 문장에서 action 별 행렬로 바뀌고, 조직관리자가 자기 org 의 관리 조작에 닿는 경로가 열린다.
        val allowed =
            iamAuthorizationService.authorize(authorizationRequest, LABEL) {
                iamAuthorizationService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = null,
                    memberOf = null,
                    request = authorizationRequest,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun uriVariables(request: HttpServletRequest): Map<String, String> =
        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String> ?: emptyMap()

    /**
     * `{name}` 자리를 요청의 URI 템플릿 변수로 치환한다. 치환값이 [SAFE_SEGMENT] 를 벗어나거나 미해결
     * placeholder 가 남으면 null(→거부).
     *
     * 값 검증이 필요한 이유: `:`·`/`·`*`·`?` 는 TRN 구분자이자 정책 글롭의 메타문자다. 검증하지 않으면
     * 경로변수 하나로 리소스의 **구조**를 바꿔 다른 자원을 가리키게 만들 수 있다.
     */
    private fun renderResource(
        template: String,
        variables: Map<String, String>,
    ): String? {
        if (!template.contains('{')) return template
        // 치환은 두 단계다: 먼저 **모든** placeholder 를 검사해 하나라도 부적격이면 포기하고,
        // 그 다음에 치환한다. 한 번에 하면 부적격 값을 만난 시점에 이미 일부가 치환돼 있어,
        // 실수로 부분 렌더된 TRN 을 흘려보내기 쉽다.
        val resolved = mutableMapOf<String, String>()
        for (match in PLACEHOLDER.findAll(template)) {
            val name = match.groupValues[1]
            val value = variables[name] ?: return null
            if (!SAFE_SEGMENT.matches(value)) return null
            resolved[name] = value
        }
        val rendered = PLACEHOLDER.replace(template) { match -> resolved.getValue(match.groupValues[1]) }
        return rendered.takeIf { !it.contains('{') }
    }

    private companion object {
        const val LABEL = "admin"
        const val ROLE_ADMIN = "ROLE_ADMIN"
        const val FALLBACK_PAGE = "fallback"
        val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)}""")
        val SAFE_SEGMENT = Regex("""^[0-9A-Za-z_.\-]{1,128}$""")
    }
}
