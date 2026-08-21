package com.taspa.server.token

import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.OrgRoleService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer

@Configuration
class TokenCustomizerConfig(
    private val userRepository: UserRepository,
    private val jwkStorageService: JwkStorageService,
    private val orgMembershipRepository: OrgMembershipRepository,
    private val organizationRepository: OrganizationRepository,
    private val orgRoleService: OrgRoleService,
) {
    companion object {
        /** 조직 클레임 발급 조건 scope(Phase 0-C). 이 scope 가 인가되지 않으면 org 클레임을 싣지 않는다(최소권한). */
        const val ORG_READ_SCOPE = "org.read"

        /**
         * 생산자(M2M client_credentials) 클라이언트를 조직에 결속하는 클라이언트 설정 키(Phase 0ب-C).
         * 등록 시 이 설정에 org UUID(문자열)를 넣으면, 발급되는 client_credentials 토큰에 org_id 클레임이 실려
         * 소비 이벤트 적재(org 결속 write)가 M2M 로 도달 가능해진다. 미설정이면 org 클레임 없음(비결속·fail-closed).
         */
        const val CLIENT_ORG_ID_SETTING = "settings.client.org-id"

        /**
         * 가맹(POS·CPM 단말) client_credentials 클라이언트를 가맹점에 결속하는 클라이언트 설정 키(식권 L1).
         * 등록 시 merchant UUID(문자열)를 넣으면 발급 토큰에 merchant_id 클레임이 실려 /api/merchant/redeem
         * 인가(가맹 결속)에 도달한다. 미설정이면 클레임 없음(비결속·fail-closed) — org-id 결속과 동일 패턴.
         */
        const val CLIENT_MERCHANT_ID_SETTING = "settings.client.merchant-id"

        /**
         * 조직 **커스텀 역할** 클레임 발급 조건 scope. 인가되지 않으면 `roles` 를 싣지 않는다(최소권한).
         * `org.read`(조직 소속)와 분리한 이유: 소속을 아는 것과 그 조직 안의 역할 구성을 아는 것은
         * 다른 능력이다 — 대부분의 연동 서비스는 앞의 것만 있으면 된다.
         */
        const val ORG_ROLES_SCOPE = "org.roles"

        /**
         * 이 클라이언트가 **인가에 쓰겠다고 선언한 역할 이름** 화이트리스트(쉼표 구분 문자열).
         *
         * ★없으면 `roles` 는 **빈 값이 아니라 미발급**이다(fail-closed). 선언 없이 전부 실어 보내면
         * ① 조직이 정의한 역할 이름 전체가 연동 서비스마다 흘러 조직 구조가 누출되고,
         * ② 조직이 역할 하나를 추가할 때마다 모든 RP 의 인가 입력이 조용히 바뀐다.
         *
         * ★값을 `List` 가 아니라 **쉼표 구분 문자열**로 저장한다. `RegisteredClient` 설정은 SAS 의
         * Jackson(기본 타이핑 활성 + 허용 타입 목록)으로 직렬화되는데, 컬렉션 구현 타입에 따라
         * 역직렬화가 깨질 수 있다 — 기존 org-id·merchant-id 설정이 문자열인 것과 같은 이유다.
         */
        const val CLIENT_ROLE_NAMES_SETTING = "settings.client.role-names"

        /** 설정 문자열 ↔ 이름 목록 변환. 저장·조회 양쪽이 같은 규칙을 쓰도록 여기 한 곳에 둔다. */
        fun parseRoleNames(raw: String?): List<String> =
            raw
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        fun formatRoleNames(names: Collection<String>): String = parseRoleNames(names.joinToString(",")).joinToString(",")
    }

    /**
     * 발급 토큰의 클레임을 scope 에 따라 채운다.
     * - **sub**(id_token·access_token 모두) → users.id(UUID, 안정적 식별자).
     * - **신원(PII) 클레임은 id_token 에만**: email → email/email_verified, profile → name(displayName ?:
     *   이메일 로컬파트) + preferred_username(=이메일). access_token 은 베어러 토큰이라 리소스 서버가 로그에
     *   남길 수 있으므로 email 등 PII 를 싣지 않고 권한 부여에 필요한 sub·scope 만 담는다.
     * - OIDC userinfo 는 SAS 기본 매퍼(`DefaultOidcUserInfoMapper`)가 id_token 클레임을 **요청 scope 기준 표준
     *   클레임 화이트리스트로 필터링**해 파생한다(sub 항상 포함, email/preferred_username 등 표준 클레임 통과).
     *   따라서 PII 를 id_token 에만 실어도 /userinfo 노출은 그대로 유지된다. 다만 비표준 커스텀 클레임을
     *   id_token 에 넣어도 화이트리스트에서 빠져 /userinfo 에는 나타나지 않는다.
     *
     * **sub 안정화(불변식)**: 기본 동작은 sub = principal name(=이메일)이지만, OIDC 규격상 sub 은
     * **불변·재사용 금지**여야 하고 이메일은 변경될 수 있다. 따라서 발급 토큰의 sub 을 이메일과 분리해
     * `users.id`(UUID)로 고정한다 — 이메일이 바뀌어도 sub 은 동일하게 유지된다. principal name(로그인
     * 식별자·세션 인덱스)은 이메일 그대로 두고, 토큰 subject 매핑만 UUID 로 덮어쓴다.
     * **하위호환 주의**: sub 을 계정 키로 쓰던 기존 클라이언트는 이 변경으로 매핑이 바뀐다(docs 참조).
     */
    @Bean
    fun jwtTokenCustomizer(): OAuth2TokenCustomizer<JwtEncodingContext> =
        OAuth2TokenCustomizer { context ->
            // 회전 유예 구간에는 ACTIVE+RETIRED 키가 JWKS 에 공존한다. NimbusJwtEncoder 는 서명 키가
            // 2개 이상 매칭되면 예외를 던지므로 모든 JWT 의 서명 키를 active kid 로 고정한다(필수).
            context.jwsHeader.keyId(jwkStorageService.activeKid())

            val tokenType = context.tokenType.value
            if (tokenType != OidcParameterNames.ID_TOKEN && tokenType != OAuth2TokenType.ACCESS_TOKEN.value) {
                return@OAuth2TokenCustomizer
            }
            val principal = context.getPrincipal<Authentication>()
            val user = userRepository.findByEmail(principal.name)
            val scopes = context.authorizedScopes

            if (user == null) {
                // 사용자로 해석되지 않는 principal = M2M client_credentials(principal.name = client_id).
                // 등록 클라이언트가 org 에 결속돼 있으면 org_id 클레임을 실어 org 결속 write(적재)에 도달 가능하게 한다.
                addClientOrgClaim(context)
                // 가맹 결속(식권 L1): merchant-id 설정이 있으면 merchant_id 클레임을 실어 redeem 인가에 도달하게 한다.
                addClientMerchantClaim(context)
                return@OAuth2TokenCustomizer
            }

            // 신원(PII) 클레임은 id_token 에만 싣는다. access_token 은 베어러 토큰이라 리소스 서버가 로그에
            // 남길 수 있으므로 PII 유출면을 줄인다(userinfo 는 id_token 클레임에서 파생되므로 노출은 유지).
            val isIdToken = tokenType == OidcParameterNames.ID_TOKEN

            context.claims.claims { claims ->
                // sub 을 안정적 UUID(users.id)로 고정 — id_token·access_token 모두. 기본값(이메일)을 덮어쓴다.
                // 영속 계정의 id 는 항상 non-null 이지만, 만일 null 이면 sub 을 건드리지 않아 기존 동작(이메일)을 유지한다.
                user.id?.let { claims["sub"] = it.toString() }
                if (isIdToken && scopes.contains(OidcScopes.EMAIL)) {
                    claims["email"] = user.email
                    claims["email_verified"] = user.emailVerified
                }
                if (isIdToken && scopes.contains(OidcScopes.PROFILE)) {
                    claims["name"] = user.displayName ?: user.email.substringBefore("@")
                    // 사람이 읽는 로그인 식별자. sub(UUID)과 달리 이메일 변경 시 함께 바뀔 수 있다.
                    claims["preferred_username"] = user.email
                }
                // 조직 클레임(Phase 0-C): org.read scope 가 인가되고 사용자에게 활성 멤버십이 있을 때만 발급한다(최소권한).
                // org 정보는 PII 가 아니므로 access_token·id_token 양쪽에 싣는다(리소스 서버가 인가에 사용).
                if (scopes.contains(ORG_READ_SCOPE)) {
                    user.id?.let { addOrgClaims(claims, it) }
                }
                // 커스텀 역할 클레임: scope 인가 + 클라이언트가 선언한 이름과의 교집합일 때만.
                if (scopes.contains(ORG_ROLES_SCOPE)) {
                    user.id?.let { addRoleClaims(claims, it, context) }
                }
            }
        }

    /**
     * 생산자(M2M client_credentials) 토큰에 org 결속 클레임을 싣는다(Phase 0ب-C). 등록 클라이언트의 설정
     * [CLIENT_ORG_ID_SETTING] 에 org UUID 가 있으면 access_token 에 org_id 클레임으로 실어 org 결속 write
     * (소비 이벤트 적재)가 M2M 로 도달 가능해진다. 설정이 없으면 아무 것도 싣지 않는다(비결속·fail-closed).
     */
    private fun addClientOrgClaim(context: JwtEncodingContext) {
        val boundOrgId =
            context.registeredClient
                ?.clientSettings
                ?.getSetting<Any?>(CLIENT_ORG_ID_SETTING)
                ?.toString()
                ?.takeIf { it.isNotBlank() } ?: return
        context.claims.claims { claims -> claims["org_id"] = boundOrgId }
    }

    /**
     * 가맹(M2M client_credentials) 토큰에 merchant 결속 클레임을 싣는다(식권 L1). 등록 클라이언트 설정
     * [CLIENT_MERCHANT_ID_SETTING] 에 merchant UUID 가 있으면 access_token 에 merchant_id 클레임으로 실어
     * /api/merchant/redeem 인가(가맹 결속 + meal.redeem scope)에 도달하게 한다. 없으면 미발급(fail-closed).
     */
    private fun addClientMerchantClaim(context: JwtEncodingContext) {
        val boundMerchantId =
            context.registeredClient
                ?.clientSettings
                ?.getSetting<Any?>(CLIENT_MERCHANT_ID_SETTING)
                ?.toString()
                ?.takeIf { it.isNotBlank() } ?: return
        context.claims.claims { claims -> claims["merchant_id"] = boundMerchantId }
    }

    /**
     * 조직 **커스텀 역할**을 클레임에 채운다 — 연동 서비스(RP)가 taspa 의 조직 역할을 자기 인가에 쓸 수 있게.
     *
     * 발급되는 값은 **클라이언트가 선언한 이름 ∩ 사용자가 실제로 가진 이름**이다. 교집합이 비면 클레임을
     * 넣지 않는다(빈 배열도 아님 — "역할이 없다"와 "이 클라이언트는 역할을 안 본다"를 굳이 구분해
     * 보낼 이유가 없고, 없는 편이 토큰이 작다).
     *
     * 모양은 기존 org 클레임과 **대칭**이다: 대표 org 는 `roles`(문자열 배열), 여러 org 에 걸치면
     * `org_roles`([{org, roles}])를 함께 싣는다. `org_id`/`orgs` 와 같은 규칙이라 RP 가 두 벌의
     * 해석 규칙을 익힐 필요가 없다.
     *
     * ★**대표 org 판정은 [addOrgClaims] 와 같아야 한다** — 다르면 `org_id` 는 A 를 가리키는데 `roles` 는
     * B 의 역할이 되어, RP 가 org 를 확인하고 역할로 인가하는 순간 **다른 조직의 권한을 준다**.
     * 그래서 정렬·필터 규칙을 [activeMembershipsFor] 한 곳으로 모았다.
     *
     * ★**역할 이름은 조직이 정하는 가변 텍스트다.** 조직이 이름을 바꾸면 교집합에서 빠져 RP 는 그 역할을
     * 잃는다 — 닫히는 방향이라 안전하지만, 운영자는 알아야 한다(docs/integration-roles.md).
     */
    private fun addRoleClaims(
        claims: MutableMap<String, Any>,
        userId: java.util.UUID,
        context: JwtEncodingContext,
    ) {
        val declared =
            parseRoleNames(
                context.registeredClient
                    ?.clientSettings
                    ?.getSetting<Any?>(CLIENT_ROLE_NAMES_SETTING)
                    ?.toString(),
            )
        if (declared.isEmpty()) return
        val declaredSet = declared.toSet()

        val held = orgRoleService.roleNamesByOrg(userId)
        if (held.isEmpty()) return

        val active = activeMembershipsFor(userId)
        if (active.isEmpty()) return

        // 조직 순서는 org 클레임과 동일(joinedAt) — 대표 org 가 두 클레임에서 같아야 한다.
        val perOrg =
            active
                .mapNotNull { membership ->
                    val names = held[membership.orgId].orEmpty().filter { it in declaredSet }
                    if (names.isEmpty()) null else membership.orgId to names
                }
        if (perOrg.isEmpty()) return

        val primary = active.first().orgId
        // 대표 org 에 교집합이 없으면 `roles` 는 넣지 않는다 — `org_id` 와 짝이 맞지 않는 값을 그 자리에
        // 넣으면 RP 가 org 를 확인하지 않고 쓰는 순간 다른 조직의 권한이 된다.
        perOrg.firstOrNull { it.first == primary }?.let { claims["roles"] = it.second }
        if (perOrg.size > 1 || perOrg.first().first != primary) {
            claims["org_roles"] = perOrg.map { mapOf("org" to it.first.toString(), "roles" to it.second) }
        }
    }

    /**
     * 사용자의 활성 조직 멤버십을 클레임에 채운다. 멤버십이 없으면 아무 것도 넣지 않는다(발급 안 함).
     * 단일 멤버십이면 org_id(UUID) + org_role, 복수면 orgs:[{id,role}] 배열 + 대표 org_id(첫 활성).
     */
    private fun addOrgClaims(
        claims: MutableMap<String, Any>,
        userId: java.util.UUID,
    ) {
        val active = activeMembershipsFor(userId)
        if (active.isEmpty()) return
        val primary = active.first()
        claims["org_id"] = primary.orgId.toString()
        claims["org_role"] = primary.role
        if (active.size > 1) {
            claims["orgs"] = active.map { mapOf("id" to it.orgId.toString(), "role" to it.role) }
        }
    }

    /**
     * 클레임에 실을 자격이 있는 멤버십 — **org 클레임과 역할 클레임이 공유하는 단일 판정**.
     *
     * 멤버십 ACTIVE ∧ 조직 ACTIVE(★정지된 조직은 클레임을 발급하지 않는다 — 정지 제어에 실효성을 준다),
     * 정렬은 `joinedAt`. 두 클레임이 각자 판정하면 **대표 org 가 갈릴 수 있고**, 그러면 `org_id` 는 A 인데
     * `roles` 는 B 의 역할이 되어 RP 의 인가가 조용히 다른 조직의 것이 된다.
     */
    private fun activeMembershipsFor(userId: java.util.UUID): List<com.taspa.server.domain.org.OrgMembership> {
        val memberships =
            orgMembershipRepository
                .findByUserId(userId)
                .filter { it.statusEnum() == MembershipStatus.ACTIVE }
        if (memberships.isEmpty()) return emptyList()
        val activeOrgIds =
            organizationRepository
                .findAllById(memberships.map { it.orgId })
                .filter { it.statusEnum() == OrgStatus.ACTIVE }
                .mapNotNull { it.id }
                .toSet()
        return memberships.filter { it.orgId in activeOrgIds }.sortedBy { it.joinedAt }
    }
}
