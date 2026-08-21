package com.taspa.server.iam

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * 관리 콘솔 표면의 인가 선언을 **기동 시점에** 검증한다. 실패하면 컨텍스트가 뜨지 않는다.
 *
 * 왜 테스트가 아니라 기동 게이트인가: 이 프로젝트의 통합 테스트는 모두 `IntegrationTestBase` 의
 * `@SpringBootTest` 한 곳을 공유한다. 여기서 실패하면 **모든 통합 테스트가 함께 실패**하므로, 별도
 * 커버리지 테스트를 만들어 "그 테스트를 돌렸는지"에 의존하지 않아도 된다. 새 관리 엔드포인트를 추가한
 * 사람은 선언을 빠뜨리는 순간 앱이 뜨지 않는 것으로 즉시 알게 된다.
 *
 * 검증 항목은 전부 "조용히 잘못될 수 있는 것"들이다:
 *  1. 선언 누락 — 없으면 폴백 판정으로 조용히 돌아 이관이 무의미해진다.
 *  2. action 이 [IamActions.PLATFORM_CONSOLE_ACTIONS] 밖 — `org:`/`meal:` action 을 쓰면 그 순간
 *     조직관리자·일반 멤버가 관리 조작에 도달한다.
 *  3. 리소스 네임스페이스 — 콘솔 자원(`trn:taspa:org:...`) 위에 올리면 org 위임 정책의 글롭이 넘어온다.
 *  4. 리소스 템플릿의 `{var}` 가 그 매핑의 URI 변수에 실제로 존재하는가 — 없으면 런타임에 렌더가 실패해
 *     **거부**로 떨어지는데, 관리자는 항상 통과하던 표면이라 배포 후에야 드러난다.
 *  5. action 중복 배정 — 복붙 사고. 다른 엔드포인트를 Deny 하려던 정책이 엉뚱한 것을 막는다.
 */
@Component
class PlatformSurfaceValidator(
    private val applicationContext: ApplicationContext,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(PlatformSurfaceValidator::class.java)

    override fun run(args: ApplicationArguments) {
        // ★getBean(RequestMappingHandlerMapping::class.java) 를 쓰지 않는다 — actuator 의
        //   ControllerEndpointHandlerMapping 이 이 타입을 **상속**해서 NoUniqueBeanDefinitionException 이
        //   나고, 그러면 컨텍스트 로딩이 실패해 전체 테스트가 죽는다. 이름으로 지정한다.
        val mapping = applicationContext.getBean(MAPPING_BEAN, RequestMappingHandlerMapping::class.java)

        val problems = mutableListOf<String>()
        val actionOwners = mutableMapOf<String, MutableList<String>>()
        var declared = 0

        for ((info, method) in mapping.handlerMethods) {
            val paths = pathsOf(info)
            if (paths.none { AdminSurface.matches(it) }) continue

            val where = "${method.beanType.simpleName}.${method.method.name} ${paths.joinToString(",")}"
            val declaration =
                AnnotatedElementUtils.findMergedAnnotation(method.method, PlatformAction::class.java)
                    ?: AnnotatedElementUtils.findMergedAnnotation(method.beanType, PlatformAction::class.java)

            if (declaration == null) {
                problems += "@PlatformAction 선언 없음: $where"
                continue
            }
            declared++

            if (declaration.action !in IamActions.PLATFORM_CONSOLE_ACTIONS) {
                problems += "action 이 PLATFORM_CONSOLE_ACTIONS 밖: ${declaration.action} ($where)"
            }
            if (RESOURCE_PREFIXES.none { declaration.resource.startsWith(it) }) {
                problems += "리소스 네임스페이스 위반: ${declaration.resource} ($where) — " +
                    "반드시 ${RESOURCE_PREFIXES.joinToString(" 또는 ")} 로 시작해야 한다"
            }
            val templateVars = PLACEHOLDER.findAll(declaration.resource).map { it.groupValues[1] }.toSet()
            val missing = templateVars - uriVariablesOf(paths)
            if (missing.isNotEmpty()) {
                problems += "리소스 템플릿 변수가 경로에 없음: $missing (${declaration.resource}, $where)"
            }
            if (declaration.action !in SHARED_ACTIONS) {
                actionOwners.getOrPut(declaration.action) { mutableListOf() } += where
            }
        }

        actionOwners.filterValues { it.size > 1 }.forEach { (action, owners) ->
            problems += "action 중복 배정: $action → ${owners.joinToString(" / ")}"
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "관리 콘솔 인가 선언 검증 실패(${problems.size}건):\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
        log.info("관리 콘솔 인가 선언 검증 통과: {}개 핸들러", declared)
    }

    /** Boot 3.x 기본은 PathPatternParser 라 전자가 채워지지만, AntPathMatcher 구성도 폴백으로 받는다. */
    private fun pathsOf(info: RequestMappingInfo): Set<String> =
        info.pathPatternsCondition?.patternValues
            ?: info.patternsCondition?.patterns
            ?: emptySet()

    /** `/api/admin/orgs/{orgId}/domains/{domainId}` → {orgId, domainId} */
    private fun uriVariablesOf(paths: Set<String>): Set<String> =
        paths.flatMapTo(mutableSetOf()) { path ->
            PLACEHOLDER.findAll(path).map { it.groupValues[1].substringBefore(':') }
        }

    private companion object {
        const val MAPPING_BEAN = "requestMappingHandlerMapping"
        val RESOURCE_PREFIXES = listOf("trn:taspa:platform:", "trn:taspa:iam:")

        /** 여러 핸들러가 공유해도 되는 action — 콘솔 페이지 진입은 본래 하나의 능력이다. */
        val SHARED_ACTIONS = setOf(IamActions.PLATFORM_ACCESS_CONSOLE)
        val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+(?::[^}]*)?)}""")
    }
}
