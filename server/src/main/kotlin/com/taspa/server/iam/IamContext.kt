package com.taspa.server.iam

import com.taspa.server.stepup.StepUp
import com.taspa.server.stepup.StepUpProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import java.util.UUID

/**
 * 예약 조건키. 엔진이 스스로 채우거나(ResourceOrg) 호출부가 컨텍스트로 넘긴다.
 * AWS 의 `aws:` 접두 글로벌 조건키 대응 — 정책 작성자는 이 키들로 상황을 제약한다.
 */
object IamContextKeys {
    /** 요청이 대상으로 삼은 조직(경로 변수). 정책 변수 `${'$'}{taspa:OrgId}` 치환에도 쓰인다. */
    const val ORG_ID = "taspa:OrgId"

    /** **엔진이 리소스 TRN 에서 구조적으로 추출해 권위적으로 채운다**(호출자 값은 덮어씀). 테넌시의 최종 방어선. */
    const val RESOURCE_ORG = "taspa:ResourceOrg"

    /** 최근 재인증(step-up) 통과 여부. AWS `aws:MultiFactorAuthPresent` 대응. */
    const val STEP_UP_PRESENT = "taspa:StepUpPresent"

    /** 주체 종류. AWS `aws:PrincipalType` 대응 — 위임 토큰이 M2M 권한에 도달하는 confused-deputy 를 정책으로 막는다. */
    const val PRINCIPAL_TYPE = "taspa:PrincipalType"

    /** 요청 시각(ISO-8601). 시간 제약 조건(Date* 연산자)에 쓴다. */
    const val CURRENT_TIME = "taspa:CurrentTime"

    /**
     * 대상 자원이 속한 **부서 경로**(`/{root}/{...}/{self}/`). ResourceOrg 와 같이 **엔진이 권위적으로**
     * 채우며, 호출자가 컨텍스트에 넣은 동명 키는 평가 직전에 지워진다. 부서 서브트리 위임 정책은
     * `StringLike` 글롭 `(별표)/{deptId}/(별표)` 로 이 값을 검사한다.
     */
    const val RESOURCE_DEPARTMENT_PATH = "taspa:ResourceDepartmentPath"
}

/**
 * 주체 종류.
 *  - SESSION: 로그인 세션 사용자(폼·패스키·소셜 등으로 인증된 사람).
 *  - M2M: client_credentials 서비스 토큰(사람 없음).
 *  - DELEGATED: 사용자를 대신하는 authorization_code 베어러 토큰(제3자 앱에 위임된 권한).
 * DELEGATED 를 M2M 과 구분하는 것이 핵심이다 — 사용자 권한을 위임받은 토큰이 서비스 전용 능력에
 * 도달하면 confused-deputy 가 된다.
 */
enum class IamPrincipalKind { SESSION, M2M, DELEGATED }

/**
 * 인가 요청 컨텍스트 조립기. step-up 여부처럼 요청 스코프에서만 알 수 있는 신호를 조건키로 승격한다.
 * 요청 밖(스케줄 잡 등)에서 호출되면 step-up 키는 생략된다 — 양성 연산자 기준으로 Allow 는 미부여,
 * Deny 는 적용(fail-safe)되므로 누락이 권한을 넓히지 않는다.
 */
@Component
class IamContextFactory(
    private val stepUpProperties: StepUpProperties,
) {
    fun build(
        principalKind: IamPrincipalKind,
        orgId: UUID? = null,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val context = HashMap<String, String>(8)
        context[IamContextKeys.PRINCIPAL_TYPE] = principalKind.name
        context[IamContextKeys.CURRENT_TIME] = Instant.now().toString()
        orgId?.let { context[IamContextKeys.ORG_ID] = it.toString() }
        currentRequest()?.let { request ->
            // step-up 은 세션 주체에게만 의미가 있다(베어러에는 auth_time 세션 속성이 없다).
            if (principalKind == IamPrincipalKind.SESSION) {
                context[IamContextKeys.STEP_UP_PRESENT] = StepUp.isRecent(request, stepUpProperties.maxAge).toString()
            }
        }
        context.putAll(extra)
        return context
    }

    private fun currentRequest(): HttpServletRequest? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
