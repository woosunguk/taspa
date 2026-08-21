package com.taspa.server.federation

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.federation.FederatedIdentity
import com.taspa.server.domain.federation.FederatedIdentityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
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
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URLDecoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as httpGet
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as httpPost

/**
 * 소셜 로그인 플로우 통합 테스트. 공급자(token/userinfo)는 WireMock 으로 스텁하고,
 * /oauth2/authorization/{id} → state 추출 → 같은 세션으로 콜백까지 전체 플로우를 태운다.
 */
class SocialLoginFlowIntegrationTest : IntegrationTestBase() {
    companion object {
        @JvmStatic
        val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort()).apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun socialProperties(registry: DynamicPropertyRegistry) {
            registry.add("KAKAO_CLIENT_ID") { "kakao-test-client" }
            registry.add("KAKAO_CLIENT_SECRET") { "kakao-test-secret" }
            registry.add("NAVER_CLIENT_ID") { "naver-test-client" }
            registry.add("NAVER_CLIENT_SECRET") { "naver-test-secret" }
            registry.add("taspa.social.kakao.token-uri") { "${wireMock.baseUrl()}/kakao/token" }
            registry.add("taspa.social.kakao.user-info-uri") { "${wireMock.baseUrl()}/kakao/userinfo" }
            registry.add("taspa.social.naver.token-uri") { "${wireMock.baseUrl()}/naver/token" }
            registry.add("taspa.social.naver.user-info-uri") { "${wireMock.baseUrl()}/naver/userinfo" }
        }
    }

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var federatedIdentityRepository: FederatedIdentityRepository

    @Autowired lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        federatedIdentityRepository.deleteAll()
        emailVerificationCodeRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
        wireMock.resetAll()
    }

    // ---- 플로우 1: 공급자·로컬 이메일 둘 다 검증됨 → 자동 연결 + 로그인 ----

    @Test
    fun `kakao login with verified email auto-links to verified local account`() {
        val email = "social-auto@example.com"
        val user = createLocalUser(email, emailVerified = true)
        stubKakao(id = 1001, email = email, emailValid = true, emailVerified = true)

        val session = webSession()
        socialCallback(session, "kakao")
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))

        val identity = federatedIdentityRepository.findByProviderAndProviderUserId("kakao", "1001")
        assertThat(identity).isNotNull
        assertThat(identity!!.userId).isEqualTo(user.id)
        // 완전 인증 성립 — 보호 자원 접근 가능.
        session.perform(httpGet("/account")).andExpect(status().isOk)
    }

    // ---- 플로우 2: 네이버(항상 미검증) → 기존 계정 확인(link-confirm) 게이트 ----

    @Test
    fun `naver login with existing local account requires email code before linking`() {
        val email = "social-link@example.com"
        val user = createLocalUser(email, emailVerified = true)
        stubNaver(id = "naver-abc", email = email, name = "홍길동")

        val session = webSession()
        socialCallback(session, "naver")
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/link-confirm"))

        // 부분 인증 상태: 연결도, 로그인도 아직 아니다.
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId("naver", "naver-abc")).isNull()
        session.perform(httpGet("/account")).andExpect(status().is3xxRedirection)

        // 메일로 받은 코드 제출 → 연결 + 완전 인증. (직전 /account 시도가 saved request 로 남아
        // completeAuthentication 이 그곳(?continue 포함)으로 복귀시킨다.)
        val redirected =
            session
                .perform(
                    httpPost("/login/link-confirm").param("code", latestCode()).with(csrf()),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
                .response.redirectedUrl
        assertThat(redirected).contains("/account")

        val identity = federatedIdentityRepository.findByProviderAndProviderUserId("naver", "naver-abc")
        assertThat(identity!!.userId).isEqualTo(user.id)
        session.perform(httpGet("/account")).andExpect(status().isOk)
    }

    // ---- 플로우 3: 카카오 이메일 미동의 → 이메일 입력·확인 후 계정 생성 ----

    @Test
    fun `kakao login without email collects and verifies email then creates account`() {
        val email = "social-new@example.com"
        stubKakao(id = 2002, email = null, emailValid = false, emailVerified = false)

        val session = webSession()
        socialCallback(session, "kakao")
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/social-email"))

        session
            .perform(httpPost("/login/social-email").param("email", email).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/social-email"))

        session
            .perform(httpPost("/login/social-email/verify").param("code", latestCode()).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))

        val created = userRepository.findByEmail(email)
        assertThat(created).isNotNull
        assertThat(created!!.passwordHash).isNull()
        assertThat(created.emailVerified).isTrue()
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId("kakao", "2002")!!.userId)
            .isEqualTo(created.id)
        session.perform(httpGet("/account")).andExpect(status().isOk)
    }

    // ---- unlink 정책: 마지막 로그인 수단 차단 ----

    @Test
    fun `unlink is rejected with 409 when it is the last login method`() {
        val email = "social-only@example.com"
        val user = userRepository.save(User(email = email, passwordHash = null, emailVerified = true))
        federatedIdentityRepository.save(
            FederatedIdentity(userId = user.id!!, provider = "kakao", providerUserId = "3003", emailAtLink = email),
        )

        // 비밀번호도 패스키도 없고 소셜 연결이 유일한 수단 → 409.
        // (unlink 는 Stage B 부터 step-up 대상이므로 최근 재인증(auth_time) 세션이 필요하다.)
        recentAuthSession()
            .perform(
                delete("/api/federations/kakao")
                    .with(oauth2Login().attributes { it["sub"] = email }),
            ).andExpect(status().isConflict)
        assertThat(federatedIdentityRepository.countByUserId(user.id)).isEqualTo(1)

        // 비밀번호 설정 후에는 해제 가능 + 알림 메일 발송.
        user.passwordHash = passwordEncoder.encode("SecureP@ssw0rd123")
        userRepository.save(user)
        recentAuthSession()
            .perform(
                delete("/api/federations/kakao")
                    .with(oauth2Login().attributes { it["sub"] = email }),
            ).andExpect(status().isNoContent)
        assertThat(federatedIdentityRepository.countByUserId(user.id)).isZero()
        assertThat(sentMessages.any { it.subject?.contains("연결 해제") == true }).isTrue()
    }

    // ---- 소셜 전용 계정(password_hash NULL)의 폼 로그인은 항상 실패 ----

    @Test
    fun `form login always fails for passwordless social-only account`() {
        val email = "social-passwordless@example.com"
        userRepository.save(User(email = email, passwordHash = null, emailVerified = true))

        val session = webSession()
        session
            .perform(
                httpPost("/login/password")
                    .param("username", email)
                    .param("password", "AnythingAtAll1!")
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login/password?error"))
        session.perform(httpGet("/account")).andExpect(status().is3xxRedirection)
    }

    // ---- 로그인 페이지 버튼: 등록된 공급자만 렌더링 ----

    @Test
    fun `login page renders buttons only for registered providers`() {
        mockMvc
            .perform(httpGet("/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("카카오로 계속하기")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("네이버로 계속하기")))
            // GOOGLE_CLIENT_ID 미설정 → 구글 버튼 없음.
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Google로 계속하기"))))
    }

    // ---- helpers ----

    /** /oauth2/authorization/{provider} → state 추출 → 같은 세션으로 콜백. */
    private fun socialCallback(
        session: WebSession,
        provider: String,
    ) = session.perform(
        httpGet("/login/oauth2/code/$provider")
            .param("code", "fake-authorization-code")
            .param("state", startAuthorization(session, provider)),
    )

    private fun startAuthorization(
        session: WebSession,
        provider: String,
    ): String {
        val result =
            session
                .perform(httpGet("/oauth2/authorization/$provider"))
                .andExpect(status().is3xxRedirection)
                .andReturn()
        val location = result.response.getHeader("Location")!!
        val encoded = Regex("[?&]state=([^&]+)").find(location)!!.groupValues[1]
        return URLDecoder.decode(encoded, Charsets.UTF_8)
    }

    private fun stubKakao(
        id: Long,
        email: String?,
        emailValid: Boolean,
        emailVerified: Boolean,
    ) {
        wireMock.stubFor(
            post(urlPathEqualTo("/kakao/token")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"access_token":"kakao-access","token_type":"bearer","expires_in":3600}"""),
            ),
        )
        val account =
            if (email == null) {
                """{"profile":{"nickname":"카카오닉"}}"""
            } else {
                """{"email":"$email","is_email_valid":$emailValid,"is_email_verified":$emailVerified,"profile":{"nickname":"카카오닉"}}"""
            }
        wireMock.stubFor(
            get(urlPathEqualTo("/kakao/userinfo")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"id":$id,"kakao_account":$account}"""),
            ),
        )
    }

    private fun stubNaver(
        id: String,
        email: String,
        name: String,
    ) {
        wireMock.stubFor(
            post(urlPathEqualTo("/naver/token")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"access_token":"naver-access","token_type":"bearer","expires_in":3600}"""),
            ),
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/naver/userinfo")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """{"resultcode":"00","message":"success","response":{"id":"$id","email":"$email","name":"$name"}}""",
                    ),
            ),
        )
    }

    /** step-up 대상 API 호출용: 방금 재인증한 것으로 간주되는 세션(저장소에 auth_time 을 직접 심는다). */
    private fun recentAuthSession(): WebSession = webSession().prime(StepUp.AUTH_TIME_SESSION_KEY to java.time.Instant.now())

    private fun createLocalUser(
        email: String,
        emailVerified: Boolean,
    ): User =
        userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode("SecureP@ssw0rd123"),
                emailVerified = emailVerified,
            ),
        )

    private fun latestCode(): String {
        val body = sentMessages.last().text ?: ""
        return Regex("\\b\\d{6}\\b").find(body)!!.value
    }
}
