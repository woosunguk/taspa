package com.taspa.server.selfservice

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.device.TrustedDevice
import com.taspa.server.domain.device.TrustedDeviceRepository
import com.taspa.server.domain.federation.FederatedIdentity
import com.taspa.server.domain.federation.FederatedIdentityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.stepup.StepUp
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Stage 2(자기서비스 계정 관리) 통합 테스트: 프로필·이메일 변경·비밀번호 변경/설정·계정 탈퇴.
 * 정상 경로 + step-up 강제 + 세션 폐기 + 감사 기록을 검증한다.
 */
class SelfServiceAccountIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var trustedDeviceRepository: TrustedDeviceRepository

    @Autowired lateinit var federatedIdentityRepository: FederatedIdentityRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var passwordChangeService: PasswordChangeService

    @Autowired lateinit var auditEventService: AuditEventService

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val email = "self@example.com"
    private val password = "SecureP@ssw0rd123"
    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM oauth2_authorization")
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent")
        auditEventRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
        createUser(email, password)
    }

    // ---- 프로필 ----

    @Test
    fun `profile displayName update persists and does not require step-up`() {
        val session = login()
        expireAuthTime(session) // 비파괴 작업이므로 step-up 만료여도 통과해야 한다.

        session
            .perform(
                patch("/api/account/profile")
                    .contentType("application/json")
                    .content("""{"displayName":"새이름"}"""),
            ).andExpect(status().isNoContent)

        assertThat(userRepository.findByEmail(email)!!.displayName).isEqualTo("새이름")
    }

    // ---- 이메일 변경 ----

    @Test
    fun `email change request then confirm switches email, notifies old address and audits`() {
        val session = login()
        val newEmail = "new-self@example.com"

        session
            .perform(jsonPost("/api/account/email/change", """{"newEmail":"$newEmail"}"""))
            .andExpect(status().isAccepted)
        val code = codeSentTo(newEmail)

        session
            .perform(jsonPost("/api/account/email/change/confirm", """{"code":"$code"}"""))
            .andExpect(status().isNoContent)

        // 전환 확인 + sub 안정화 덕에 현재 세션은 그대로 유지된다(새 이메일 principal).
        assertThat(userRepository.findByEmail(newEmail)).isNotNull
        assertThat(userRepository.findByEmail(email)).isNull()
        session.perform(get("/account")).andExpect(status().isOk)

        // 옛 주소로 변경 통지가 발송된다.
        assertThat(sentMessages.any { it.to?.contains(email) == true && it.subject?.contains("변경") == true }).isTrue()
        assertThat(latestAuditType()).isEqualTo("EMAIL_CHANGED")
    }

    @Test
    fun `email change requires recent auth (step-up)`() {
        val session = login()
        expireAuthTime(session)

        session
            .perform(jsonPost("/api/account/email/change", """{"newEmail":"x@example.com"}"""))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    @Test
    fun `email change to an already-used address is rejected`() {
        createUser("taken@example.com", password)
        val session = login()

        session
            .perform(jsonPost("/api/account/email/change", """{"newEmail":"taken@example.com"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"))
    }

    @Test
    fun `email change confirm with wrong code fails and keeps the old email`() {
        val session = login()
        session
            .perform(jsonPost("/api/account/email/change", """{"newEmail":"new2@example.com"}"""))
            .andExpect(status().isAccepted)

        session
            .perform(jsonPost("/api/account/email/change/confirm", """{"code":"000000"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VERIFICATION_CODE_INVALID"))

        assertThat(userRepository.findByEmail(email)).isNotNull
    }

    @Test
    fun `email change confirm revokes other sessions but keeps the current one`() {
        val current = login()
        val other = login()
        // 두 세션 모두 로그인 상태.
        other.perform(get("/account")).andExpect(status().isOk)

        val newEmail = "moved@example.com"
        current
            .perform(jsonPost("/api/account/email/change", """{"newEmail":"$newEmail"}"""))
            .andExpect(status().isAccepted)
        val code = codeSentTo(newEmail)
        current
            .perform(jsonPost("/api/account/email/change/confirm", """{"code":"$code"}"""))
            .andExpect(status().isNoContent)

        // 현재 세션은 유지(새 이메일), 다른 세션은 폐기(옛 이메일 인덱스).
        current.perform(get("/account")).andExpect(status().isOk)
        other.perform(get("/account")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `email change purges third-party authorizations and consents keyed by the old email`() {
        val session = login()
        // 옛 이메일로 키잉된 제3자 권한부여·동의 시딩(연결앱).
        seedAuthorization(email, "some-client")
        seedConsent(email, "some-client")
        assertThat(countAuthorizations(email)).isEqualTo(1)
        assertThat(countConsents(email)).isEqualTo(1)

        val newEmail = "purged@example.com"
        session
            .perform(jsonPost("/api/account/email/change", """{"newEmail":"$newEmail"}"""))
            .andExpect(status().isAccepted)
        val code = codeSentTo(newEmail)
        session
            .perform(jsonPost("/api/account/email/change/confirm", """{"code":"$code"}"""))
            .andExpect(status().isNoContent)

        // 옛 이메일 고아 행이 남지 않는다(연결앱 관리 불가·탈퇴 후 PII/토큰 잔존·교차계정 발급 차단).
        assertThat(countAuthorizations(email)).isZero()
        assertThat(countConsents(email)).isZero()
        // 새 이메일로 이관하지 않고 폐기한다(재동의 요구).
        assertThat(countAuthorizations(newEmail)).isZero()
        assertThat(countConsents(newEmail)).isZero()
        // 연결된 앱 목록도 새 이메일 principal 로 비어 있다.
        val listed =
            objectMapper.readTree(
                session
                    .perform(get("/api/account/authorized-clients"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(listed).isEmpty()
    }

    // ---- 비밀번호 변경/설정 ----

    @Test
    fun `password change updates hash, revokes all sessions and trusted devices, audits`() {
        val session = login()
        val user = userRepository.findByEmail(email)!!
        // 신뢰 기기 1대 시딩.
        trustedDeviceRepository.save(
            TrustedDevice(
                userId = user.id!!,
                tokenHash = SecureTokenGenerator.hashToken("td-token"),
                uaLabel = "Chrome",
                expiresAt = Instant.now().plus(Duration.ofDays(30)),
            ),
        )
        val newPassword = "N3wP@ssw0rd!xyz"

        session
            .perform(
                jsonPost("/api/account/password", """{"currentPassword":"$password","newPassword":"$newPassword"}"""),
            ).andExpect(status().isNoContent)

        assertThat(passwordEncoder.matches(newPassword, userRepository.findByEmail(email)!!.passwordHash)).isTrue()
        assertThat(trustedDeviceRepository.findByUserId(user.id!!)).isEmpty()
        // 현재 세션도 폐기 → 보호 페이지 접근이 로그인으로 되돌려진다.
        session.perform(get("/account")).andExpect(status().is3xxRedirection)
        assertThat(auditEventRepository.findByTypeOrderByCreatedAtDesc("PASSWORD_CHANGED", PageRequest.of(0, 1))).isNotEmpty
    }

    @Test
    fun `password change with wrong current password is rejected`() {
        val session = login()

        session
            .perform(
                jsonPost("/api/account/password", """{"currentPassword":"WrongP@ss1","newPassword":"N3wP@ssw0rd!xyz"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("CURRENT_PASSWORD_INCORRECT"))
    }

    @Test
    fun `password change requires recent auth (step-up)`() {
        val session = login()
        expireAuthTime(session)

        session
            .perform(
                jsonPost("/api/account/password", """{"currentPassword":"$password","newPassword":"N3wP@ssw0rd!xyz"}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    @Test
    fun `social-only account can set a password without a current password`() {
        val social =
            userRepository.save(
                User(email = "social@example.com", passwordHash = null, emailVerified = true),
            )
        val newPassword = "S3tP@ssw0rd!xyz"

        // step-up 은 컨트롤러 관심사 — 서비스는 현재 비밀번호 없이 최초 설정을 허용해야 한다.
        passwordChangeService.changePassword(social.id!!, null, newPassword)

        assertThat(passwordEncoder.matches(newPassword, userRepository.findById(social.id!!).get().passwordHash)).isTrue()
    }

    // ---- 계정 탈퇴 ----

    @Test
    fun `account deletion removes user, cascades data, purges authorizations and audits with hashed email`() {
        val session = login()
        val user = userRepository.findByEmail(email)!!
        federatedIdentityRepository.save(
            FederatedIdentity(userId = user.id!!, provider = "google", providerUserId = "g-1", emailAtLink = email),
        )
        seedAuthorization(user.email, "some-client")

        session
            .perform(delete("/api/account").contentType("application/json").content("""{"email":"$email"}"""))
            .andExpect(status().isNoContent)

        assertThat(userRepository.findByEmail(email)).isNull()
        assertThat(federatedIdentityRepository.findByUserId(user.id!!)).isEmpty()
        assertThat(countAuthorizations(email)).isZero()

        val audit = auditEventRepository.findByTypeOrderByCreatedAtDesc("ACCOUNT_DELETED", PageRequest.of(0, 1)).single()
        assertThat(audit.detail).contains(SecureTokenGenerator.hashToken(email))
        assertThat(audit.detail).doesNotContain(email) // PII 미보존.
    }

    @Test
    fun `deleted account cannot log in again but the same email can re-register`() {
        val session = login()
        session
            .perform(delete("/api/account").contentType("application/json").content("""{"email":"$email"}"""))
            .andExpect(status().isNoContent)

        // 재로그인 불가: 동일 자격으로 로그인해도 보호 페이지에 접근할 수 없다.
        val relogin = webSession()
        relogin.perform(post("/login/identifier").param("email", email).with(csrf()))
        relogin.perform(
            post("/login/password").param("username", email).param("password", password).with(csrf()),
        )
        relogin.perform(get("/account")).andExpect(status().is3xxRedirection)

        // 동일 이메일 재가입 가능(하드삭제로 이메일이 해방됨).
        mockMvc
            .perform(
                post("/api/accounts/signup")
                    .contentType("application/json")
                    .content("""{"email":"$email","password":"$password"}"""),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `account deletion requires matching email confirmation`() {
        val session = login()

        session
            .perform(delete("/api/account").contentType("application/json").content("""{"email":"wrong@example.com"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("CONFIRMATION_MISMATCH"))

        assertThat(userRepository.findByEmail(email)).isNotNull
    }

    @Test
    fun `account deletion requires recent auth (step-up)`() {
        val session = login()
        expireAuthTime(session)

        session
            .perform(delete("/api/account").contentType("application/json").content("""{"email":"$email"}"""))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    @Test
    fun `account deletion anonymizes prior audit detail so no plaintext email remains`() {
        val session = login()
        val user = userRepository.findByEmail(email)!!
        // 선행 감사 이벤트(평문 이메일 포함)를 시딩 — 실제로는 이메일 인증/비번 변경 등에서 남는다.
        auditEventService.record("EMAIL_VERIFICATION_SENT", user.id, mapOf("email" to email))
        auditEventService.record("PASSWORD_CHANGED", user.id, mapOf("email" to email))
        // 사전 조건: 이 user_id 의 감사 detail 에 평문 이메일이 실제로 존재한다.
        assertThat(auditEventRepository.findAll().any { it.userId == user.id && it.detail?.contains(email) == true })
            .isTrue()

        session
            .perform(delete("/api/account").contentType("application/json").content("""{"email":"$email"}"""))
            .andExpect(status().isNoContent)

        // 하드삭제 후: 이 user_id 의 어떤 선행 감사 detail 에도 평문 이메일이 남지 않는다.
        assertThat(auditEventRepository.findAll().none { it.userId == user.id && it.detail?.contains(email) == true })
            .isTrue()
        // ACCOUNT_DELETED 는 해시만 담아 유지된다(재식별 불가 감사).
        val deleted = auditEventRepository.findByTypeOrderByCreatedAtDesc("ACCOUNT_DELETED", PageRequest.of(0, 1)).single()
        assertThat(deleted.detail).contains(SecureTokenGenerator.hashToken(email)).doesNotContain(email)
    }

    // ---- helpers ----

    private fun createUser(
        userEmail: String,
        userPassword: String,
    ): User =
        userRepository.save(
            User(email = userEmail, passwordHash = passwordEncoder.encode(userPassword), emailVerified = true),
        )

    private fun login(): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(
                post("/login/password").param("username", email).param("password", password).with(csrf()),
            ).andExpect(status().is3xxRedirection)
        return session
    }

    private fun jsonPost(
        url: String,
        body: String,
    ) = post(url).contentType("application/json").content(body)

    private fun expireAuthTime(session: WebSession) {
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
    }

    private fun codeSentTo(target: String): String {
        val message = sentMessages.last { it.to?.contains(target) == true }
        return Regex("\\b\\d{6}\\b").find(message.text ?: "")!!.value
    }

    private fun latestAuditType(): String = auditEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1)).single().type

    private fun seedAuthorization(
        principal: String,
        clientId: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type) " +
                "VALUES (?, ?, ?, ?)",
            UUID.randomUUID().toString(),
            clientId,
            principal,
            "authorization_code",
        )
    }

    private fun countAuthorizations(principal: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?",
            Int::class.java,
            principal,
        ) ?: 0

    private fun seedConsent(
        principal: String,
        clientId: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) " +
                "VALUES (?, ?, ?)",
            clientId,
            principal,
            "SCOPE_openid",
        )
    }

    private fun countConsents(principal: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE principal_name = ?",
            Int::class.java,
            principal,
        ) ?: 0
}
