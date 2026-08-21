package com.taspa.server.admin

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.TokenCustomizerConfig
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID

/**
 * 관리자 콘솔(Stage A) 통합 테스트: 역할 기반 접근 제어 / 클라이언트 CRUD(secret 1회 노출·bcrypt
 * 저장·SAS 역직렬화 복원·삭제 시 인가 행 정리) / 사용자 정지(세션 즉시 폐기 + 로그인 차단) /
 * 자기 보호 가드(409) / admin API CSRF 보호.
 */
class AdminConsoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var indexedSessionRepository: FindByIndexNameSessionRepository<out Session>

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "console-admin@example.com"
    private val memberEmail = "console-member@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        loginEventRepository.deleteAll()
        listOf(adminEmail, memberEmail).forEach { principal ->
            indexedSessionRepository.findByPrincipalName(principal).keys.forEach {
                indexedSessionRepository.deleteById(it)
            }
        }
        jdbcTemplate.update("DELETE FROM oauth2_authorization")
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent")
        jdbcTemplate.update("DELETE FROM oauth2_registered_client")
        userRepository.deleteAll()
        userRepository.save(
            User(
                email = adminEmail,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = UserRole.ADMIN.name,
            ),
        )
        userRepository.save(
            User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `admin pages and APIs are 403 for regular users and 200 for admins`() {
        val memberSession = login(memberEmail)
        memberSession.perform(get("/admin")).andExpect(status().isForbidden)
        memberSession.perform(get("/admin/clients")).andExpect(status().isForbidden)
        memberSession.perform(get("/api/admin/clients")).andExpect(status().isForbidden)
        memberSession.perform(get("/api/admin/users")).andExpect(status().isForbidden)
        memberSession
            .perform(post("/api/admin/users/${memberId()}/suspend").with(csrf()))
            .andExpect(status().isForbidden)

        val adminSession = login(adminEmail)
        adminSession.perform(get("/admin")).andExpect(status().isOk)
        adminSession.perform(get("/admin/clients")).andExpect(status().isOk)
        adminSession.perform(get("/admin/users")).andExpect(status().isOk)
        adminSession.perform(get("/admin/audit")).andExpect(status().isOk)
        adminSession.perform(get("/api/admin/clients")).andExpect(status().isOk)
        adminSession.perform(get("/api/admin/users")).andExpect(status().isOk)
        adminSession.perform(get("/api/admin/audit")).andExpect(status().isOk)

        // 미인증은 로그인 진입점으로 유도된다(페이지 접근 거부 처리).
        mockMvc.perform(get("/admin")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `confidential client registration exposes secret once and stores bcrypt hash`() {
        val adminSession = login(adminEmail)
        val body =
            adminSession
                .perform(
                    post("/api/admin/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"clientId":"acme-app","clientName":"Acme App",
                             "redirectUris":["https://acme.example.com/callback"],
                             "postLogoutRedirectUris":["https://acme.example.com/"],
                             "scopes":["openid","profile","email"],
                             "grantTypes":["authorization_code","refresh_token"],
                             "publicClient":false}
                            """.trimIndent(),
                        ).with(csrf()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.clientSecret").isNotEmpty)
                .andExpect(jsonPath("$.client.clientId").value("acme-app"))
                .andReturn()
                .response.contentAsString
        val rawSecret = Regex(""""clientSecret":"([^"]+)"""").find(body)!!.groupValues[1]

        // 저장은 접두사 없는 bcrypt 해시(공유 BCryptPasswordEncoder 빈)이며 원문이 아니다.
        // 인코더 접두사를 붙이면 SAS 토큰 엔드포인트가 공유 BCryptPasswordEncoder 로 검증할 때
        // 매칭이 실패하므로(invalid_client), matches 가 참인 것이 실제 인증 성립의 불변식이다.
        val stored =
            jdbcTemplate.queryForObject(
                "SELECT client_secret FROM oauth2_registered_client WHERE client_id = 'acme-app'",
                String::class.java,
            )!!
        assertThat(stored).doesNotStartWith("{")
        assertThat(stored).doesNotContain(rawSecret)
        assertThat(passwordEncoder.matches(rawSecret, stored)).isTrue()

        // 목록 조회는 secret 을 다시 노출하지 않는다(1회 노출 계약).
        adminSession
            .perform(get("/api/admin/clients"))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString(rawSecret))))

        // SAS 역직렬화(findByClientId) 복원값이 등록 요청과 일치한다.
        val restored = registeredClientRepository.findByClientId("acme-app")!!
        assertThat(restored.clientName).isEqualTo("Acme App")
        assertThat(restored.redirectUris).containsExactly("https://acme.example.com/callback")
        assertThat(restored.postLogoutRedirectUris).containsExactly("https://acme.example.com/")
        assertThat(restored.scopes).containsExactlyInAnyOrder("openid", "profile", "email")
        assertThat(restored.authorizationGrantTypes.map { it.value })
            .containsExactlyInAnyOrder("authorization_code", "refresh_token")
        assertThat(restored.clientAuthenticationMethods)
            .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        assertThat(restored.clientSettings.isRequireProofKey).isTrue()
        assertThat(restored.clientSettings.isRequireAuthorizationConsent).isTrue()
        assertThat(restored.tokenSettings.accessTokenTimeToLive).isEqualTo(Duration.ofMinutes(15))
        assertThat(restored.tokenSettings.refreshTokenTimeToLive).isEqualTo(Duration.ofDays(30))
        assertThat(restored.tokenSettings.isReuseRefreshTokens).isFalse()
    }

    @Test
    fun `redirect uri with dangerous scheme is rejected`() {
        val adminSession = login(adminEmail)
        // javascript:/data: 는 URI.isAbsolute 를 통과하지만 스킴 화이트리스트(http/https)가 막는다.
        for (uri in listOf("javascript:alert(1)", "data:text/html,x")) {
            adminSession
                .perform(
                    post("/api/admin/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"clientId":"evil-app","clientName":"Evil App",
                             "redirectUris":["$uri"],
                             "scopes":["openid"],
                             "grantTypes":["authorization_code"],
                             "publicClient":false}
                            """.trimIndent(),
                        ).with(csrf()),
                ).andExpect(status().isBadRequest)
        }
        assertThat(registeredClientRepository.findByClientId("evil-app")).isNull()
    }

    @Test
    fun `public client is registered without secret and with token endpoint auth method none`() {
        val adminSession = login(adminEmail)
        adminSession
            .perform(
                post("/api/admin/clients")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientId":"spa-app","clientName":"SPA App",
                         "redirectUris":["https://spa.example.com/callback"],
                         "scopes":["openid","email"],
                         "grantTypes":["authorization_code","refresh_token"],
                         "publicClient":true}
                        """.trimIndent(),
                    ).with(csrf()),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.clientSecret").value(nullValue()))
            .andExpect(jsonPath("$.client.publicClient").value(true))

        val restored = registeredClientRepository.findByClientId("spa-app")!!
        assertThat(restored.clientAuthenticationMethods).containsExactly(ClientAuthenticationMethod.NONE)
        assertThat(restored.clientSecret).isNull()
        assertThat(restored.clientSettings.isRequireProofKey).isTrue()

        // 공개 클라이언트는 secret 재발급 불가(409).
        adminSession
            .perform(post("/api/admin/clients/${restored.id}/secret").with(csrf()))
            .andExpect(status().isConflict)
    }

    @Test
    fun `client update changes name uris scopes and keeps clientId and secret`() {
        val adminSession = login(adminEmail)
        val secretBefore = registerClient(adminSession, "update-app")
        val id = registeredClientRepository.findByClientId("update-app")!!.id

        adminSession
            .perform(
                put("/api/admin/clients/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientName":"Updated Name",
                         "redirectUris":["https://updated.example.com/cb"],
                         "postLogoutRedirectUris":[],
                         "scopes":["openid"]}
                        """.trimIndent(),
                    ).with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.clientName").value("Updated Name"))

        val restored = registeredClientRepository.findByClientId("update-app")!!
        assertThat(restored.id).isEqualTo(id)
        assertThat(restored.clientName).isEqualTo("Updated Name")
        assertThat(restored.redirectUris).containsExactly("https://updated.example.com/cb")
        assertThat(restored.scopes).containsExactly("openid")
        // 유형·secret 은 그대로(수정 범위 밖).
        assertThat(passwordEncoder.matches(secretBefore, restored.clientSecret!!)).isTrue()
    }

    /**
     * 선언 역할 이름(`roles` 클레임의 한쪽 축)이 등록·수정에서 살아남는지.
     *
     * ★핵심은 **다른 항목만 고친 수정이 결속 설정을 지우지 않는 것**이다. `clientSettings(...)` 는 설정
     * 맵을 통째로 교체하므로, 새로 만들어 넘기면 org-id·merchant-id 결속이 조용히 사라진다 —
     * 클라이언트 **이름만 바꿨는데 POS 단말이 결제하지 못하게** 되고 화면 어디에도 이유가 없다.
     */
    @Test
    fun `선언 역할 이름은 저장·수정되고, 이름만 고친 수정은 기존 결속을 지우지 않는다`() {
        val adminSession = login(adminEmail)
        adminSession
            .perform(
                post("/api/admin/clients")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientId":"roles-app","clientName":"Roles App",
                         "redirectUris":["https://roles.example.com/cb"],
                         "scopes":["openid","org.read","org.roles"],
                         "grantTypes":["authorization_code"],
                         "roleNames":["회계 담당"," 인사 담당 ",""]}
                        """.trimIndent(),
                    ).with(csrf()),
            ).andExpect(status().isCreated)
            // 공백·빈 값은 정규화되고 순서는 보존된다.
            .andExpect(jsonPath("$.client.roleNames[0]").value("회계 담당"))
            .andExpect(jsonPath("$.client.roleNames[1]").value("인사 담당"))
            .andExpect(jsonPath("$.client.roleNames.length()").value(2))

        val id = registeredClientRepository.findByClientId("roles-app")!!.id
        // 결속 설정이 있다고 가정하고 직접 심는다(등록 API 의 org/merchant 결속과 같은 자리).
        val seeded =
            RegisteredClient
                .from(registeredClientRepository.findByClientId("roles-app")!!)
                .clientSettings(
                    ClientSettings
                        .withSettings(
                            registeredClientRepository
                                .findByClientId("roles-app")!!
                                .clientSettings.settings
                                .toMutableMap()
                                .also { it[TokenCustomizerConfig.CLIENT_ORG_ID_SETTING] = "org-marker" },
                        ).build(),
                ).build()
        registeredClientRepository.save(seeded)

        // roleNames 를 보내지 않는 수정 = 미전송 → 기존 선언 유지 + 결속도 유지.
        adminSession
            .perform(
                put("/api/admin/clients/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientName":"Renamed",
                         "redirectUris":["https://roles.example.com/cb"],
                         "postLogoutRedirectUris":[],
                         "scopes":["openid","org.read","org.roles"]}
                        """.trimIndent(),
                    ).with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.roleNames.length()").value(2))

        val afterRename = registeredClientRepository.findByClientId("roles-app")!!
        assertThat(afterRename.clientSettings.getSetting<Any?>(TokenCustomizerConfig.CLIENT_ORG_ID_SETTING))
            .isEqualTo("org-marker")

        // 빈 목록은 **선언 해제**다(미전송과 다르다) — 그래도 결속은 남는다.
        adminSession
            .perform(
                put("/api/admin/clients/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientName":"Renamed",
                         "redirectUris":["https://roles.example.com/cb"],
                         "postLogoutRedirectUris":[],
                         "scopes":["openid"],
                         "roleNames":[]}
                        """.trimIndent(),
                    ).with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.roleNames.length()").value(0))

        val afterClear = registeredClientRepository.findByClientId("roles-app")!!
        assertThat(afterClear.clientSettings.getSetting<Any?>(TokenCustomizerConfig.CLIENT_ORG_ID_SETTING))
            .isEqualTo("org-marker")
    }

    @Test
    fun `client deletion removes issued authorization and consent rows`() {
        val adminSession = login(adminEmail)
        registerClient(adminSession, "doomed-app")
        val id = registeredClientRepository.findByClientId("doomed-app")!!.id
        jdbcTemplate.update(
            "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type) " +
                "VALUES (?, ?, ?, ?)",
            UUID.randomUUID().toString(),
            id,
            memberEmail,
            "authorization_code",
        )
        jdbcTemplate.update(
            "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) " +
                "VALUES (?, ?, ?)",
            id,
            memberEmail,
            "SCOPE_openid",
        )

        adminSession
            .perform(delete("/api/admin/clients/$id").with(csrf()))
            .andExpect(status().isNoContent)

        assertThat(registeredClientRepository.findByClientId("doomed-app")).isNull()
        assertThat(countWhere("oauth2_authorization", id)).isZero()
        assertThat(countWhere("oauth2_authorization_consent", id)).isZero()
    }

    @Test
    fun `suspending a user revokes sessions immediately and blocks login until unsuspended`() {
        val adminSession = login(adminEmail)
        val targetSession = login(memberEmail)
        targetSession.perform(get("/account")).andExpect(status().isOk)

        adminSession
            .perform(post("/api/admin/users/${memberId()}/suspend").with(csrf()))
            .andExpect(status().isNoContent)

        // 상태 전환 + 전 세션 즉시 폐기 → 기존 세션 쿠키는 미인증 처리된다.
        assertThat(userRepository.findByEmail(memberEmail)!!.status).isEqualTo(UserStatus.SUSPENDED.name)
        assertThat(indexedSessionRepository.findByPrincipalName(memberEmail)).isEmpty()
        targetSession.perform(get("/account")).andExpect(status().is3xxRedirection)

        // 재로그인도 기존 SUSPENDED 경로(UserDetails.disabled)로 차단된다.
        val retry = webSession()
        retry
            .perform(post("/login/identifier").param("email", memberEmail).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        retry
            .perform(
                post("/login/password").param("username", memberEmail).param("password", password).with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", startsWith("/login")))

        // 해제 후에는 정상 로그인된다.
        adminSession
            .perform(post("/api/admin/users/${memberId()}/unsuspend").with(csrf()))
            .andExpect(status().isNoContent)
        login(memberEmail)
    }

    @Test
    fun `self suspension and self demotion are rejected with 409`() {
        val adminSession = login(adminEmail)
        val adminId = userRepository.findByEmail(adminEmail)!!.id!!

        adminSession
            .perform(post("/api/admin/users/$adminId/suspend").with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ADMIN_SELF_ACTION"))
        adminSession
            .perform(
                post("/api/admin/users/$adminId/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"USER"}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ADMIN_SELF_ACTION"))

        val admin = userRepository.findByEmail(adminEmail)!!
        assertThat(admin.status).isEqualTo(UserStatus.ACTIVE.name)
        assertThat(admin.role).isEqualTo(UserRole.ADMIN.name)
    }

    @Test
    fun `role change takes effect on next login and demotion revokes sessions immediately`() {
        val adminSession = login(adminEmail)
        adminSession
            .perform(
                post("/api/admin/users/${memberId()}/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isNoContent)

        // 새 로그인부터 ROLE_ADMIN 이 부여된다.
        val promotedSession = login(memberEmail)
        promotedSession.perform(get("/admin")).andExpect(status().isOk)

        // 강등은 세션에 남은 ROLE_ADMIN 을 걷어내기 위해 전 세션을 즉시 폐기한다.
        adminSession
            .perform(
                post("/api/admin/users/${memberId()}/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"USER"}""")
                    .with(csrf()),
            ).andExpect(status().isNoContent)
        promotedSession.perform(get("/admin")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `admin mutation API without csrf token is rejected`() {
        val adminSession = login(adminEmail)
        adminSession
            .perform(post("/api/admin/users/${memberId()}/suspend"))
            .andExpect(status().isForbidden)
        assertThat(userRepository.findByEmail(memberEmail)!!.status).isEqualTo(UserStatus.ACTIVE.name)
    }

    @Test
    fun `user search and detail expose account overview and audit filters work`() {
        val adminSession = login(adminEmail)
        val memberSession = login(memberEmail)
        memberSession.perform(get("/account")).andExpect(status().isOk)

        adminSession
            .perform(get("/api/admin/users").param("query", "console-member"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].email").value(memberEmail))

        adminSession
            .perform(get("/api/admin/users/${memberId()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user.email").value(memberEmail))
            .andExpect(jsonPath("$.passkeyCount").value(0))
            .andExpect(jsonPath("$.activeSessionCount").value(1))

        adminSession
            .perform(post("/api/admin/users/${memberId()}/sessions/revoke").with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(indexedSessionRepository.findByPrincipalName(memberEmail)).isEmpty()

        // 감사 로그 필터: type + email(→userId 해석) 조합.
        adminSession
            .perform(
                get("/api/admin/audit")
                    .param("type", "ADMIN_USER_SESSIONS_REVOKED")
                    .param("email", memberEmail),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].type").value("ADMIN_USER_SESSIONS_REVOKED"))
            .andExpect(jsonPath("$[0].email").value(memberEmail))

        // 대시보드는 감사 이벤트가 존재하는 상태에서도 렌더링된다(최근 이벤트 테이블의 시각 포맷 포함).
        adminSession
            .perform(get("/admin"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("ADMIN_USER_SESSIONS_REVOKED")))
    }

    // ---- helpers ----

    private fun memberId(): UUID = userRepository.findByEmail(memberEmail)!!.id!!

    private fun registerClient(
        session: WebSession,
        clientId: String,
    ): String {
        val body =
            session
                .perform(
                    post("/api/admin/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"clientId":"$clientId","clientName":"$clientId",
                             "redirectUris":["https://$clientId.example.com/cb"],
                             "scopes":["openid","email"],
                             "grantTypes":["authorization_code"],
                             "publicClient":false}
                            """.trimIndent(),
                        ).with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return Regex(""""clientSecret":"([^"]+)"""").find(body)!!.groupValues[1]
    }

    private fun countWhere(
        table: String,
        registeredClientId: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $table WHERE registered_client_id = ?",
            Int::class.javaObjectType,
            registeredClientId,
        )!!

    private fun login(email: String): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password").param("username", email).param("password", password).with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        return session
    }
}
