package com.taspa.server.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.meal.MealTransaction
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 전역 지급 현황(`/api/admin/payables`) — **운영자의 자금 계획 도구**.
 *
 * 매장별 정산은 사장이 자기 몫을 확인할 때 연다. 이건 반대 방향 — 매장이 100개면 하나씩 열어 볼 수
 * 없고, 열어 보지 않으면 이번 달 총 지급액을 아무도 모른다.
 *
 * ★핵심 단언은 "총액이 맞는가"가 아니라 **"매장이 보는 숫자와 같은가"** 다. 두 화면이 갈라지면 어느
 * 쪽이 맞는지 아무도 답할 수 없고, 그 순간 정산 분쟁을 조정할 근거가 사라진다.
 */
class AdminPayablesIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var admin: User
    private lateinit var plainUser: User
    private lateinit var orgId: UUID
    private lateinit var merchantA: UUID
    private lateinit var merchantB: UUID
    private lateinit var period: String
    private lateinit var occurredAt: Instant

    @BeforeEach
    fun setUp() {
        transactionRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        // 이번 달 중순 — 타임존 편차(±14h)에도 달을 넘지 않는 시각.
        val month = YearMonth.now(ZoneId.of("UTC"))
        period = month.toString()
        occurredAt =
            month
                .atDay(15)
                .atTime(12, 0)
                .atZone(ZoneId.of("UTC"))
                .toInstant()

        orgId = organizationRepository.save(Organization(slug = "pay-org", name = "지급 테스트")).id!!
        admin = saveUser("payables-admin@example.com", UserRole.ADMIN)
        plainUser = saveUser("payables-plain@example.com", UserRole.USER)
        membershipRepository.save(
            OrgMembership(orgId = orgId, userId = plainUser.id!!, role = OrgRole.MEMBER.name),
        )
        merchantA = merchantRepository.save(Merchant(name = "가맹 A")).id!!
        merchantB = merchantRepository.save(Merchant(name = "가맹 B")).id!!
    }

    @Test
    fun `★지급 예정액은 조직 부담만 더하고 개인 부담은 섞이지 않는다`() {
        // A: 15,000 결제 중 조직 12,000 + 개인 3,000 → 지급 대상은 12,000.
        seedTxn(merchantA, amount = 15000, selfPaid = 3000)
        // B: 9,000 전액 조직 부담.
        seedTxn(merchantB, amount = 9000, selfPaid = 0)

        login(admin.email)
            .perform(get("/api/admin/payables").param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value(period))
            .andExpect(jsonPath("$.scanned").value(2))
            .andExpect(jsonPath("$.skipped").value(0))
            // 12,000 + 9,000 = 21,000. 개인부담 3,000 은 매장이 계산대에서 이미 받았다.
            .andExpect(jsonPath("$.totalPayableMinor").value(21000))
            .andExpect(jsonPath("$.totalApprovedCount").value(2))
            .andExpect(jsonPath("$.lines.length()").value(2))
            // 금액 큰 순 — 운영자는 큰 지급부터 확인한다.
            .andExpect(jsonPath("$.lines[0].merchantName").value("가맹 A"))
            .andExpect(jsonPath("$.lines[0].payableMinor").value(12000))
            .andExpect(jsonPath("$.lines[1].payableMinor").value(9000))
    }

    @Test
    fun `★매장이 자기 화면에서 보는 금액과 정확히 같다`() {
        // 두 화면이 갈라지면 정산 분쟁을 조정할 근거가 사라진다.
        seedTxn(merchantA, amount = 15000, selfPaid = 3000)
        seedTxn(merchantA, amount = 8000, selfPaid = 0)

        val session = login(admin.email)
        val payablesBody =
            session
                .perform(get("/api/admin/payables").param("period", period))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val platformAmount =
            objectMapper
                .readTree(payablesBody)
                .get("lines")
                .get(0)
                .get("payableMinor")
                .asLong()

        // 같은 기간의 매장 정산 명세(플랫폼 ADMIN 은 지원 목적으로 열람 가능).
        val settlementBody =
            session
                .perform(
                    get("/api/merchant-console/{id}/settlement", merchantA).param("period", period),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val merchantAmount = objectMapper.readTree(settlementBody).get("payableMinor").asLong()

        assertThat(platformAmount).isEqualTo(merchantAmount)
    }

    @Test
    fun `활동이 없는 기간은 훑은 매장이 0 이다(지급액 0 과 구별된다)`() {
        seedTxn(merchantA, amount = 10000, selfPaid = 0)

        val quiet = YearMonth.now(ZoneId.of("UTC")).minusMonths(6).toString()
        login(admin.email)
            .perform(get("/api/admin/payables").param("period", quiet))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scanned").value(0))
            .andExpect(jsonPath("$.totalPayableMinor").value(0))
            .andExpect(jsonPath("$.lines.length()").value(0))
    }

    @Test
    fun `취소된 거래는 지급 대상에서 빠진다`() {
        seedTxn(merchantA, amount = 10000, selfPaid = 0)
        seedTxn(merchantA, amount = 7000, selfPaid = 0, status = "VOIDED")

        login(admin.email)
            .perform(get("/api/admin/payables").param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalPayableMinor").value(10000))
            .andExpect(jsonPath("$.totalApprovedCount").value(1))
    }

    @Test
    fun `기간을 생략하면 이번 달을 본다(지급은 진행 중인 달을 계획한다)`() {
        login(admin.email)
            .perform(get("/api/admin/payables"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value(YearMonth.now(ZoneId.of("UTC")).toString()))
    }

    @Test
    fun `일반 사용자는 전역 지급 현황을 볼 수 없다`() {
        login(plainUser.email)
            .perform(get("/api/admin/payables"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 요청은 401 이다`() {
        mockMvc
            .perform(get("/api/admin/payables"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
    }

    @Test
    fun `기간 형식이 틀리면 400 이다`() {
        login(admin.email)
            .perform(get("/api/admin/payables").param("period", "이번달"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `CSV 도 같은 권한·같은 숫자를 낸다`() {
        seedTxn(merchantA, amount = 15000, selfPaid = 3000)

        val body =
            login(admin.email)
                .perform(get("/api/admin/payables/csv").param("period", period))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)

        assertThat(body).contains("지급예정액(원)")
        assertThat(body).contains("가맹 A")
        assertThat(body).contains("12000")
        // 엑셀 한글 깨짐 방지 BOM.
        assertThat(body.first()).isEqualTo('\uFEFF')

        // 형식이 권한을 우회하지 않는다.
        login(plainUser.email)
            .perform(get("/api/admin/payables/csv"))
            .andExpect(status().isForbidden)
    }

    // ---- helpers ----

    private fun seedTxn(
        merchantId: UUID,
        amount: Long,
        selfPaid: Long,
        status: String = "APPROVED",
    ): MealTransaction =
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = plainUser.id!!,
                merchantId = merchantId,
                amountMinor = amount,
                selfPaidMinor = selfPaid,
                mealWindow = "LUNCH",
                status = status,
                posTxnId = UUID.randomUUID().toString(),
                approvedAt = occurredAt,
                voidedAt = if (status == "VOIDED") occurredAt else null,
            ),
        )

    private fun saveUser(
        email: String,
        role: UserRole,
    ): User =
        userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = role.name,
            ),
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
