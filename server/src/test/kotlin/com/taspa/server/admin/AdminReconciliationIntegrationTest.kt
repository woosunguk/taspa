package com.taspa.server.admin

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.consumption.ConsumptionEvent
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.ledger.LedgerAccount
import com.taspa.server.domain.ledger.LedgerEntry
import com.taspa.server.domain.ledger.LedgerEntryRepository
import com.taspa.server.domain.ledger.LedgerEntryType
import com.taspa.server.domain.ledger.LedgerPosting
import com.taspa.server.domain.ledger.LedgerPostingRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * 전역 정합성 대사(`/api/admin/reconciliation`) — **운영자의 조기 경보**.
 *
 * 조직이 100개면 하나씩 열어 볼 수 없고, 열어 보지 않으면 아무도 모른다. 그래서 이 표면의 값어치는
 * "불일치를 **찾아내는가**" 와 "**아무것도 안 봤다**를 이상 없음으로 위장하지 않는가" 둘이다.
 * 후자를 위해 `scanned` 를 함께 단언한다 — 0 을 훑고 "이상 없음"이라 말하면 화면이 있으나 마나다.
 */
class AdminReconciliationIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var entryRepository: LedgerEntryRepository

    @Autowired lateinit var postingRepository: LedgerPostingRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var admin: User
    private lateinit var plainUser: User
    private lateinit var healthyOrg: UUID
    private lateinit var brokenOrg: UUID
    private lateinit var period: String
    private lateinit var merchantId: UUID
    private lateinit var occurredAt: Instant

    @BeforeEach
    fun setUp() {
        postingRepository.deleteAll()
        entryRepository.deleteAll()
        eventRepository.deleteAll()
        transactionRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        // 이번 달 중순 — 타임존 편차(±14h)에도 달을 넘지 않는 시각.
        val month = YearMonth.now(ZoneOffset.UTC)
        period = month.toString()
        occurredAt = month.atDay(15).atTime(12, 0).toInstant(ZoneOffset.UTC)

        healthyOrg = organizationRepository.save(Organization(slug = "recon-ok", name = "정상 조직")).id!!
        brokenOrg = organizationRepository.save(Organization(slug = "recon-bad", name = "깨진 조직")).id!!

        merchantId = merchantRepository.save(Merchant(name = "대사 확인 식당")).id!!
        admin = saveUser("recon-admin@example.com", UserRole.ADMIN)
        plainUser = saveUser("recon-plain@example.com", UserRole.USER)
        membershipRepository.save(
            OrgMembership(orgId = healthyOrg, userId = plainUser.id!!, role = OrgRole.ORG_ADMIN.name),
        )
    }

    @Test
    fun `불일치가 없으면 목록은 비지만 훑은 조직 수는 드러난다`() {
        // 대차가 맞는 원장 — 정상 조직.
        balancedEntry(healthyOrg, 10000)

        login(admin.email)
            .perform(get("/api/admin/reconciliation").param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unbalanced.length()").value(0))
            // ★"이상 없음"과 "아무것도 안 봤음"을 구분하는 값.
            .andExpect(jsonPath("$.scanned").value(1))
            .andExpect(jsonPath("$.skipped").value(0))
    }

    @Test
    fun `★깨진 조직만 목록에 오르고 조직 이름이 함께 나온다`() {
        balancedEntry(healthyOrg, 10000)
        // 한쪽 분개만 있는 사건 — 대차가 맞지 않는다.
        val entry =
            entryRepository.save(
                LedgerEntry(
                    orgId = brokenOrg,
                    entryType = LedgerEntryType.REDEEM.name,
                    transactionId = UUID.randomUUID(),
                    occurredAt = occurredAt,
                ),
            )
        postingRepository.save(
            LedgerPosting(
                entryId = entry.id!!,
                orgId = brokenOrg,
                account = LedgerAccount.ORG_RECEIVABLE.name,
                amountMinor = 7000,
                occurredAt = occurredAt,
            ),
        )

        login(admin.email)
            .perform(get("/api/admin/reconciliation").param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scanned").value(2))
            .andExpect(jsonPath("$.unbalanced.length()").value(1))
            // 운영자는 UUID 로 조직을 알아보지 못한다 — 이름이 없으면 대응이 한 단계 늦어진다.
            .andExpect(jsonPath("$.unbalanced[0].orgName").value("깨진 조직"))
            .andExpect(jsonPath("$.unbalanced[0].unbalancedEntryCount").value(1))
            .andExpect(jsonPath("$.unbalanced[0].amountDrift").value(7000))
            .andExpect(jsonPath("$.unbalanced[0].passThroughDrift").value(7000))
    }

    @Test
    fun `★원장이 통째로 빠진 조직도 전역 대사에 잡힌다(후보를 원장에서만 뽑지 않는다)`() {
        // 이 결함이 지키는 것: 후보를 ledger_entries 에서만 뽑으면, 원장 배선이 빠진 조직은 표본에서
        // **구조적으로** 제외돼 조직별 대사는 불일치라고 하는데 전역 화면만 "이상 없음"이라 답한다.
        // 대사가 잡으라고 만들어진 실패 형태가 경보 화면에서만 안 보이는 셈이다.
        balancedEntry(healthyOrg, 10000)
        // 거래·소비이벤트는 있는데 원장 분개가 **하나도 없는** 조직.
        val transaction =
            transactionRepository.save(
                MealTransaction(
                    authId = UUID.randomUUID().toString(),
                    orgId = brokenOrg,
                    userId = plainUser.id!!,
                    merchantId = merchantId,
                    amountMinor = 9000,
                    selfPaidMinor = 0,
                    mealWindow = "LUNCH",
                    posTxnId = UUID.randomUUID().toString(),
                    approvedAt = occurredAt,
                ),
            )
        eventRepository.save(
            ConsumptionEvent(
                orgId = brokenOrg,
                source = "payment",
                externalId = transaction.authId,
                userSub = plainUser.id!!,
                merchantId = merchantId,
                mealWindow = "LUNCH",
                quantity = 1,
                status = "CONFIRMED",
                occurredAt = occurredAt,
            ),
        )

        login(admin.email)
            .perform(get("/api/admin/reconciliation").param("period", period))
            .andExpect(status().isOk)
            // 후보가 원장 ∪ 장부라야 이 조직이 훑는 대상에 든다.
            .andExpect(jsonPath("$.scanned").value(2))
            .andExpect(jsonPath("$.unbalanced.length()").value(1))
            .andExpect(jsonPath("$.unbalanced[0].orgName").value("깨진 조직"))
            // 원장 미수금 0 − 장부 조직부담 9,000 = −9,000.
            .andExpect(jsonPath("$.unbalanced[0].amountDrift").value(-9000))
    }

    @Test
    fun `활동이 없는 기간은 훑은 조직이 0 이다(이상 없음과 구별된다)`() {
        balancedEntry(healthyOrg, 10000)

        val quiet = YearMonth.now(ZoneOffset.UTC).minusMonths(6).toString()
        login(admin.email)
            .perform(get("/api/admin/reconciliation").param("period", quiet))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scanned").value(0))
            .andExpect(jsonPath("$.unbalanced.length()").value(0))
    }

    @Test
    fun `기간을 생략하면 지난달을 본다(이번 달은 아직 쌓이는 중이라 기본값으로 부적절)`() {
        val lastMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1)
        login(admin.email)
            .perform(get("/api/admin/reconciliation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value(lastMonth.toString()))
    }

    @Test
    fun `일반 사용자와 조직관리자는 전역 대사를 볼 수 없다`() {
        // 조직관리자는 자기 org 대사(billing:Reconcile)는 보지만 전 조직은 못 본다 — 능력의 크기가 다르다.
        login(plainUser.email)
            .perform(get("/api/admin/reconciliation"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 요청은 로그인으로 유도된다`() {
        mockMvc
            .perform(get("/api/admin/reconciliation"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
    }

    @Test
    fun `기간 형식이 틀리면 400 이다`() {
        login(admin.email)
            .perform(get("/api/admin/reconciliation").param("period", "지난달"))
            .andExpect(status().isBadRequest)
    }

    // ---- helpers ----

    /**
     * **정상 상태 한 건** — 거래·소비이벤트·원장이 서로 맞는다.
     *
     * ★원장만 만들면 안 된다. 대사는 원장↔장부를 맞대므로 거래 없는 원장은 그 자체로 불일치이고,
     * 그러면 "정상 조직" 픽스처가 정상이 아니다(실제로 처음엔 이걸 빠뜨려 테스트가 깨졌다 —
     * 대사가 예민하게 작동한다는 증거이기도 하다).
     */
    private fun balancedEntry(
        orgId: UUID,
        amount: Long,
    ) {
        val transaction =
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
        eventRepository.save(
            ConsumptionEvent(
                orgId = orgId,
                source = "payment",
                externalId = transaction.authId,
                userSub = plainUser.id!!,
                merchantId = merchantId,
                mealWindow = "LUNCH",
                quantity = 1,
                status = "CONFIRMED",
                occurredAt = occurredAt,
            ),
        )
        val entry =
            entryRepository.save(
                LedgerEntry(
                    orgId = orgId,
                    entryType = LedgerEntryType.REDEEM.name,
                    transactionId = UUID.randomUUID(),
                    occurredAt = occurredAt,
                ),
            )
        postingRepository.saveAll(
            listOf(
                LedgerPosting(
                    entryId = entry.id!!,
                    orgId = orgId,
                    account = LedgerAccount.ORG_RECEIVABLE.name,
                    amountMinor = amount,
                    occurredAt = occurredAt,
                ),
                LedgerPosting(
                    entryId = entry.id!!,
                    orgId = orgId,
                    account = LedgerAccount.MERCHANT_PAYABLE.name,
                    amountMinor = -amount,
                    occurredAt = occurredAt,
                ),
            ),
        )
    }

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
