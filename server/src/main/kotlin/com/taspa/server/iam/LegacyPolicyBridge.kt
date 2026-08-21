package com.taspa.server.iam

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 레거시 역할/스코프 → 정책 문서 브리지(shadow 파리티용). 기존 인가 신호(플랫폼 ADMIN, ORG_ADMIN, 활성 멤버,
 * M2M scope+앵커)를 등가의 PolicyDocument 로 합성해, 엔진 판정이 레거시 판정과 일치하는지 검증하게 한다.
 *
 * ★테넌시 강제 방식(2중):
 *  1) 리소스 패턴에 구체 org UUID 를 박는다(정책 변수 금지 — 변수는 "요청이 가리키는 org"로 치환돼
 *     타 org 요청에도 매치되는 교차테넌트 함정이 있다).
 *  2) 그 위에 `taspa:ResourceOrg` **정확일치 조건**을 건다. 이 키는 PolicyEvaluator 가 요청 리소스에서
 *     구조적으로 뽑아 권위적으로 채우므로, 글롭 `*` 가 `:` 경계를 넘더라도 org 경계는 절대 넘지 못한다.
 *
 * ★엔진 경계(중요 — 이 브리지가 모델링하지 "않는" 것):
 *  - step-up 재인증(@RequireRecentAuth / RecentAuthInterceptor), CSRF, 위임 베어러 거부(confused-deputy),
 *    행 단위 소유권(예: void 는 자기 merchant 만) 은 **외부 인터셉터/서비스 계층에 남는다.**
 *    엔진은 "역할·스코프·테넌시에 근거한 action/resource 판정"만 대체 대상이며, 집행 전환 시에도
 *    위 가드들은 제거하지 않는다. 따라서 이 브리지의 문장에는 step-up 조건을 걸지 않는다(레거시 파리티 유지).
 */
@Component
class LegacyPolicyBridge {
    private val version = PolicyParser.DEFAULT_VERSION

    /**
     * 플랫폼 ADMIN — 전권(테넌시 제약 없음). **단 레거시가 주지 않는 능력은 명시 Deny 로 제외**한다.
     *
     * `*`/`*` 만 두면 엔진이 레거시보다 **강해진다** — 예컨대 식권 발급은 활성 멤버십에 결속된 능력이라
     * 레거시는 비멤버 플랫폼 관리자에게 주지 않는데, 엔진이 허용하면 임의 조직 명의로 실지출이 발생한다.
     * 명시 Deny 는 어떤 Allow 로도 넘을 수 없으므로(AWS 동일) 이 제외는 정책 추가로 되살아나지 않는다.
     */
    fun platformAdmin(): PolicyDocument =
        PolicyDocument(
            version,
            listOf(
                Statement(sid = "PlatformAdmin", effect = Effect.ALLOW, actions = listOf("*"), resources = listOf("*")),
                Statement(
                    sid = "PlatformAdminExclusions",
                    effect = Effect.DENY,
                    actions = IamActions.PLATFORM_ADMIN_EXCLUDED_ACTIONS,
                    resources = listOf("*"),
                ),
            ),
        )

    /**
     * 가맹 관리자(사람 신원) — **자기 매장의 조회 권한만**. 해당 merchant 리소스로 한정한다.
     *
     * 결제 승인(`meal:Redeem`)은 여기 없다 — 그건 기계 신원(POS 단말) 전용이다. 사람이 로그인해서 임의
     * 승인을 만들 수 있으면 계정 탈취가 곧 무단 결제가 되므로, 조회와 승인의 신원을 분리해 둔다.
     *
     * 리소스 TRN 은 org 세그먼트가 비어 있어(가맹은 org 스코프 자원이 아니다) ResourceOrg 조건이 아니라
     * **merchant UUID 정확 일치**가 테넌시를 가른다.
     */
    fun merchantAdmin(merchantId: UUID): PolicyDocument =
        PolicyDocument(
            version,
            listOf(
                Statement(
                    sid = "MerchantAdmin",
                    effect = Effect.ALLOW,
                    actions =
                        listOf(
                            IamActions.MERCHANT_READ_TRANSACTIONS,
                            IamActions.MERCHANT_READ_FORECAST,
                            IamActions.MERCHANT_READ_SETTLEMENT,
                        ),
                    resources = listOf(Trn.merchant(merchantId)),
                ),
            ),
        )

    /** 조직 활성 멤버(비관리자) — 식권 발급·조회, 캘린더/소비 집계 읽기. 해당 org 리소스로 한정. */
    fun orgMember(orgId: UUID): PolicyDocument =
        PolicyDocument(
            version,
            listOf(orgScoped("OrgMember", IamActions.ORG_MEMBER_ACTIONS, orgId)),
        )

    /** ORG_ADMIN — 멤버 권한 + org/billing/forecast 관리. 전부 해당 org 리소스로 한정. */
    fun orgAdmin(orgId: UUID): PolicyDocument =
        PolicyDocument(
            version,
            listOf(
                orgScoped("OrgMember", IamActions.ORG_MEMBER_ACTIONS, orgId),
                orgScoped("OrgAdmin", IamActions.ORG_ADMIN_ACTIONS, orgId),
            ),
        )

    /**
     * **부서 서브트리 위임자(부서장)** — 자기 부서와 그 하위에 속한 자원만.
     *
     * 두 조건이 AND 로 걸린다:
     *  ① `taspa:ResourceOrg` 정확일치 — 다른 조직으로 새지 않는다(다른 브리지 문장과 같은 장치).
     *  ② `taspa:ResourceDepartmentPath` 글롭 — 대상 자원의 부서 경로에 이 부서가 들어 있어야 한다.
     *     경로는 엔진이 [ResourceScope] 에서만 채우므로 호출자가 지어낼 수 없다.
     *
     * ★두 번째 조건이 **양성 연산자**라는 점이 중요하다. 부서 축이 없는 자원(조직 전체 설정, 청구서)은
     * 키가 아예 방출되지 않고, 양성 조건 + 키 부재는 Allow 문장에서 **미적용**이다([ConditionEvaluator]
     * 의 fail-safe 편향). 즉 새 표면을 추가하면서 스코프를 채우는 것을 잊으면 위임자는 **거부**된다 —
     * 잊었을 때 열리는 쪽이 아니라 닫히는 쪽으로 실패한다.
     *
     * 명시 Deny 는 [IamActions.DEPARTMENT_DELEGATE_DENIED_ACTIONS] — **조건 없이** 전 리소스에 건다.
     * 조건을 달면 그 조건이 평가되지 않는 요청에서 Deny 가 빠져나갈 수 있는데, 이 Deny 들은 위임의
     * 자기 증식(부서원을 ORG_ADMIN 으로 승격 → 전권)을 막는 장치라 예외를 만들면 안 된다.
     */
    fun departmentDelegate(
        orgId: UUID,
        departmentId: UUID,
    ): PolicyDocument =
        PolicyDocument(
            version,
            listOf(
                Statement(
                    sid = "DepartmentDelegate:$departmentId",
                    effect = Effect.ALLOW,
                    actions = IamActions.DEPARTMENT_DELEGATE_ACTIONS,
                    resources = listOf("trn:taspa:*:$orgId:*"),
                    conditions =
                        listOf(
                            Condition("StringEquals", PolicyEvaluator.RESOURCE_ORG_KEY, listOf(orgId.toString())),
                            Condition(
                                "StringLike",
                                IamContextKeys.RESOURCE_DEPARTMENT_PATH,
                                listOf(ResourceScope.subtreeGlob(departmentId)),
                            ),
                        ),
                ),
                Statement(
                    sid = "DepartmentDelegateExclusions",
                    effect = Effect.DENY,
                    actions = IamActions.DEPARTMENT_DELEGATE_DENIED_ACTIONS,
                    resources = listOf("*"),
                ),
            ),
        )

    /**
     * M2M client_credentials 토큰의 scope + 앵커 → 정책 문서들.
     * boundOrgs 는 org 결속(org_id/orgs 클레임), merchantId 는 가맹 결속, scimOrg 는 SCIM 전용 **단일** org
     * 앵커다(SCIM 은 org_id 클레임만 인정하고 orgs[] 를 보지 않으므로 boundOrgs 와 분리한다).
     */
    fun m2m(
        scopes: Set<String>,
        boundOrgs: Set<UUID>,
        merchantId: UUID?,
        scimOrg: UUID? = null,
    ): List<PolicyDocument> {
        val statements = mutableListOf<Statement>()

        if ("meal.redeem" in scopes && merchantId != null) {
            // 가맹 앵커는 org 가 아닌 merchant — org 조건 없이 merchant TRN 정확 매치.
            statements +=
                Statement(
                    sid = "M2mRedeem",
                    effect = Effect.ALLOW,
                    actions = listOf(IamActions.MEAL_REDEEM, IamActions.MEAL_VOID_REDEEM, IamActions.MEAL_REFUND_REDEEM),
                    resources = listOf(Trn.merchant(merchantId)),
                )
        }
        boundOrgs.forEach { org ->
            if ("meal.consumption.write" in scopes) {
                statements += orgScoped("M2mConsumptionWrite:$org", listOf(IamActions.CONSUMPTION_WRITE), org, Trn.consumptionLog(org))
            }
            if ("meal.consumption.read" in scopes) {
                statements +=
                    orgScoped("M2mConsumptionRead:$org", listOf(IamActions.CONSUMPTION_READ_AGGREGATE), org, Trn.consumptionLog(org))
            }
            if ("meal.forecast.read" in scopes) {
                statements +=
                    orgScoped(
                        "M2mForecast:$org",
                        listOf(IamActions.FORECAST_READ, IamActions.FORECAST_BACKTEST),
                        org,
                        Trn.forecast(org),
                    )
            }
            if ("calendar.read" in scopes) {
                statements += orgScoped("M2mCalendar:$org", listOf(IamActions.CALENDAR_READ_EVENTS), org, Trn.calendarEvents(org))
            }
        }
        // SCIM 은 단일 org_id 앵커 전용(orgs[] 무시 — ScimAuthorization 과 정합).
        if (scimOrg != null && "org.scim" in scopes) {
            statements +=
                orgScoped(
                    "M2mScim:$scimOrg",
                    listOf(IamActions.SCIM_MANAGE_DIRECTORY),
                    scimOrg,
                    Trn.scimDirectory(scimOrg),
                )
        }
        // 전조회 스코프(*.read.all) — 모든 org 대상(테넌시 조건 없음, 신뢰 플랫폼 클라이언트 전용).
        if ("meal.consumption.read.all" in scopes) {
            statements +=
                Statement(
                    sid = "M2mConsumptionReadAll",
                    effect = Effect.ALLOW,
                    actions = listOf(IamActions.CONSUMPTION_READ_AGGREGATE),
                    resources = listOf(Trn.build(Trn.SERVICE_CONSUMPTION, "*", "log")),
                )
        }
        if ("calendar.read.all" in scopes) {
            statements +=
                Statement(
                    sid = "M2mCalendarReadAll",
                    effect = Effect.ALLOW,
                    actions = listOf(IamActions.CALENDAR_READ_EVENTS),
                    resources = listOf(Trn.build(Trn.SERVICE_CALENDAR, "*", "events")),
                )
        }

        if (statements.isEmpty()) return emptyList()
        // ★모든 M2M 문장에 "주체가 기계 신원일 것" 조건을 단다 — 사용자 위임 토큰(authorization_code)에
        // 기계 전용 scope 가 붙어도 이 조건에서 걸린다(레거시의 isUserToken 거부를 엔진 안으로 이관).
        // 컨텍스트에 taspa:PrincipalType 이 반드시 채워져 있어야 한다(IamContextFactory) — 키가 없으면
        // 양성 연산자 fail-safe 로 Allow 가 미적용되어 M2M 표면 전체가 거부된다.
        val machineOnly =
            Condition(
                "StringEquals",
                IamContextKeys.PRINCIPAL_TYPE,
                listOf(IamPrincipalKind.M2M.name),
            )
        return listOf(
            PolicyDocument(version, statements.map { it.copy(conditions = it.conditions + machineOnly) }),
        )
    }

    /**
     * **위임 경계(delegation boundary)** — 사용자 위임 토큰(authorization_code)이 넘을 수 없는 상한.
     * AWS permission boundary 와 같은 의미론으로, 최종 권한 = 신원 권한(∩) 이 경계다(IamAuthorizationService.decideDelegated).
     *
     * 두 가지를 동시에 강제한다:
     *  1. **동의(scope) 강제** — 토큰이 받은 scope 가 허용하는 action 만 경계에 들어온다. 사용자가 org 멤버라도
     *     제3자 앱이 동의받지 않은 능력에는 도달하지 못한다(confused deputy 차단).
     *  2. **기계 전용 능력 차단** — 아래 표에 없는 scope 는 경계에 **아무것도 기여하지 않는다**. `org.scim`·
     *     `meal.consumption.write`·`meal.forecast.read`·`*.read.all` 은 프로그램적 신원(client_credentials)
     *     전용이라, 그 scope 가 사용자 토큰에 붙어도 위임으로는 사용할 수 없다(레거시의 isUserToken 거부와 등가).
     *
     * 경계는 org 를 제한하지 않는다(resource `*`) — org 격리는 신원 쪽(orgMember/orgAdmin 의 ResourceOrg 조건)이
     * 이미 담당하며, 교집합이므로 둘 중 좁은 쪽이 이긴다.
     */
    fun delegationBoundary(scopes: Set<String>): List<PolicyDocument> {
        val actions = scopes.flatMap { DELEGATABLE_SCOPE_ACTIONS[it].orEmpty() }.distinct()
        if (actions.isEmpty()) return emptyList()
        return listOf(
            PolicyDocument(
                version,
                listOf(
                    Statement(
                        sid = "DelegationBoundary",
                        effect = Effect.ALLOW,
                        actions = actions,
                        resources = listOf("*"),
                    ),
                ),
            ),
        )
    }

    /**
     * org 스코프 Allow 문장: 리소스 패턴(기본 = 그 org 전체) + ResourceOrg 정확일치 조건.
     * 조건이 테넌시의 최종 방어선이므로 org 스코프 문장은 반드시 이 헬퍼로 만든다.
     */
    private fun orgScoped(
        sid: String,
        actions: List<String>,
        orgId: UUID,
        resource: String? = null,
    ) = Statement(
        sid = sid,
        effect = Effect.ALLOW,
        actions = actions,
        resources = listOf(resource ?: "trn:taspa:*:$orgId:*"),
        conditions = listOf(Condition("StringEquals", PolicyEvaluator.RESOURCE_ORG_KEY, listOf(orgId.toString()))),
    )

    private companion object {
        /**
         * **사용자에게 위임 가능한** scope → action. 여기 없는 scope 는 위임 경계에 기여하지 않는다.
         * 레거시가 사용자 토큰에 허용하는 표면(캘린더 조회·소비 집계 조회)과 정확히 일치한다 —
         * 나머지(`org.scim`·`meal.consumption.write`·`meal.forecast.read`·`*.read.all`)는 기계 전용이라
         * 사용자 토큰에 그 scope 가 붙어 있어도 위임으로 행사할 수 없다.
         */
        val DELEGATABLE_SCOPE_ACTIONS: Map<String, List<String>> =
            mapOf(
                "calendar.read" to listOf(IamActions.CALENDAR_READ_EVENTS),
                "meal.consumption.read" to listOf(IamActions.CONSUMPTION_READ_AGGREGATE),
            )
    }
}
