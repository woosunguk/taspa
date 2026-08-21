package com.taspa.server.iam

import java.util.UUID

/**
 * TRN(taspa resource name) — AWS ARN 대응. 형식: `trn:taspa:{service}:{org}:{type}[/{id}]`.
 *   service = 도메인(org·billing·meal·…), org = 조직 UUID 또는 빈 세그먼트(플랫폼 전역), type/id = 리소스.
 * 정책 Resource 패턴은 이 형식에 `*`/`?` 글롭과 `${'$'}{taspa:OrgId}` 정책 변수를 섞는다.
 */
object Trn {
    const val SERVICE_PLATFORM = "platform"
    const val SERVICE_ORG = "org"
    const val SERVICE_BILLING = "billing"
    const val SERVICE_MEAL = "meal"
    const val SERVICE_CONSUMPTION = "consumption"
    const val SERVICE_FORECAST = "forecast"
    const val SERVICE_CALENDAR = "calendar"
    const val SERVICE_SCIM = "scim"
    const val SERVICE_IAM = "iam"

    fun build(
        service: String,
        org: String?,
        type: String,
        id: String? = null,
    ): String {
        val base = "trn:taspa:$service:${org ?: ""}:$type"
        return if (id != null) "$base/$id" else base
    }

    fun build(
        service: String,
        org: UUID?,
        type: String,
        id: String? = null,
    ): String = build(service, org?.toString(), type, id)

    /**
     * TRN 의 org 세그먼트(4번째)를 구조적으로 추출한다 — `trn:taspa:{service}:{org}:{type}...` 의 org.
     * 형식이 아니거나 세그먼트가 없으면 빈 문자열(=플랫폼/무소속). 글롭 매칭이 아니라 이 정확 추출값이
     * 테넌시 판정(taspa:ResourceOrg 조건)의 근거가 된다.
     */
    fun orgSegmentOf(resource: String): String {
        if (!resource.startsWith(PREFIX)) return ""
        // PREFIX 이후: "{service}:{org}:{type}[/{id}]" — service 다음 콜론부터 org 가 시작한다.
        val afterPrefix = resource.substring(PREFIX.length)
        val serviceEnd = afterPrefix.indexOf(':')
        if (serviceEnd < 0) return ""
        val orgStart = serviceEnd + 1
        val orgEnd = afterPrefix.indexOf(':', orgStart)
        if (orgEnd < 0) return ""
        return afterPrefix.substring(orgStart, orgEnd)
    }

    private const val PREFIX = "trn:taspa:"

    // ── org 도메인 리소스 ──────────────────────────────────────────────
    fun organization(org: UUID): String = build(SERVICE_ORG, org, "organization", org.toString())

    fun member(
        org: UUID,
        userId: UUID,
    ): String = build(SERVICE_ORG, org, "member", userId.toString())

    /**
     * 멤버 **컬렉션** TRN. 대상이 아직 특정되지 않은 판정에 쓴다 — 초대의 ORG_ADMIN 승격 사전 검사가
     * 그런 경우다(초대 대상은 아직 이 조직의 멤버가 아니다).
     */
    fun members(org: UUID): String = build(SERVICE_ORG, org, "member", "*")

    fun invitation(
        org: UUID,
        id: UUID,
    ): String = build(SERVICE_ORG, org, "invitation", id.toString())

    fun invitations(org: UUID): String = build(SERVICE_ORG, org, "invitation", "*")

    fun department(
        org: UUID,
        id: UUID,
    ): String = build(SERVICE_ORG, org, "department", id.toString())

    fun departments(org: UUID): String = build(SERVICE_ORG, org, "department", "*")

    fun site(
        org: UUID,
        id: UUID,
    ): String = build(SERVICE_ORG, org, "site", id.toString())

    fun sites(org: UUID): String = build(SERVICE_ORG, org, "site", "*")

    fun orgDomain(
        org: UUID,
        id: UUID,
    ): String = build(SERVICE_ORG, org, "domain", id.toString())

    fun orgDomains(org: UUID): String = build(SERVICE_ORG, org, "domain", "*")

    fun dashboard(org: UUID): String = build(SERVICE_ORG, org, "dashboard")

    fun audit(org: UUID): String = build(SERVICE_ORG, org, "audit")

    fun delegations(org: UUID): String = build(SERVICE_ORG, org, "delegation", "*")

    /** 조직 커스텀 역할. 4번째 세그먼트 규약(org)을 지켜 `orgSegmentOf` 가 그대로 동작한다. */
    fun role(
        org: UUID,
        id: UUID,
    ): String = build(SERVICE_ORG, org, "role", id.toString())

    fun roles(org: UUID): String = build(SERVICE_ORG, org, "role", "*")

    // ── billing / meal / consumption / forecast / calendar / scim ──────
    fun invoice(
        org: UUID,
        id: UUID,
    ): String = build(SERVICE_BILLING, org, "invoice", id.toString())

    fun invoices(org: UUID): String = build(SERVICE_BILLING, org, "invoice", "*")

    fun mealQr(org: UUID): String = build(SERVICE_MEAL, org, "qr")

    fun mealTransactions(org: UUID): String = build(SERVICE_MEAL, org, "transaction", "*")

    /**
     * 조직 식대 정책. 4번째 세그먼트가 org 라는 규약을 그대로 지키므로 [orgSegmentOf] 가 org 를
     * 정확히 뽑아내고, 그 결과 `taspa:ResourceOrg` 정확일치 조건에 의한 테넌시 격리가 그대로 작동한다.
     */
    fun mealPolicy(org: UUID): String = build(SERVICE_MEAL, org, "policy")

    fun mealPolicyRevisions(org: UUID): String = build(SERVICE_MEAL, org, "policy", "revision/*")

    fun mealPolicyOverrides(org: UUID): String = build(SERVICE_MEAL, org, "policy", "override/*")

    /** 가맹점은 org 가 아니라 merchant_id 앵커 — org 세그먼트를 비운다(전역). */
    fun merchant(merchantId: UUID): String = build(SERVICE_MEAL, null as String?, "merchant", merchantId.toString())

    fun consumptionLog(org: UUID): String = build(SERVICE_CONSUMPTION, org, "log")

    fun forecast(org: UUID): String = build(SERVICE_FORECAST, org, "forecast")

    fun calendarEvents(org: UUID): String = build(SERVICE_CALENDAR, org, "events")

    fun scimDirectory(org: UUID): String = build(SERVICE_SCIM, org, "directory")

    // ── 관리 콘솔(/admin) 리소스 ──────────────────────────────────────
    //
    // ★org 스코프 admin 자원도 service 세그먼트는 `platform` 이다. 콘솔과 같은 `trn:taspa:org:{org}:...`
    //   위에 올리면, IAM 관리자가 자연스럽게 쓸 위임 정책 한 줄
    //     {"Effect":"Allow","Action":"*","Resource":"trn:taspa:org:X:*"}
    //   이 platform:ForceVerifyOrgDomain(자동가입 보안앵커 우회)·platform:AdministerOrg(정지 자가 해제)·
    //   platform:AddOrgMember(멤버 직접 주입 → meal:IssueQr 앵커 획득)까지 넘긴다. service 를 갈라 두면
    //   그 글롭이 구조적으로 매치하지 못한다.
    //
    //   org 세그먼트는 그대로 유지한다 — orgSegmentOf 가 service 와 무관하게 4번째 세그먼트를 뽑으므로
    //   taspa:ResourceOrg 가 계속 채워지고, 미래의 "org X 한정 위임 admin" 정책을 조건으로 표현할 수 있다.
    private const val TYPE_CONSOLE = "console"

    fun platformConsole(page: String): String = build(SERVICE_PLATFORM, null as String?, TYPE_CONSOLE, page)

    fun platformDashboard(): String = build(SERVICE_PLATFORM, null as String?, "dashboard")

    fun platformOrganizations(): String = build(SERVICE_PLATFORM, null as String?, "organization", "*")

    fun platformUser(userId: String): String = build(SERVICE_PLATFORM, null as String?, "user", userId)

    fun platformUsers(): String = build(SERVICE_PLATFORM, null as String?, "user", "*")

    fun platformClient(clientId: String): String = build(SERVICE_PLATFORM, null as String?, "client", clientId)

    fun platformClients(): String = build(SERVICE_PLATFORM, null as String?, "client", "*")

    /** ★`merchant(…)` 재사용 금지 — 그건 `merchantAdmin()` 브리지가 매장 관리자에게 주는 자원이다. */
    fun platformMerchant(merchantId: String): String = build(SERVICE_PLATFORM, null as String?, "merchant", merchantId)

    fun platformMerchants(): String = build(SERVICE_PLATFORM, null as String?, "merchant", "*")

    fun platformSsoConnection(id: String): String = build(SERVICE_PLATFORM, null as String?, "sso-connection", id)

    fun platformSsoConnections(): String = build(SERVICE_PLATFORM, null as String?, "sso-connection", "*")

    fun platformAudit(): String = build(SERVICE_PLATFORM, null as String?, "audit")

    // org 스코프 admin 자원(org 세그먼트 유지 → ResourceOrg 가 채워진다)
    fun adminOrg(org: String): String = build(SERVICE_PLATFORM, org, "organization", org)

    fun adminOrgMember(
        org: String,
        userId: String,
    ): String = build(SERVICE_PLATFORM, org, "member", userId)

    fun adminOrgMembers(org: String): String = build(SERVICE_PLATFORM, org, "member", "*")

    fun adminOrgDomain(
        org: String,
        id: String,
    ): String = build(SERVICE_PLATFORM, org, "domain", id)

    fun adminOrgDomains(org: String): String = build(SERVICE_PLATFORM, org, "domain", "*")

    fun adminCalendarFeed(
        org: String,
        id: String,
    ): String = build(SERVICE_PLATFORM, org, "calendar-feed", id)

    fun adminCalendarFeeds(org: String): String = build(SERVICE_PLATFORM, org, "calendar-feed", "*")

    // IAM 자체 관리
    fun iamPolicy(id: String): String = build(SERVICE_IAM, null as String?, "policy", id)

    fun iamPolicies(): String = build(SERVICE_IAM, null as String?, "policy", "*")

    fun iamGroup(id: String): String = build(SERVICE_IAM, null as String?, "group", id)

    fun iamGroups(): String = build(SERVICE_IAM, null as String?, "group", "*")

    fun iamPrincipal(
        type: String,
        id: String,
    ): String = build(SERVICE_IAM, null as String?, "principal", "$type/$id")

    fun iamSimulation(): String = build(SERVICE_IAM, null as String?, "simulation")
}
