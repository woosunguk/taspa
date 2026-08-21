package com.taspa.server.admin

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.billing.InvoiceService
import com.taspa.server.domain.billing.InvoiceLineRepository
import com.taspa.server.domain.billing.InvoiceRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * 미확정 청구서 현황 — **자동 생성 루프의 마지막 구멍**.
 *
 * 초안은 자동으로 만들어지고 메일까지 나가지만, 조직이 확정하지 않으면 그대로 방치된다. 그러면 회사가
 * 쓴 식대를 우리가 끝내 청구하지 않고 아무도 그 사실을 모른다 — 자동 생성 잡이 막으려던 사고가 한 단계
 * 뒤에서 반복된다.
 *
 * ★두 상태를 구별하는 것이 이 표면의 값어치다: **DRAFT**(사람이 안 눌렀다)와 **MISSING**(시스템이 못
 * 만들었다). 뒤쪽이 더 심각한데, 하나로 뭉치면 그 차이가 사라진다.
 */
// ★유예 기간을 0 으로 고정한다. 기본값(2일)이면 매달 1·2일에 이 테스트가 MISSING 대신 PENDING 을 받아
// **한 달 중 특정 날짜에만 깨진다** — 이 코드베이스가 반복해서 당한 형태다(끼니창 END_OF_DAY, 크론 시각).
// 유예 판정 자체는 billing/InvoiceGraceWindowTest 가 날짜 독립적으로 고정한다.
@TestPropertySource(properties = ["taspa.billing.auto-generate-grace-days=0"])
class AdminUnfinalizedInvoicesIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var invoiceRepository: InvoiceRepository

    @Autowired lateinit var invoiceLineRepository: InvoiceLineRepository

    @Autowired lateinit var invoiceService: InvoiceService

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var admin: User
    private lateinit var plainUser: User
    private lateinit var draftOrg: UUID
    private lateinit var missingOrg: UUID
    private lateinit var doneOrg: UUID
    private lateinit var merchantId: UUID
    private lateinit var period: String
    private lateinit var occurredAt: Instant

    @BeforeEach
    fun setUp() {
        invoiceLineRepository.deleteAllInBatch()
        invoiceRepository.deleteAllInBatch()
        transactionRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        // 지난달 중순 — 컨트롤러 기본값(지난달)과 같은 창.
        val month = YearMonth.now(ZoneOffset.UTC).minusMonths(1)
        period = month.toString()
        occurredAt = month.atDay(15).atTime(12, 0).toInstant(ZoneOffset.UTC)

        draftOrg =
            organizationRepository
                .save(
                    Organization(slug = "unf-draft", name = "초안만 있는 조직", timezone = "UTC"),
                ).id!!
        missingOrg =
            organizationRepository
                .save(
                    Organization(slug = "unf-missing", name = "청구서가 없는 조직", timezone = "UTC"),
                ).id!!
        doneOrg =
            organizationRepository
                .save(
                    Organization(slug = "unf-done", name = "확정한 조직", timezone = "UTC"),
                ).id!!
        merchantId = merchantRepository.save(Merchant(name = "구내식당", timezone = "UTC")).id!!

        admin = saveUser("unf-admin@example.com", UserRole.ADMIN)
        plainUser = saveUser("unf-plain@example.com", UserRole.USER)
        listOf(draftOrg, missingOrg, doneOrg).forEach {
            membershipRepository.save(OrgMembership(orgId = it, userId = plainUser.id!!, role = OrgRole.MEMBER.name))
        }
    }

    @Test
    fun `★확정 안 된 조직만 오르고 DRAFT 와 MISSING 을 구별한다`() {
        seedTxn(draftOrg, 10000)
        seedTxn(missingOrg, 7000)
        seedTxn(doneOrg, 5000)

        // 초안만 만든 조직 / 초안을 만들고 확정까지 한 조직 / 아무것도 안 만든 조직.
        invoiceService.generate(draftOrg, period, null)
        val done = invoiceService.generate(doneOrg, period, null)
        invoiceService.finalize(doneOrg, done.id, admin.id!!)

        login(admin.email)
            .perform(get("/api/admin/invoices/unfinalized").param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value(period))
            // 거래가 있는 조직 셋 다 훑되, 확정한 조직은 목록에서 빠진다.
            .andExpect(jsonPath("$.scanned").value(3))
            .andExpect(jsonPath("$.lines.length()").value(2))
            // ★MISSING 이 먼저 — 사람이 안 누른 것보다 시스템이 못 만든 것이 더 심각하다.
            .andExpect(jsonPath("$.lines[0].state").value("MISSING"))
            .andExpect(jsonPath("$.lines[0].orgName").value("청구서가 없는 조직"))
            .andExpect(jsonPath("$.lines[0].subtotalMinor").doesNotExist())
            .andExpect(jsonPath("$.lines[1].state").value("DRAFT"))
            .andExpect(jsonPath("$.lines[1].orgName").value("초안만 있는 조직"))
            .andExpect(jsonPath("$.lines[1].subtotalMinor").value(10000))
    }

    @Test
    fun `거래가 없던 조직은 청구서가 없어도 목록에 오르지 않는다(정상 상태는 경보가 아니다)`() {
        // 정상 상태가 경보에 섞이면 진짜 누락이 묻힌다.
        login(admin.email)
            .perform(get("/api/admin/invoices/unfinalized").param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scanned").value(0))
            .andExpect(jsonPath("$.lines.length()").value(0))
    }

    @Test
    fun `전부 확정됐으면 목록은 비지만 훑은 조직 수는 드러난다`() {
        seedTxn(doneOrg, 5000)
        val done = invoiceService.generate(doneOrg, period, null)
        invoiceService.finalize(doneOrg, done.id, admin.id!!)

        login(admin.email)
            .perform(get("/api/admin/invoices/unfinalized").param("period", period))
            .andExpect(status().isOk)
            // "다 확정됐다"와 "아무것도 안 봤다"를 구별하는 값.
            .andExpect(jsonPath("$.scanned").value(1))
            .andExpect(jsonPath("$.lines.length()").value(0))
    }

    @Test
    fun `기간을 생략하면 지난달을 본다(이번 달은 아직 확정할 시점이 아니다)`() {
        login(admin.email)
            .perform(get("/api/admin/invoices/unfinalized"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value(YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString()))
    }

    @Test
    fun `일반 사용자는 볼 수 없다`() {
        login(plainUser.email)
            .perform(get("/api/admin/invoices/unfinalized"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `기간 형식이 틀리면 400 이다`() {
        login(admin.email)
            .perform(get("/api/admin/invoices/unfinalized").param("period", "지난달"))
            .andExpect(status().isBadRequest)
    }

    // ---- helpers ----

    private fun seedTxn(
        orgId: UUID,
        amount: Long,
    ): MealTransaction =
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = plainUser.id!!,
                merchantId = merchantId,
                amountMinor = amount,
                selfPaidMinor = 0,
                mealWindow = "LUNCH",
                posTxnId = UUID.randomUUID().toString(),
                approvedAt = occurredAt,
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
