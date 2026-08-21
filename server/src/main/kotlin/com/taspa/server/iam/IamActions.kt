package com.taspa.server.iam

/**
 * taspa action 네임스페이스 — `service:Action` 문자열 상수. 인가 집행/shadow 평가/파리티 테스트가 공유한다.
 * (인벤토리의 URL 체인 + 컨트롤러 authorize() 판정을 fine-grained action 으로 정규화한 것)
 */
object IamActions {
    // org 콘솔(ORG_ADMIN 관리 + 멤버 읽기)
    const val ORG_LIST_MEMBERS = "org:ListMembers"
    const val ORG_CHANGE_MEMBER_ROLE = "org:ChangeMemberRole"
    const val ORG_REMOVE_MEMBER = "org:RemoveMember"
    const val ORG_ASSIGN_MEMBER = "org:AssignMember"
    const val ORG_UPDATE_MEMBER_ATTRIBUTES = "org:UpdateMemberAttributes"
    const val ORG_READ_MEMBER_HISTORY = "org:ReadMemberHistory"
    const val ORG_CREATE_INVITATION = "org:CreateInvitation"
    const val ORG_LIST_INVITATIONS = "org:ListInvitations"
    const val ORG_RESEND_INVITATION = "org:ResendInvitation"
    const val ORG_REVOKE_INVITATION = "org:RevokeInvitation"
    const val ORG_BULK_INVITE = "org:BulkInvite"
    const val ORG_LIST_DOMAINS = "org:ListDomains"
    const val ORG_REGISTER_DOMAIN = "org:RegisterDomain"
    const val ORG_VERIFY_DOMAIN = "org:VerifyDomain"
    const val ORG_REMOVE_DOMAIN = "org:RemoveDomain"
    const val ORG_CONFIGURE_AUTO_JOIN = "org:ConfigureAutoJoin"
    const val ORG_LIST_DEPARTMENTS = "org:ListDepartments"
    const val ORG_CREATE_DEPARTMENT = "org:CreateDepartment"
    const val ORG_UPDATE_DEPARTMENT = "org:UpdateDepartment"
    const val ORG_DELETE_DEPARTMENT = "org:DeleteDepartment"
    const val ORG_LIST_SITES = "org:ListSites"
    const val ORG_CREATE_SITE = "org:CreateSite"
    const val ORG_UPDATE_SITE = "org:UpdateSite"
    const val ORG_DELETE_SITE = "org:DeleteSite"
    const val ORG_UPDATE_PROFILE = "org:UpdateProfile"
    const val ORG_READ_AUDIT = "org:ReadAudit"
    const val ORG_READ_DASHBOARD = "org:ReadDashboard"

    /**
     * 부서 서브트리 위임 부여·회수. **ORG_ADMIN 전용**이며 위임자에게는 명시 Deny 로 닫는다 —
     * 위임자가 위임을 줄 수 있으면 자기 부하에게 자기 부서를 재위임하고, 그 사람이 다시… 로
     * 경계가 무한 증식한다(위임의 자기 증식).
     */
    const val ORG_MANAGE_DELEGATION = "org:ManageDelegation"
    const val ORG_LIST_DELEGATIONS = "org:ListDelegations"

    // billing
    const val BILLING_GENERATE_INVOICE = "billing:GenerateInvoice"
    const val BILLING_READ_INVOICE = "billing:ReadInvoice"
    const val BILLING_FINALIZE_INVOICE = "billing:FinalizeInvoice"

    /**
     * 진행 중인 달의 조직부담 집계 조회(읽기 전용). 청구서(`billing:GenerateInvoice`)와 분리하는 이유는
     * generate 가 DRAFT 를 full-replace 하는 **상태 변경**(+step-up +감사)이라, 대시보드가 열릴 때마다
     * 진행 중인 달을 문서로 굳혀 버리기 때문이다. 조회는 조회로 끝나야 한다.
     */
    const val BILLING_READ_SPEND = "billing:ReadSpend"

    /**
     * 3-way 대사 조회. `billing:ReadInvoice` 를 재사용하지 않는 이유는 성격이 다르기 때문이다 —
     * 청구서는 조직에 보내는 문서이고, 대사는 **시스템의 건강 상태**다. 나중에 "청구서는 보되 내부
     * 정합성 지표는 못 보는" 역할을 두려면 action 이 갈려 있어야 한다.
     */
    const val BILLING_RECONCILE = "billing:Reconcile"

    // meal(식권 발급·조회 = 멤버, redeem/void = 가맹 M2M)
    const val MEAL_ISSUE_QR = "meal:IssueQr"
    const val MEAL_READ_TRANSACTIONS = "meal:ReadTransactions"

    /**
     * 자기 식대 자격(끼니창·1식 한도·일 횟수·월 cap 과 그 소진분) 조회. 읽기 전용이라 `MEAL_ISSUE_QR`
     * 과 달리 실지출을 만들지 않으므로 플랫폼 관리자 제외 목록에 넣지 않는다.
     */
    const val MEAL_READ_ENTITLEMENT = "meal:ReadEntitlement"
    const val MEAL_REDEEM = "meal:Redeem"
    const val MEAL_VOID_REDEEM = "meal:VoidRedeem"

    /**
     * 부분 환불. `meal:VoidRedeem` 을 재사용하지 않는 이유는 두 조작의 위험이 다르기 때문이다 —
     * void 는 거래를 통째로 없애고 환불은 금액만 줄인다. 나중에 "환불은 되지만 취소는 안 되는"
     * 단말을 두려면 action 이 갈려 있어야 한다(인가 표현력의 단위가 action 축이다).
     */
    const val MEAL_REFUND_REDEEM = "meal:RefundRedeem"

    /**
     * 조직 식대 정책(한도·끼니창) 조회·편집·이력. **ORG_ADMIN 표면**이다.
     *
     * ★`meal:*` 와일드카드로 묶고 싶은 유혹이 생기는 자리인데, 그러면 조직관리자가 그 순간
     * `meal:Redeem`/`meal:VoidRedeem`(가맹 결제 승인·취소)까지 얻는다. 정책 편집은 "우리 회사가 얼마를
     * 지원할지"를 정하는 것이고 승인은 "이 결제를 성립시키는" 것이라 성격이 전혀 다르다.
     * [ORG_ADMIN_ACTIONS] 에는 반드시 이 셋만 **명시 열거**로 넣는다.
     */
    const val MEAL_READ_POLICY = "meal:ReadPolicy"
    const val MEAL_UPDATE_POLICY = "meal:UpdatePolicy"
    const val MEAL_READ_POLICY_HISTORY = "meal:ReadPolicyHistory"

    /**
     * 부서·사업장 재정의 관리. 조회는 `MEAL_READ_POLICY` 를 재사용하지 않고 따로 둔다 — 나중에
     * "부서장은 자기 부서 재정의만 본다" 같은 위임을 붙일 때 조회 권한을 따로 줄 수 있어야 한다.
     */
    const val MEAL_READ_POLICY_OVERRIDES = "meal:ReadPolicyOverrides"
    const val MEAL_MANAGE_POLICY_OVERRIDES = "meal:ManagePolicyOverrides"

    // merchant 콘솔(가맹 관리자 — 사람 신원. 결제 승인 meal:Redeem 은 여전히 기계 전용이다)
    const val MERCHANT_READ_TRANSACTIONS = "merchant:ReadTransactions"
    const val MERCHANT_READ_FORECAST = "merchant:ReadForecast"

    /**
     * 월 정산 명세. 거래 로그와 **action 을 나눈다** — 나중에 "로그는 보되 금액 합계는 못 보는" 매장 직원
     * 역할(V29 의 MERCHANT_ADMIN 고정을 푸는 시점)을 두려면 지금 갈라 놔야 한다. 합친 뒤 나누는 것은
     * 이미 발급된 정책을 전부 손대야 하는 일이다.
     */
    const val MERCHANT_READ_SETTLEMENT = "merchant:ReadSettlement"

    // consumption(적재 = M2M write, 집계 = 멤버/M2M read)
    const val CONSUMPTION_WRITE = "consumption:Write"
    const val CONSUMPTION_READ_AGGREGATE = "consumption:ReadAggregate"

    // forecast(ORG_ADMIN + M2M)
    const val FORECAST_READ = "forecast:Read"
    const val FORECAST_BACKTEST = "forecast:Backtest"

    // calendar(멤버/M2M read)
    const val CALENDAR_READ_EVENTS = "calendar:ReadEvents"

    // scim(M2M org.scim 전용)
    const val SCIM_MANAGE_DIRECTORY = "scim:ManageDirectory"

    // iam(정책 시스템 자체 관리 — 플랫폼 ADMIN)
    const val IAM_LIST_POLICIES = "iam:ListPolicies"
    const val IAM_CREATE_POLICY = "iam:CreatePolicy"
    const val IAM_UPDATE_POLICY = "iam:UpdatePolicy"
    const val IAM_DELETE_POLICY = "iam:DeletePolicy"
    const val IAM_ATTACH_POLICY = "iam:AttachPolicy"
    const val IAM_DETACH_POLICY = "iam:DetachPolicy"
    const val IAM_SIMULATE = "iam:SimulatePolicy"
    const val IAM_READ_POLICY = "iam:ReadPolicy"
    const val IAM_LIST_GROUPS = "iam:ListGroups"

    // 그룹 조작은 **핸들러마다 별도 action** 이다. 인가 표현력의 단위가 action 축이라, 하나로 묶으면
    // ("iam:ManageGroup") 생성·삭제·멤버 부여·멤버 회수를 따로 제어할 방법이 영구히 사라진다.
    // 특히 **그룹 멤버 부여는 곧 정책 부여 경로**다(정책이 그룹에 붙는다) — 별도 Deny 수요가 가장 높다.
    const val IAM_CREATE_GROUP = "iam:CreateGroup"
    const val IAM_DELETE_GROUP = "iam:DeleteGroup"
    const val IAM_LIST_GROUP_MEMBERS = "iam:ListGroupMembers"
    const val IAM_ADD_GROUP_MEMBER = "iam:AddGroupMember"
    const val IAM_REMOVE_GROUP_MEMBER = "iam:RemoveGroupMember"
    const val IAM_READ_PRINCIPAL_POLICIES = "iam:ReadPrincipalPolicies"
    const val IAM_SET_INLINE_POLICY = "iam:SetInlinePolicy"
    const val IAM_REMOVE_INLINE_POLICY = "iam:RemoveInlinePolicy"

    // ── platform(관리 콘솔 표면 — 플랫폼 ADMIN 전용) ──────────────────────
    //
    // ★별도 네임스페이스인 이유: 이 조작들은 org 콘솔과 **같은 일을 임의 org 에** 한다. `org:` 에 두면
    //   저장 정책의 `{"Action":"org:*"}` 한 줄이 조직관리자에게 플랫폼 전용 능력을 넘긴다(도메인
    //   force-verify = 자동가입 보안앵커 무력화, org status 자가 해제 등). ORG_ADMIN_ACTIONS 열거에서
    //   빼는 것만으로는 부족하다 — 네임스페이스 자체를 갈라 놓아야 와일드카드 정책이 넘어오지 못한다.
    const val PLATFORM_ACCESS_CONSOLE = "platform:AccessConsole"
    const val PLATFORM_READ_CONSOLE_DASHBOARD = "platform:ReadConsoleDashboard"

    // 조직(9)
    const val PLATFORM_LIST_ORGS = "platform:ListOrgs"
    const val PLATFORM_READ_ORG = "platform:ReadOrg"
    const val PLATFORM_CREATE_ORG = "platform:CreateOrg"

    /** org status(ACTIVE↔SUSPENDED) 변경 포함 — `org:UpdateProfile` 재사용 금지. 재사용하면 ORG_ADMIN 이 자기 조직 정지를 스스로 해제한다. */
    const val PLATFORM_ADMINISTER_ORG = "platform:AdministerOrg"
    const val PLATFORM_LIST_ORG_MEMBERS = "platform:ListOrgMembers"

    /** 초대 절차를 건너뛰고 멤버십을 직접 만든다 — 그 org 의 `meal:IssueQr` 앵커를 얻는 경로다. */
    const val PLATFORM_ADD_ORG_MEMBER = "platform:AddOrgMember"
    const val PLATFORM_CHANGE_ORG_MEMBER_ROLE = "platform:ChangeOrgMemberRole"
    const val PLATFORM_REMOVE_ORG_MEMBER = "platform:RemoveOrgMember"
    const val PLATFORM_LINK_ORG_SSO = "platform:LinkOrgSso"

    // 도메인(6)
    const val PLATFORM_LIST_ORG_DOMAINS = "platform:ListOrgDomains"
    const val PLATFORM_REGISTER_ORG_DOMAIN = "platform:RegisterOrgDomain"
    const val PLATFORM_VERIFY_ORG_DOMAIN = "platform:VerifyOrgDomain"

    /** DNS 확인 없이 검증 처리 — 도메인 자동가입의 보안앵커 ①을 우회하는 유일한 경로다. */
    const val PLATFORM_FORCE_VERIFY_ORG_DOMAIN = "platform:ForceVerifyOrgDomain"
    const val PLATFORM_UNVERIFY_ORG_DOMAIN = "platform:UnverifyOrgDomain"
    const val PLATFORM_REMOVE_ORG_DOMAIN = "platform:RemoveOrgDomain"

    // 캘린더(7)
    const val PLATFORM_LIST_CALENDAR_FEEDS = "platform:ListCalendarFeeds"
    const val PLATFORM_CREATE_CALENDAR_FEED = "platform:CreateCalendarFeed"
    const val PLATFORM_UPDATE_CALENDAR_FEED = "platform:UpdateCalendarFeed"
    const val PLATFORM_DELETE_CALENDAR_FEED = "platform:DeleteCalendarFeed"

    /** `calendar:ReadEvents` 재사용 금지 — 그건 ORG_MEMBER_ACTIONS 라 **일반 직원 전원**이 가진다. */
    const val PLATFORM_READ_CALENDAR_FEED_EVENTS = "platform:ReadCalendarFeedEvents"
    const val PLATFORM_SYNC_CALENDAR_FEED = "platform:SyncCalendarFeed"
    const val PLATFORM_IMPORT_CALENDAR_FEED = "platform:ImportCalendarFeed"

    // 사용자(6)
    const val PLATFORM_SEARCH_USERS = "platform:SearchUsers"
    const val PLATFORM_READ_USER = "platform:ReadUser"
    const val PLATFORM_SUSPEND_USER = "platform:SuspendUser"
    const val PLATFORM_UNSUSPEND_USER = "platform:UnsuspendUser"
    const val PLATFORM_REVOKE_USER_SESSIONS = "platform:RevokeUserSessions"
    const val PLATFORM_CHANGE_USER_ROLE = "platform:ChangeUserRole"

    // OAuth2 클라이언트(5)
    const val PLATFORM_LIST_CLIENTS = "platform:ListClients"

    /** org/merchant 결속 클라이언트를 만들 수 있다 — M2M 신원을 임의 테넌트에 붙이는 경로다. */
    const val PLATFORM_REGISTER_CLIENT = "platform:RegisterClient"
    const val PLATFORM_UPDATE_CLIENT = "platform:UpdateClient"
    const val PLATFORM_DELETE_CLIENT = "platform:DeleteClient"
    const val PLATFORM_REGENERATE_CLIENT_SECRET = "platform:RegenerateClientSecret"

    // 가맹(8)
    const val PLATFORM_LIST_MERCHANTS = "platform:ListMerchants"
    const val PLATFORM_READ_MERCHANT = "platform:ReadMerchant"
    const val PLATFORM_CREATE_MERCHANT = "platform:CreateMerchant"
    const val PLATFORM_UPDATE_MERCHANT = "platform:UpdateMerchant"
    const val PLATFORM_DELETE_MERCHANT = "platform:DeleteMerchant"
    const val PLATFORM_LIST_MERCHANT_MEMBERS = "platform:ListMerchantMembers"
    const val PLATFORM_GRANT_MERCHANT_MEMBER = "platform:GrantMerchantMember"
    const val PLATFORM_REVOKE_MERCHANT_MEMBER = "platform:RevokeMerchantMember"

    // 기업 SSO(6)
    const val PLATFORM_LIST_SSO_CONNECTIONS = "platform:ListSsoConnections"
    const val PLATFORM_READ_SSO_CONNECTION = "platform:ReadSsoConnection"
    const val PLATFORM_CREATE_SSO_CONNECTION = "platform:CreateSsoConnection"
    const val PLATFORM_UPDATE_SSO_CONNECTION = "platform:UpdateSsoConnection"
    const val PLATFORM_DELETE_SSO_CONNECTION = "platform:DeleteSsoConnection"
    const val PLATFORM_SET_SSO_DOMAIN_VERIFIED = "platform:SetSsoDomainVerified"

    // 전역 감사(1)
    /** `org:ReadAudit` 재사용 금지 — 콘솔 쪽은 org_id 정확일치 격리 + 행위자 이메일 마스킹이 있고 이쪽은 둘 다 없다. */
    /**
     * 전역 정합성 대사 조회 — 어느 조직이든 장부가 깨졌는지. `billing:Reconcile` 을 재사용하지 않는다:
     * 그건 org 스코프(조직관리자도 가진다)이고 이건 **전 조직**이라 능력의 크기가 다르다.
     */
    const val PLATFORM_READ_RECONCILIATION = "platform:ReadReconciliation"

    /**
     * 전역 지급 현황 — 이번 달 전 매장에 나갈 금액. `merchant:ReadSettlement` 를 재사용하지 않는다:
     * 그건 **자기 매장 하나**(merchant TRN 결속)이고 이건 전 매장이라 능력의 크기가 다르다.
     */
    const val PLATFORM_READ_PAYABLES = "platform:ReadPayables"

    /**
     * 지급 현황 CSV 내려받기. 조회와 **action 을 나눈다** — 관리 표면은 핸들러당 action 이 유일해야
     * 하고(`PlatformSurfaceValidator`), 그 규약 덕에 "화면은 보되 파일로는 못 빼는" 운영 역할을
     * 나중에 둘 수 있다. 파일은 조직 밖으로 나가므로 화면 조회보다 큰 능력이다.
     */
    const val PLATFORM_EXPORT_PAYABLES = "platform:ExportPayables"

    /**
     * 미확정 청구서 현황 — 어느 조직이 아직 확정하지 않았는가. `billing:ReadInvoice` 를 재사용하지
     * 않는다: 그건 **한 조직**의 문서를 보는 능력(조직관리자도 가진다)이고 이건 **전 조직**의 상태다.
     */
    const val PLATFORM_READ_UNFINALIZED_INVOICES = "platform:ReadUnfinalizedInvoices"

    const val PLATFORM_READ_GLOBAL_AUDIT = "platform:ReadGlobalAudit"

    // 조직 커스텀 역할(조직관리자가 자기 조직 안에서 정의하는 역할)
    const val ORG_LIST_ROLES = "org:ListRoles"
    const val ORG_MANAGE_ROLES = "org:ManageRoles"

    /**
     * 커스텀 역할에 **부여할 수 있는** action 집합 = `ORG_ADMIN_ACTIONS` − [ROLE_NON_GRANTABLE_ACTIONS].
     *
     * ★두 겹의 의미가 있다:
     *  1. **천장** — 조직관리자가 자기보다 큰 권한을 만들 수 없다(`platform:*` 은 애초에 여기 없다).
     *  2. **자기 증식 금지** — 아래 제외 목록이 없으면 커스텀 역할이 새 역할을 만들거나 자기 소유자를
     *     ORG_ADMIN 으로 승격시켜, 한 번 부여된 역할이 스스로 조직 전체 권한으로 자랄 수 있다.
     *
     * 목록에서 빼는 것만으로 충분한 이유: 정책 문서를 **서버가 생성**하므로(원시 JSON 편집 불가)
     * 여기 없는 action 은 문서에 실릴 경로 자체가 없다.
     */
    val ORG_ROLE_GRANTABLE_ACTIONS: List<String> by lazy {
        ORG_ADMIN_ACTIONS.filterNot { it in ROLE_NON_GRANTABLE_ACTIONS }
    }

    /**
     * 커스텀 역할에 **절대 부여하지 않는** action.
     *
     * - `ORG_MANAGE_ROLES` — 역할이 역할을 만들면 경계가 스스로 넓어진다.
     * - `ORG_CHANGE_MEMBER_ROLE` — 누군가를 ORG_ADMIN 으로 승격시킬 수 있다(= 조직 전체 권한).
     * - `ORG_MANAGE_DELEGATION` — 부서 위임 부여는 또 다른 권한 부여 통로다.
     *
     * (`org/DepartmentDelegationService` 의 `DEPARTMENT_DELEGATE_DENIED_ACTIONS` 와 같은 근거·같은 형태다.)
     */
    val ROLE_NON_GRANTABLE_ACTIONS =
        setOf(
            ORG_MANAGE_ROLES,
            ORG_CHANGE_MEMBER_ROLE,
            ORG_MANAGE_DELEGATION,
        )

    /** 조직 활성 멤버(비관리자)에게 허용되는 action 집합. */
    val ORG_MEMBER_ACTIONS =
        listOf(
            MEAL_ISSUE_QR,
            MEAL_READ_ENTITLEMENT,
            MEAL_READ_TRANSACTIONS,
            CALENDAR_READ_EVENTS,
            CONSUMPTION_READ_AGGREGATE,
        )

    /**
     * ORG_ADMIN 이 멤버 권한에 더해 가지는 관리 action — **명시 열거(와일드카드 금지)**.
     *
     * 과거 `org:*`·`billing:*`·`forecast:*` 와일드카드를 썼으나, 그러면 앞으로 그 네임스페이스에 추가되는
     * 모든 action 이 ORG_ADMIN 에게 **자동 부여**된다. 플랫폼 전용 조작(도메인 force-verify·멤버 직접 주입·
     * org status 변경·SSO 결속)을 `org:` 로 명명하는 순간 조직관리자가 그것까지 얻는다 — 자동가입 보안앵커
     * 무력화·초대 절차 우회 같은 실질 권한상승이다. 새 action 은 여기에 **의도적으로 추가**해야 부여된다.
     */
    /**
     * **부서 위임자(부서장)에게 주는 action** — 자기 서브트리 안에서만.
     *
     * ★여기 없는 것이 이 목록의 요점이다:
     *  - `org:ChangeMemberRole` — 부서장이 자기 부서원을 ORG_ADMIN 으로 올리면 그 사람이 전사를 얻고,
     *    그 다음 자기를 올리면 위임이 곧 전권이 된다. 위임의 자기 증식 경로다.
     *  - `org:RemoveMember` — 사람을 조직에서 빼는 것은 부서 경계를 넘는 결과(계정·식대·정산)가 있다.
     *  - 초대 계열 — 새 사람을 들이는 것은 조직의 결정이다(부서장은 배정만 한다).
     *  - `org:ListDepartments` — 그 호출부의 리소스에 부서 앵커가 없어 조건키가 비고, 결과적으로 항상
     *    거부된다. 목록에 넣어 두면 "권한을 줬는데 안 된다"로 보여 더 헷갈린다.
     */
    val DEPARTMENT_DELEGATE_ACTIONS =
        listOf(
            ORG_LIST_MEMBERS,
            ORG_ASSIGN_MEMBER,
            ORG_UPDATE_MEMBER_ATTRIBUTES,
            ORG_READ_MEMBER_HISTORY,
        )

    /**
     * 위임자에게 **절대 열리지 않는** action — 위임 정책에 명시 Deny 로 박는다.
     *
     * 열거에서 빼는 것만으로 충분하지 않은 이유: 위임자도 그 조직의 **일반 멤버**이고, 앞으로 누군가
     * 멤버 표면에 이 action 을 추가하거나 별도 정책을 부착할 수 있다. 명시 Deny 는 어떤 Allow 로도
     * 넘을 수 없으므로(AWS 동일) 그 경로를 미리 닫는다.
     */
    val DEPARTMENT_DELEGATE_DENIED_ACTIONS =
        listOf(
            ORG_CHANGE_MEMBER_ROLE,
            ORG_REMOVE_MEMBER,
            ORG_CREATE_INVITATION,
            ORG_BULK_INVITE,
            ORG_CREATE_DEPARTMENT,
            ORG_UPDATE_DEPARTMENT,
            ORG_DELETE_DEPARTMENT,
            ORG_UPDATE_PROFILE,
            ORG_CONFIGURE_AUTO_JOIN,
            MEAL_UPDATE_POLICY,
            MEAL_MANAGE_POLICY_OVERRIDES,
            // ★위임의 자기 증식 차단 — 위임자는 위임을 줄 수 없다.
            ORG_MANAGE_DELEGATION,
        )

    val ORG_ADMIN_ACTIONS =
        listOf(
            // 멤버 관리
            ORG_LIST_MEMBERS,
            ORG_CHANGE_MEMBER_ROLE,
            ORG_REMOVE_MEMBER,
            ORG_ASSIGN_MEMBER,
            ORG_UPDATE_MEMBER_ATTRIBUTES,
            ORG_READ_MEMBER_HISTORY,
            // 초대
            ORG_CREATE_INVITATION,
            ORG_LIST_INVITATIONS,
            ORG_RESEND_INVITATION,
            ORG_REVOKE_INVITATION,
            ORG_BULK_INVITE,
            // 도메인(자동 가입)
            ORG_LIST_DOMAINS,
            ORG_REGISTER_DOMAIN,
            ORG_VERIFY_DOMAIN,
            ORG_REMOVE_DOMAIN,
            ORG_CONFIGURE_AUTO_JOIN,
            // 조직 구조
            ORG_LIST_DEPARTMENTS,
            ORG_CREATE_DEPARTMENT,
            ORG_UPDATE_DEPARTMENT,
            ORG_DELETE_DEPARTMENT,
            ORG_LIST_SITES,
            ORG_CREATE_SITE,
            ORG_UPDATE_SITE,
            ORG_DELETE_SITE,
            // 프로필·조회
            ORG_UPDATE_PROFILE,
            ORG_READ_AUDIT,
            ORG_READ_DASHBOARD,
            // 부서 위임(부여·회수는 조직관리자만)
            ORG_MANAGE_DELEGATION,
            ORG_LIST_DELEGATIONS,
            // 커스텀 역할(조직 자율)
            ORG_LIST_ROLES,
            ORG_MANAGE_ROLES,
            // 식대 정책(명시 열거 — meal:* 와일드카드 금지. 그러면 meal:Redeem 까지 딸려 온다)
            MEAL_READ_POLICY,
            MEAL_UPDATE_POLICY,
            MEAL_READ_POLICY_HISTORY,
            MEAL_READ_POLICY_OVERRIDES,
            MEAL_MANAGE_POLICY_OVERRIDES,
            // 정산·예측
            BILLING_GENERATE_INVOICE,
            BILLING_READ_INVOICE,
            BILLING_FINALIZE_INVOICE,
            BILLING_READ_SPEND,
            BILLING_RECONCILE,
            FORECAST_READ,
            FORECAST_BACKTEST,
        )

    /**
     * 플랫폼 ADMIN 에게도 **레거시가 주지 않는** action — 브리지의 `*`/`*` 에서 명시 Deny 로 제외한다.
     * 이들은 "역할이 높아서" 얻는 권한이 아니라 **멤버십·기계 신원**에 결속된 능력이기 때문이다:
     *  - `meal:IssueQr` — MealQrService 는 활성 멤버십만 검사(관리자 우회 경로 없음). 허용하면 플랫폼
     *    관리자가 임의 조직 명의로 식권을 발급해 **실지출**을 일으킬 수 있다.
     *  - `meal:Redeem`/`meal:VoidRedeem` — 가맹 M2M(merchant_id 결속) 전용. 세션은 그 체인에 닿지 못한다.
     *  - `consumption:Write` — 생산자 M2M 전용(장부 무결성). 세션 쓰기는 레거시가 명시 거부한다.
     *  - `scim:ManageDirectory` — HR 프로비저닝 M2M 전용.
     */
    val PLATFORM_ADMIN_EXCLUDED_ACTIONS =
        listOf(
            MEAL_ISSUE_QR,
            MEAL_REDEEM,
            MEAL_VOID_REDEEM,
            MEAL_REFUND_REDEEM,
            CONSUMPTION_WRITE,
            SCIM_MANAGE_DIRECTORY,
        )

    /**
     * 관리 콘솔(`/admin` 이하) 표면의 전체 action 집합 — `PlatformSurfaceValidator` 가 선언 검증에 쓰고,
     * `PlatformActionNamespaceTest` 가 다른 집합과의 **교집합이 공집합**임을 고정한다.
     *
     * ★그 disjointness 가 이 설계의 유일한 방어선이다. 브리지의 `orgScoped` 문장은 리소스 글롭
     * `trn:taspa:*:{org}:*` 이 admin TRN(`trn:taspa:platform:{org}:...`)에도 매치하므로, **action 축이
     * 겹치지 않는다는 사실만이** 조직관리자가 admin 조작에 도달하지 못하게 막는다. 여기에 `org:`/`meal:`
     * action 을 넣거나 ORG_ADMIN_ACTIONS 에 `platform:` 을 넣으면 그 순간 격리가 사라진다.
     */
    val PLATFORM_CONSOLE_ACTIONS =
        listOf(
            PLATFORM_ACCESS_CONSOLE,
            PLATFORM_READ_CONSOLE_DASHBOARD,
            PLATFORM_READ_RECONCILIATION,
            PLATFORM_READ_PAYABLES,
            PLATFORM_EXPORT_PAYABLES,
            PLATFORM_READ_UNFINALIZED_INVOICES,
            // 조직
            PLATFORM_LIST_ORGS,
            PLATFORM_READ_ORG,
            PLATFORM_CREATE_ORG,
            PLATFORM_ADMINISTER_ORG,
            PLATFORM_LIST_ORG_MEMBERS,
            PLATFORM_ADD_ORG_MEMBER,
            PLATFORM_CHANGE_ORG_MEMBER_ROLE,
            PLATFORM_REMOVE_ORG_MEMBER,
            PLATFORM_LINK_ORG_SSO,
            // 도메인
            PLATFORM_LIST_ORG_DOMAINS,
            PLATFORM_REGISTER_ORG_DOMAIN,
            PLATFORM_VERIFY_ORG_DOMAIN,
            PLATFORM_FORCE_VERIFY_ORG_DOMAIN,
            PLATFORM_UNVERIFY_ORG_DOMAIN,
            PLATFORM_REMOVE_ORG_DOMAIN,
            // 캘린더
            PLATFORM_LIST_CALENDAR_FEEDS,
            PLATFORM_CREATE_CALENDAR_FEED,
            PLATFORM_UPDATE_CALENDAR_FEED,
            PLATFORM_DELETE_CALENDAR_FEED,
            PLATFORM_READ_CALENDAR_FEED_EVENTS,
            PLATFORM_SYNC_CALENDAR_FEED,
            PLATFORM_IMPORT_CALENDAR_FEED,
            // 사용자
            PLATFORM_SEARCH_USERS,
            PLATFORM_READ_USER,
            PLATFORM_SUSPEND_USER,
            PLATFORM_UNSUSPEND_USER,
            PLATFORM_REVOKE_USER_SESSIONS,
            PLATFORM_CHANGE_USER_ROLE,
            // 클라이언트
            PLATFORM_LIST_CLIENTS,
            PLATFORM_REGISTER_CLIENT,
            PLATFORM_UPDATE_CLIENT,
            PLATFORM_DELETE_CLIENT,
            PLATFORM_REGENERATE_CLIENT_SECRET,
            // 가맹
            PLATFORM_LIST_MERCHANTS,
            PLATFORM_READ_MERCHANT,
            PLATFORM_CREATE_MERCHANT,
            PLATFORM_UPDATE_MERCHANT,
            PLATFORM_DELETE_MERCHANT,
            PLATFORM_LIST_MERCHANT_MEMBERS,
            PLATFORM_GRANT_MERCHANT_MEMBER,
            PLATFORM_REVOKE_MERCHANT_MEMBER,
            // SSO
            PLATFORM_LIST_SSO_CONNECTIONS,
            PLATFORM_READ_SSO_CONNECTION,
            PLATFORM_CREATE_SSO_CONNECTION,
            PLATFORM_UPDATE_SSO_CONNECTION,
            PLATFORM_DELETE_SSO_CONNECTION,
            PLATFORM_SET_SSO_DOMAIN_VERIFIED,
            // 감사
            PLATFORM_READ_GLOBAL_AUDIT,
            // IAM 자체 관리(같은 콘솔 표면이다)
            IAM_LIST_POLICIES,
            IAM_READ_POLICY,
            IAM_CREATE_POLICY,
            IAM_UPDATE_POLICY,
            IAM_DELETE_POLICY,
            IAM_ATTACH_POLICY,
            IAM_DETACH_POLICY,
            IAM_SIMULATE,
            IAM_LIST_GROUPS,
            IAM_CREATE_GROUP,
            IAM_DELETE_GROUP,
            IAM_LIST_GROUP_MEMBERS,
            IAM_ADD_GROUP_MEMBER,
            IAM_REMOVE_GROUP_MEMBER,
            IAM_READ_PRINCIPAL_POLICIES,
            IAM_SET_INLINE_POLICY,
            IAM_REMOVE_INLINE_POLICY,
        )
}
