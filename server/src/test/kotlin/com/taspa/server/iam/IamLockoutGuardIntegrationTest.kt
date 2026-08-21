package com.taspa.server.iam

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.iam.IamInlinePolicyRepository
import com.taspa.server.domain.iam.IamPolicyRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * **IAM 편집이 자신을 잠그지 못한다** — `IamLockoutGuard` 의 회귀 테스트.
 *
 * 이 가드가 없으면 플랫폼 관리자가 `{"Effect":"Deny","Action":"iam:*","Resource":"*"}` 한 줄을 저장하는
 * 순간 **그 정책을 지울 수 있는 사람이 아무도 없다.** 체인의 `hasRole("ADMIN")` 은 심층 방어라 판정을
 * 되돌리지 못하고 복구는 DB 직접 수정뿐이다.
 *
 * ★**순서가 시나리오의 전부다.** 자기 자신을 먼저 막으면 그 다음 요청은 인가 인터셉터에서 **403** 으로
 * 걸려 가드가 아예 돌지 않는다(그것도 옳은 동작이다). 실제 사고는 반대 순서로 일어난다 —
 * "관리자 권한을 좁히자"며 남들을 먼저 막고 **마지막에 자기를 막는** 순간 아무도 남지 않는다.
 * 그 마지막 요청은 요청 시점엔 아직 허용되므로 인터셉터를 통과하고, 가드만이 막을 수 있다.
 *
 * ★그리고 핵심은 "거절된다"가 아니라 **거절 뒤에도 편집이 계속 가능하다**(롤백이 실제로 일어났다)는
 * 것이다. 거절만 확인하면 가드가 트랜잭션을 반쯤 남겨 놓고 실패하는 형태를 놓친다.
 */
class IamLockoutGuardIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var policyRepository: IamPolicyRepository

    @Autowired lateinit var inlineRepository: IamInlinePolicyRepository

    @Autowired lateinit var indexedSessionRepository: FindByIndexNameSessionRepository<out Session>

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "lockout-admin@example.com"
    private val secondAdminEmail = "lockout-admin2@example.com"
    private val password = "SecureP@ssw0rd123"

    /** IAM 관리 능력을 통째로 막는 문서 — 이 한 줄이 복구 경로를 없앤다. */
    private val denyAllIam =
        """{"Version":"2026-07-25","Statement":[{"Sid":"denyIam","Effect":"Deny","Action":"iam:*","Resource":"*"}]}"""

    /** 무해한 문서(대조군) — 가드가 정상 편집까지 막지는 않는지 확인하는 데 쓴다. */
    private val harmless =
        """{"Version":"2026-07-25","Statement":[{"Sid":"allowPing","Effect":"Allow","Action":"ping:Do","Resource":"*"}]}"""

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM iam_policy_attachments")
        jdbcTemplate.update("DELETE FROM iam_group_members")
        jdbcTemplate.update("DELETE FROM iam_inline_policies")
        jdbcTemplate.update("DELETE FROM iam_policies")
        jdbcTemplate.update("DELETE FROM iam_principal_groups")
        listOf(adminEmail, secondAdminEmail).forEach { principal ->
            indexedSessionRepository.findByPrincipalName(principal).keys.forEach {
                indexedSessionRepository.deleteById(it)
            }
        }
        userRepository.deleteAll()
        listOf(adminEmail, secondAdminEmail).forEach { email ->
            userRepository.save(
                User(
                    email = email,
                    passwordHash = passwordEncoder.encode(password),
                    emailVerified = true,
                    role = UserRole.ADMIN.name,
                ),
            )
        }
    }

    private fun adminId(email: String) = userRepository.findByEmail(email)!!.id!!

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

    /** inline 정책을 저장하고 응답 상태를 돌려준다(가드가 거절하면 409). */
    private fun putInline(
        session: WebSession,
        email: String,
        name: String,
        document: String,
    ): Int =
        session
            .perform(
                put("/api/admin/iam/principals/USER/${adminId(email)}/inline/$name")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":${jsonString(document)}}""")
                    .with(csrf()),
            ).andReturn()
            .response.status

    private fun jsonString(raw: String) = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `마지막으로 자기 자신을 막으면 409 로 거절되고 저장되지 않는다`() {
        val admin = login(adminEmail)

        // ① 남(두 번째 관리자)을 먼저 막는다 — 아직 내가 되돌릴 수 있으므로 정당한 운영이다.
        assertThat(putInline(admin, secondAdminEmail, "deny-iam", denyAllIam))
            .`as`("한 명만 막는 것은 허용된다 — 다른 관리자가 되돌릴 수 있다")
            .isEqualTo(204)

        // ② 마지막으로 나를 막는다 → 복구 가능한 사람이 0명 → 가드가 거절.
        admin
            .perform(
                put("/api/admin/iam/principals/USER/${adminId(adminEmail)}/inline/deny-iam")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":${jsonString(denyAllIam)}}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_LOCKOUT"))

        // ★롤백 확인 — 거절된 문서가 남아 있으면 가드가 스스로 락아웃을 만든 셈이다.
        assertThat(
            inlineRepository.findByPrincipalTypeAndPrincipalId(IamPrincipalType.USER, adminId(adminEmail)),
        ).`as`("거절된 inline 정책은 저장되지 않아야 한다").isEmpty()
    }

    @Test
    fun `거절된 뒤에도 IAM 편집은 계속 가능하다(트랜잭션이 실제로 롤백된다)`() {
        val admin = login(adminEmail)
        putInline(admin, secondAdminEmail, "deny-iam", denyAllIam)
        // 자기 자신에 대한 시도는 거절된다(위 테스트).
        putInline(admin, adminEmail, "deny-iam", denyAllIam)

        // 롤백됐으므로 나는 여전히 IAM 을 쓸 수 있다 — 남에게 걸어 둔 Deny 도 되돌릴 수 있다.
        admin
            .perform(
                delete("/api/admin/iam/principals/USER/${adminId(secondAdminEmail)}/inline/deny-iam").with(csrf()),
            ).andExpect(status().isNoContent)
        admin.perform(get("/api/admin/iam/policies")).andExpect(status().isOk)

        // 되돌린 뒤에는 두 번째 관리자도 정상 동작한다.
        login(secondAdminEmail).perform(get("/api/admin/iam/policies")).andExpect(status().isOk)
    }

    @Test
    fun `관리자가 한 명뿐이면 그 한 명을 막는 것도 거절된다`() {
        // 두 번째 관리자를 일반 사용자로 내려 관리자를 한 명으로 만든다.
        val second = userRepository.findByEmail(secondAdminEmail)!!
        second.role = UserRole.USER.name
        userRepository.save(second)

        val admin = login(adminEmail)
        admin
            .perform(
                put("/api/admin/iam/principals/USER/${adminId(adminEmail)}/inline/deny-iam")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":${jsonString(denyAllIam)}}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_LOCKOUT"))
    }

    @Test
    fun `정지된 관리자는 복구 인원으로 세지 않는다`() {
        // 두 번째 관리자를 정지시키면 로그인할 수 없으므로 복구 경로가 아니다.
        val second = userRepository.findByEmail(secondAdminEmail)!!
        second.status = UserStatus.SUSPENDED.name
        userRepository.save(second)

        val admin = login(adminEmail)
        admin
            .perform(
                put("/api/admin/iam/principals/USER/${adminId(adminEmail)}/inline/deny-iam")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":${jsonString(denyAllIam)}}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_LOCKOUT"))
    }

    @Test
    fun `★좁게 겨냥한 Deny 도 잡는다(프로브가 집행과 같은 TRN 을 쓴다)`() {
        val admin = login(adminEmail)
        // Resource 를 principal 컬렉션으로 좁게 겨냥한다 — 예전 프로브(와일드카드 리터럴)는 이걸 못 봤다.
        val narrow =
            """{"Version":"2026-07-25","Statement":[{"Sid":"narrow","Effect":"Deny",""" +
                """"Action":"iam:*","Resource":"trn:taspa:iam::principal/USER/*"}]}"""
        assertThat(putInline(admin, secondAdminEmail, "narrow-deny", narrow)).isEqualTo(204)

        admin
            .perform(
                put("/api/admin/iam/principals/USER/${adminId(adminEmail)}/inline/narrow-deny")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":${jsonString(narrow)}}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_LOCKOUT"))
    }

    @Test
    fun `★그룹 경유 Deny 도 잡는다(그룹 inline·부착·멤버 추가)`() {
        val admin = login(adminEmail)

        // 관리자 전원이 속한 그룹을 만든다.
        val group =
            admin
                .perform(
                    post("/api/admin/iam/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"all-admins"}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val groupId = Regex(""""id":"([0-9a-f-]+)"""").find(group)!!.groupValues[1]
        listOf(adminEmail, secondAdminEmail).forEach { email ->
            admin
                .perform(
                    post("/api/admin/iam/groups/$groupId/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"userId":"${adminId(email)}"}""")
                        .with(csrf()),
                ).andExpect(status().isNoContent)
        }

        // 그 그룹의 inline 에 Deny 를 넣으면 두 관리자 모두 복구 불가 → 거절.
        admin
            .perform(
                put("/api/admin/iam/principals/GROUP/$groupId/inline/deny-iam")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":${jsonString(denyAllIam)}}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_LOCKOUT"))
    }

    @Test
    fun `무해한 정책 편집은 가드에 걸리지 않는다(대조군)`() {
        val admin = login(adminEmail)

        val created =
            admin
                .perform(
                    post("/api/admin/iam/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"harmless","document":${jsonString(harmless)}}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val id = Regex(""""id":"([0-9a-f-]+)"""").find(created)!!.groupValues[1]

        admin
            .perform(
                put("/api/admin/iam/policies/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"description":"d","document":${jsonString(harmless)}}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        admin
            .perform(
                post("/api/admin/iam/attachments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"policyId":"$id","principalType":"USER","principalId":"${adminId(adminEmail)}"}""")
                    .with(csrf()),
            ).andExpect(status().isNoContent)

        admin.perform(delete("/api/admin/iam/policies/$id").with(csrf())).andExpect(status().is2xxSuccessful)
        assertThat(policyRepository.findAll()).`as`("정상 편집은 끝까지 진행된다").isEmpty()
    }
}
