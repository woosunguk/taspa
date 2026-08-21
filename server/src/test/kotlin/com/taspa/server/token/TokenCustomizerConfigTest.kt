package com.taspa.server.token

import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.OrgRoleService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Stage 1 — sub 안정화. 발급 토큰의 sub 이 이메일이 아니라 users.id(UUID)이고, 이메일이 바뀌어도
 * 불변임을 검증한다. userinfo 의 sub 은 SAS 기본 매퍼가 id_token 클레임(sub/preferred_username)을
 * 그대로 옮기므로 id_token 검증으로 대표한다.
 */
class TokenCustomizerConfigTest {
    private val userRepository = mockk<UserRepository>()
    private val orgMembershipRepository = mockk<OrgMembershipRepository>()

    // 기본: 요청된 org id 는 모두 ACTIVE 로 해석(개별 테스트가 SUSPENDED 로 재정의 가능).
    private val organizationRepository =
        mockk<OrganizationRepository>().also {
            every { it.findAllById(any<Iterable<UUID>>()) } answers {
                firstArg<Iterable<UUID>>().map { id -> Organization(id = id, slug = "s", name = "n") }
            }
        }
    private val jwkStorageService =
        mockk<JwkStorageService>().also {
            every { it.activeKid() } returns "test-kid"
        }
    private val orgRoleService =
        mockk<OrgRoleService>().also {
            every { it.roleNamesByOrg(any()) } returns emptyMap()
        }
    private val customizer =
        TokenCustomizerConfig(
            userRepository,
            jwkStorageService,
            orgMembershipRepository,
            organizationRepository,
            orgRoleService,
        ).jwtTokenCustomizer()

    /** 이 클라이언트가 인가에 쓰겠다고 선언한 역할 이름을 담은 등록 클라이언트. null 이면 선언 없음. */
    private fun clientDeclaring(vararg roleNames: String): RegisteredClient {
        val settings = ClientSettings.builder()
        if (roleNames.isNotEmpty()) {
            settings.setting(
                TokenCustomizerConfig.CLIENT_ROLE_NAMES_SETTING,
                TokenCustomizerConfig.formatRoleNames(roleNames.toList()),
            )
        }
        return RegisteredClient
            .withId(UUID.randomUUID().toString())
            .clientId("rp")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://rp.example.com/cb")
            .clientSettings(settings.build())
            .build()
    }

    private fun customizedClaims(
        email: String,
        tokenType: OAuth2TokenType,
        scopes: Set<String> = setOf("openid", "profile", "email"),
        client: RegisteredClient? = null,
    ): JwtClaimsSet {
        val now = Instant.now()
        // JwtGenerator 기본 동작 재현: sub 을 principal name(=이메일)로 미리 채워 둔다.
        val claimsBuilder =
            JwtClaimsSet
                .builder()
                .subject(email)
                .issuer("http://localhost:9100")
                .issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
        val builder =
            JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), claimsBuilder)
                .principal(UsernamePasswordAuthenticationToken(email, "n/a", emptyList()))
                .authorizedScopes(scopes)
                .tokenType(tokenType)
        client?.let { builder.registeredClient(it) }
        val context = builder.build()
        customizer.customize(context)
        return context.claims.build()
    }

    @Test
    fun `id_token sub 은 이메일이 아니라 users_id UUID 이고 email·preferred_username 은 이메일이다`() {
        val id = UUID.randomUUID()
        val email = "user@example.com"
        every { userRepository.findByEmail(email) } returns
            User(id = id, email = email, displayName = "홍길동", emailVerified = true)

        val claims = customizedClaims(email, OAuth2TokenType(OidcParameterNames.ID_TOKEN))

        assertThat(claims.subject).isEqualTo(id.toString())
        assertThat(claims.subject).isNotEqualTo(email)
        assertThat(claims.getClaimAsString("email")).isEqualTo(email)
        assertThat(claims.getClaimAsBoolean("email_verified")).isTrue()
        assertThat(claims.getClaimAsString("preferred_username")).isEqualTo(email)
        assertThat(claims.getClaimAsString("name")).isEqualTo("홍길동")
    }

    @Test
    fun `access_token 은 sub(UUID)만 담고 email 등 PII 는 싣지 않는다`() {
        val id = UUID.randomUUID()
        val email = "access@example.com"
        every { userRepository.findByEmail(email) } returns
            User(id = id, email = email, displayName = "홍길동", emailVerified = true)

        val claims = customizedClaims(email, OAuth2TokenType.ACCESS_TOKEN)

        assertThat(claims.subject).isEqualTo(id.toString())
        // 베어러 access_token 에는 PII 를 넣지 않는다(리소스 서버 로그 유출면 축소) — id_token/userinfo 로만 노출.
        assertThat(claims.claims).doesNotContainKeys("email", "email_verified", "name", "preferred_username")
    }

    @Test
    fun `이메일이 바뀌어도 sub 은 동일한 UUID 로 유지된다`() {
        val id = UUID.randomUUID()
        every { userRepository.findByEmail("old@example.com") } returns
            User(id = id, email = "old@example.com", emailVerified = true)
        // 이메일 변경 후: 같은 users.id, 다른 이메일.
        every { userRepository.findByEmail("new@example.com") } returns
            User(id = id, email = "new@example.com", emailVerified = true)

        val subBefore = customizedClaims("old@example.com", OAuth2TokenType(OidcParameterNames.ID_TOKEN)).subject
        val subAfter = customizedClaims("new@example.com", OAuth2TokenType(OidcParameterNames.ID_TOKEN)).subject

        assertThat(subBefore).isEqualTo(id.toString())
        assertThat(subAfter).isEqualTo(id.toString())
        assertThat(subAfter).isEqualTo(subBefore)
    }

    // ---- Phase 0-C: org 클레임(org.read scope + 멤버십 있을 때만) ----

    @Test
    fun `org_read scope 이고 멤버십이 있으면 access_token 에 org_id·org_role 을 싣는다`() {
        val id = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val email = "org@example.com"
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        every { orgMembershipRepository.findByUserId(id) } returns
            listOf(OrgMembership(orgId = orgId, userId = id, role = "ORG_ADMIN"))

        val claims =
            customizedClaims(
                email,
                OAuth2TokenType.ACCESS_TOKEN,
                scopes = setOf("openid", "org.read"),
            )

        assertThat(claims.getClaimAsString("org_id")).isEqualTo(orgId.toString())
        assertThat(claims.getClaimAsString("org_role")).isEqualTo("ORG_ADMIN")
    }

    @Test
    fun `org_read scope 이지만 멤버십이 없으면 org 클레임을 싣지 않는다`() {
        val id = UUID.randomUUID()
        val email = "noorg@example.com"
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        every { orgMembershipRepository.findByUserId(id) } returns emptyList()

        val claims = customizedClaims(email, OAuth2TokenType.ACCESS_TOKEN, scopes = setOf("openid", "org.read"))

        assertThat(claims.claims).doesNotContainKeys("org_id", "org_role", "orgs")
    }

    @Test
    fun `org_read scope 가 없으면 멤버십이 있어도 org 클레임을 싣지 않는다(최소권한)`() {
        val id = UUID.randomUUID()
        val email = "scoped@example.com"
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        // findByUserId 는 호출되지 않아야 한다(scope 미충족 → 조회 자체를 하지 않음).

        val claims = customizedClaims(email, OAuth2TokenType.ACCESS_TOKEN, scopes = setOf("openid", "profile"))

        assertThat(claims.claims).doesNotContainKeys("org_id", "org_role", "orgs")
    }

    @Test
    fun `SUSPENDED 조직의 멤버십은 org 클레임을 싣지 않는다(정지 강제)`() {
        val id = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val email = "susp@example.com"
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        every { orgMembershipRepository.findByUserId(id) } returns
            listOf(OrgMembership(orgId = orgId, userId = id, role = "MEMBER"))
        // 조직이 SUSPENDED 면 활성 멤버십이어도 org 클레임은 발급되지 않는다.
        every { organizationRepository.findAllById(any<Iterable<UUID>>()) } returns
            listOf(Organization(id = orgId, slug = "s", name = "n", status = "SUSPENDED"))

        val claims = customizedClaims(email, OAuth2TokenType.ACCESS_TOKEN, scopes = setOf("org.read"))

        assertThat(claims.claims).doesNotContainKeys("org_id", "org_role", "orgs")
    }

    @Test
    fun `복수 멤버십이면 orgs 배열과 대표 org_id 를 싣는다`() {
        val id = UUID.randomUUID()
        val org1 = UUID.randomUUID()
        val org2 = UUID.randomUUID()
        val email = "multi@example.com"
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        every { orgMembershipRepository.findByUserId(id) } returns
            listOf(
                OrgMembership(orgId = org1, userId = id, role = "MEMBER", joinedAt = Instant.now().minusSeconds(100)),
                OrgMembership(orgId = org2, userId = id, role = "ORG_ADMIN", joinedAt = Instant.now()),
            )

        val claims = customizedClaims(email, OAuth2TokenType.ACCESS_TOKEN, scopes = setOf("org.read"))

        assertThat(claims.getClaimAsString("org_id")).isEqualTo(org1.toString()) // 첫 활성(가입 순)
        @Suppress("UNCHECKED_CAST")
        val orgs = claims.getClaim<List<Map<String, String>>>("orgs")
        assertThat(orgs).hasSize(2)
        assertThat(orgs.map { it["id"] }).containsExactlyInAnyOrder(org1.toString(), org2.toString())
    }

    // ---- 조직 커스텀 역할 클레임(`org.roles`) ----
    //
    // 이 클레임의 값은 **클라이언트가 선언한 이름 ∩ 사용자가 실제로 가진 이름**이다.
    // 두 축이 각각 fail-closed 인지가 이 묶음의 요점이다.

    private fun userWithRoles(
        email: String,
        orgId: UUID,
        vararg names: String,
    ): UUID {
        val id = UUID.randomUUID()
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        every { orgMembershipRepository.findByUserId(id) } returns
            listOf(
                OrgMembership(orgId = orgId, userId = id, role = "MEMBER", joinedAt = Instant.now()),
            )
        every { orgRoleService.roleNamesByOrg(id) } returns mapOf(orgId to names.toList())
        return id
    }

    @Test
    fun `선언한 이름과 보유 역할의 교집합만 roles 로 실린다`() {
        val org = UUID.randomUUID()
        val email = "role@example.com"
        userWithRoles(email, org, "회계 담당", "인사 담당")

        val claims =
            customizedClaims(
                email,
                OAuth2TokenType.ACCESS_TOKEN,
                scopes = setOf("org.read", "org.roles"),
                client = clientDeclaring("회계 담당", "구매 담당"),
            )

        // 선언했지만 보유하지 않은 "구매 담당", 보유했지만 선언하지 않은 "인사 담당" 은 둘 다 빠진다.
        assertThat(claims.getClaim<List<String>>("roles")).containsExactly("회계 담당")
    }

    @Test
    fun `클라이언트가 역할을 선언하지 않았으면 scope 가 있어도 roles 를 싣지 않는다(fail-closed)`() {
        val org = UUID.randomUUID()
        val email = "undeclared@example.com"
        userWithRoles(email, org, "회계 담당")

        val claims =
            customizedClaims(
                email,
                OAuth2TokenType.ACCESS_TOKEN,
                scopes = setOf("org.read", "org.roles"),
                client = clientDeclaring(),
            )

        assertThat(claims.claims).doesNotContainKey("roles")
    }

    @Test
    fun `org_roles scope 가 없으면 선언·보유가 모두 있어도 싣지 않는다(최소권한)`() {
        val org = UUID.randomUUID()
        val email = "noscope@example.com"
        userWithRoles(email, org, "회계 담당")

        val claims =
            customizedClaims(
                email,
                OAuth2TokenType.ACCESS_TOKEN,
                scopes = setOf("org.read"),
                client = clientDeclaring("회계 담당"),
            )

        assertThat(claims.claims).doesNotContainKey("roles")
    }

    /**
     * ★이 테스트가 이 기능에서 가장 중요하다. `org_id` 와 `roles` 는 **같은 조직**을 가리켜야 한다 —
     * 갈리면 RP 가 org 를 확인하고 역할로 인가하는 순간 **다른 조직의 권한**을 준다.
     */
    @Test
    fun `대표 org 에 해당 역할이 없으면 roles 를 비우고 org_roles 로만 알린다`() {
        val id = UUID.randomUUID()
        val primary = UUID.randomUUID()
        val secondary = UUID.randomUUID()
        val email = "cross@example.com"
        every { userRepository.findByEmail(email) } returns User(id = id, email = email, emailVerified = true)
        every { orgMembershipRepository.findByUserId(id) } returns
            listOf(
                OrgMembership(orgId = primary, userId = id, role = "MEMBER", joinedAt = Instant.now().minusSeconds(100)),
                OrgMembership(orgId = secondary, userId = id, role = "MEMBER", joinedAt = Instant.now()),
            )
        // 역할은 **두 번째** 조직에만 있다.
        every { orgRoleService.roleNamesByOrg(id) } returns mapOf(secondary to listOf("회계 담당"))

        val claims =
            customizedClaims(
                email,
                OAuth2TokenType.ACCESS_TOKEN,
                scopes = setOf("org.read", "org.roles"),
                client = clientDeclaring("회계 담당"),
            )

        assertThat(claims.getClaimAsString("org_id")).isEqualTo(primary.toString())
        assertThat(claims.claims).doesNotContainKey("roles")
        @Suppress("UNCHECKED_CAST")
        val perOrg = claims.getClaim<List<Map<String, Any>>>("org_roles")
        assertThat(perOrg).hasSize(1)
        assertThat(perOrg.first()["org"]).isEqualTo(secondary.toString())
    }

    @Test
    fun `SUSPENDED 조직의 역할은 실리지 않는다(org 클레임과 같은 판정)`() {
        val org = UUID.randomUUID()
        val email = "suspended-role@example.com"
        userWithRoles(email, org, "회계 담당")
        every { organizationRepository.findAllById(any<Iterable<UUID>>()) } returns
            listOf(Organization(id = org, slug = "s", name = "n", status = "SUSPENDED"))

        val claims =
            customizedClaims(
                email,
                OAuth2TokenType.ACCESS_TOKEN,
                scopes = setOf("org.read", "org.roles"),
                client = clientDeclaring("회계 담당"),
            )

        assertThat(claims.claims).doesNotContainKey("roles")
        assertThat(claims.claims).doesNotContainKey("org_id")
    }
}
