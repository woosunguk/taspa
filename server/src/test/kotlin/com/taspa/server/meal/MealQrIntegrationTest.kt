package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealTransaction
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 식권 QR 발급/이력 API(L1) 통합 테스트 — 세션 경로.
 *  - 발급: 활성 멤버만(비멤버 403), 원문은 응답에만(저장은 해시), 쿨다운(기본 10초) 내 재요청 429.
 *  - 이력: 본인 거래만, 상점명 라벨 포함.
 */
class MealQrIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var qrTokenRepository: MealQrTokenRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgId: UUID
    private lateinit var memberEmail: String
    private lateinit var memberId: UUID
    private lateinit var outsiderEmail: String

    @BeforeEach
    fun setUp() {
        transactionRepository.deleteAll()
        qrTokenRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId = organizationRepository.save(Organization(slug = "meal-qr", name = "Meal QR Org")).id!!
        memberEmail = "meal-qr-member@example.com"
        memberId =
            userRepository
                .save(
                    User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = memberId, role = "MEMBER"))
        outsiderEmail = "meal-qr-outsider@example.com"
        userRepository.save(
            User(email = outsiderEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `활성 멤버는 QR 을 발급받고, 저장소에는 해시만 남는다`() {
        val session = login(memberEmail)
        val body =
            session
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.token").isNotEmpty)
                .andExpect(jsonPath("$.expiresAt").isNotEmpty)
                .andReturn()
                .response.contentAsString
        val rawToken = objectMapper.readTree(body).get("token").asText()

        val stored = qrTokenRepository.findAll()
        assertThat(stored).hasSize(1)
        assertThat(stored.first().tokenHash).isNotEqualTo(rawToken) // 원문 미저장(해시만)
        assertThat(stored.first().userId).isEqualTo(memberId)
        assertThat(stored.first().orgId).isEqualTo(orgId)
        assertThat(stored.first().usedAt).isNull()
    }

    @Test
    fun `비멤버는 QR 발급이 403 이다`() {
        val session = login(outsiderEmail)
        session
            .perform(
                post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
            ).andExpect(status().isForbidden)
        assertThat(qrTokenRepository.count()).isZero()
    }

    @Test
    fun `미인증 발급 요청은 401 이다`() {
        // /api/meal/** 는 default 체인 소속이고, 그 체인은 API 경로에 ApiAuthenticationEntryPoint 를 매핑해
        // **로그인 페이지가 아니라 401 JSON** 을 돌려준다. 리다이렉트를 돌려주면 브라우저 fetch 가 그것을
        // 투명하게 따라가 로그인 HTML 을 200 으로 받고, 호출자는 실패를 성공으로 오인한다.
        mockMvc
            .perform(
                post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
    }

    @Test
    fun `휴직 중인 멤버는 QR 발급이 거부된다`() {
        // ★멤버십 ACTIVE 와 재직상태는 다른 축이다. HR(SCIM)이 휴직을 밀면 employment_status 만 바뀌고
        //   멤버십은 살아 있는데, 이 가드가 없으면 회사가 휴직자 식대를 계속 지불한다.
        val membership = membershipRepository.findByOrgIdAndUserId(orgId, memberId)!!
        membership.employmentStatus = EmploymentStatus.ON_LEAVE.name
        membershipRepository.save(membership)

        val session = login(memberEmail)
        session
            .perform(
                post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOT_EMPLOYED"))
        assertThat(qrTokenRepository.count()).isZero()
    }

    @Test
    fun `퇴직한 멤버는 QR 발급이 거부된다`() {
        val membership = membershipRepository.findByOrgIdAndUserId(orgId, memberId)!!
        membership.employmentStatus = EmploymentStatus.TERMINATED.name
        membershipRepository.save(membership)

        login(memberEmail)
            .perform(
                post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOT_EMPLOYED"))
    }

    @Test
    fun `쿨다운 내 재발급 요청은 429 로 거절된다(남용 방지)`() {
        val session = login(memberEmail)
        val request = post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}""")
        session.perform(request).andExpect(status().isOk)
        session
            .perform(
                post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
            ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.errorCode").value("QR_RATE_LIMITED"))
        assertThat(qrTokenRepository.count()).isEqualTo(1)
    }

    @Test
    fun `거래 이력은 본인 것만 최근순으로 상점명과 함께 반환된다`() {
        val merchant = merchantRepository.save(Merchant(name = "구내식당"))
        val other =
            userRepository.save(
                User(email = "meal-qr-other@example.com", passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = memberId,
                merchantId = merchant.id!!,
                amountMinor = 9000,
                mealWindow = "LUNCH",
                posTxnId = "hist-1",
            ),
        )
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = other.id!!,
                merchantId = merchant.id!!,
                amountMinor = 7000,
                mealWindow = "LUNCH",
                posTxnId = "hist-2",
            ),
        )

        val session = login(memberEmail)
        session
            .perform(get("/api/meal/transactions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].merchantName").value("구내식당"))
            .andExpect(jsonPath("$[0].amountMinor").value(9000))
            .andExpect(jsonPath("$[0].status").value("APPROVED"))
    }

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
