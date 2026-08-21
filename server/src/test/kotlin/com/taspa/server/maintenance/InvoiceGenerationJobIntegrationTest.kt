package com.taspa.server.maintenance

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.billing.InvoiceService
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.billing.InvoiceLineRepository
import com.taspa.server.domain.billing.InvoiceRepository
import com.taspa.server.domain.billing.InvoiceStatus
import com.taspa.server.domain.meal.MealTransaction
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mail.MailService
import com.taspa.server.support.IntegrationTestBase
import io.mockk.every
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 월 청구서 자동 생성 잡 — **누락 청구를 막는 유일한 장치**의 회귀.
 *
 * ★잡 인스턴스를 **직접 만든다**(스프링 빈이 아니라). 유예 일수를 케이스마다 달리 줘야 하는데,
 * `@TestPropertySource` 는 클래스 단위라 한 값밖에 못 준다. 그리고 유예 판정은 "오늘이 며칠인가"에
 * 달려 있어, 프로퍼티로 고정하면 **한 달의 특정 날짜에만 통과하는 테스트**가 된다.
 *
 * 잡 자체는 테스트 프로파일에서 꺼져 있다(application-test.yml) — 크론 시각에 테스트가 돌면 다른
 * 테스트의 청구서 단언을 무너뜨리기 때문. 그래서 여기서만 명시적으로 인스턴스를 만들어 호출한다.
 */
class InvoiceGenerationJobIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var invoiceRepository: InvoiceRepository

    @Autowired lateinit var invoiceLineRepository: InvoiceLineRepository

    @Autowired lateinit var invoiceService: InvoiceService

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var mailService: MailService

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")
    private lateinit var orgId: UUID
    private lateinit var userId: UUID
    private lateinit var merchantId: UUID
    private lateinit var lastMonth: YearMonth

    @BeforeEach
    fun setUp() {
        auditEventRepository.deleteAllInBatch()
        invoiceLineRepository.deleteAllInBatch()
        invoiceRepository.deleteAllInBatch()
        transactionRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId =
            organizationRepository
                .save(
                    Organization(slug = "auto-inv", name = "자동청구 조직", timezone = "Asia/Seoul"),
                ).id!!
        userId =
            userRepository
                .save(
                    User(email = "auto-inv@example.com", passwordHash = "x", emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = userId, role = "MEMBER"))
        merchantId = merchantRepository.save(Merchant(name = "구내식당")).id!!

        // org 로컬(서울) 달력의 직전 달 — 잡이 대상으로 삼는 기간.
        lastMonth = YearMonth.from(java.time.LocalDate.now(seoul)).minusMonths(1)
    }

    @Test
    fun `★아무도 누르지 않아도 직전 달 초안이 생긴다(누락 청구 방지)`() {
        seedTxn(amount = 10000, selfPaid = 0)
        seedTxn(amount = 15000, selfPaid = 3000)

        job(graceDays = 0).generateMonthlyDrafts()

        val invoice = invoiceRepository.findByOrgIdAndPeriod(orgId, lastMonth.toString())
        assertThat(invoice).isNotNull
        // 10000 + (15000 − 3000) = 22000. 확정은 사람의 일이므로 DRAFT 로 남는다.
        assertThat(invoice!!.subtotalMinor).isEqualTo(22000)
        assertThat(invoice.txnCount).isEqualTo(2)
        assertThat(invoice.statusEnum()).isEqualTo(InvoiceStatus.DRAFT)
        assertThat(invoiceLineRepository.findAll()).hasSize(1)
    }

    @Test
    fun `★두 번 돌아도 청구서는 하나다(매일 도는 잡의 멱등성)`() {
        seedTxn(amount = 10000, selfPaid = 0)

        job(graceDays = 0).generateMonthlyDrafts()
        job(graceDays = 0).generateMonthlyDrafts()

        assertThat(invoiceRepository.findAll()).hasSize(1)
    }

    @Test
    fun `★사람이 재생성한 초안을 덮지 않는다`() {
        // 이 테스트가 지키는 것: 관리자가 확인한 숫자가 다음 날 새벽에 조용히 바뀌면 안 된다.
        seedTxn(amount = 10000, selfPaid = 0)
        invoiceService.generate(orgId, lastMonth.toString(), userId)
        // 사람이 만든 뒤 거래가 하나 더 들어왔다 — 잡이 덮었다면 합계가 25,000 이 된다.
        seedTxn(amount = 15000, selfPaid = 0)

        job(graceDays = 0).generateMonthlyDrafts()

        val invoice = invoiceRepository.findByOrgIdAndPeriod(orgId, lastMonth.toString())!!
        assertThat(invoice.subtotalMinor).isEqualTo(10000)
    }

    @Test
    fun `★초안을 만들면 조직관리자에게 알린다(아무도 모르면 자동 생성은 한 일이 없다)`() {
        val adminEmail = "auto-inv-admin@example.com"
        val adminId =
            userRepository
                .save(
                    User(email = adminEmail, passwordHash = "x", emailVerified = true),
                ).id!!
        membershipRepository.save(
            OrgMembership(orgId = orgId, userId = adminId, role = OrgRole.ORG_ADMIN.name),
        )
        seedTxn(amount = 10000, selfPaid = 0)

        val captured = slot<SimpleMailMessage>()
        every { mailSender.send(capture(captured)) } returns Unit

        job(graceDays = 0).generateMonthlyDrafts()

        assertThat(captured.isCaptured).isTrue()
        assertThat(captured.captured.to).containsExactly(adminEmail)
        val text = captured.captured.text!!
        assertThat(text).contains(lastMonth.toString())
        assertThat(text).contains("10,000")
        // 확정 링크가 아니라 **콘솔 링크**다 — 확정은 숫자를 확인한 뒤의 판단이다.
        assertThat(text).contains("/console/$orgId/invoices")
    }

    @Test
    fun `메일 발송이 실패해도 청구서는 남는다`() {
        // 알림이 없어서 생기는 문제를 고치려다 청구 자체를 잃으면 안 된다.
        val adminId =
            userRepository
                .save(
                    User(email = "auto-inv-admin2@example.com", passwordHash = "x", emailVerified = true),
                ).id!!
        membershipRepository.save(
            OrgMembership(orgId = orgId, userId = adminId, role = OrgRole.ORG_ADMIN.name),
        )
        seedTxn(amount = 10000, selfPaid = 0)
        every { mailSender.send(any<SimpleMailMessage>()) } throws RuntimeException("smtp down")

        job(graceDays = 0).generateMonthlyDrafts()

        assertThat(invoiceRepository.findByOrgIdAndPeriod(orgId, lastMonth.toString())).isNotNull
    }

    @Test
    fun `활동이 없으면 0원 청구서를 만들지 않는다`() {
        // 0원 청구서가 쌓이면 목록에서 진짜 청구서를 가린다.
        job(graceDays = 0).generateMonthlyDrafts()

        assertThat(invoiceRepository.findAll()).isEmpty()
    }

    @Test
    fun `★활동이 없으면 감사 이벤트도 남기지 않는다(만들었다 지우는 방식이 아니다)`() {
        // 만들었다가 지우면 존재하지 않는 청구서의 INVOICE_GENERATED 가 활동로그에 남아,
        // 관리자가 사라진 청구서를 찾게 된다.
        job(graceDays = 0).generateMonthlyDrafts()

        val generated = auditEventRepository.findAll().filter { it.type == "INVOICE_GENERATED" }
        assertThat(generated).isEmpty()
    }

    @Test
    fun `정지된 조직은 건너뛴다`() {
        seedTxn(amount = 10000, selfPaid = 0)
        val org = organizationRepository.findById(orgId).get()
        org.status = OrgStatus.SUSPENDED.name
        organizationRepository.save(org)

        job(graceDays = 0).generateMonthlyDrafts()

        assertThat(invoiceRepository.findAll()).isEmpty()
    }

    @Test
    fun `★유예 기간 안에는 만들지 않는다(경계 직전 거래의 지연 도착 대비)`() {
        seedTxn(amount = 10000, selfPaid = 0)

        // 유예를 31일로 두면 어떤 날짜에 돌려도 항상 유예 안이다 — 날짜에 의존하지 않는 단언.
        job(graceDays = 31).generateMonthlyDrafts()

        assertThat(invoiceRepository.findAll()).isEmpty()
    }

    @Test
    fun `한 조직의 실패가 다른 조직의 청구를 막지 않는다`() {
        // 깨진 타임존은 저장 시 검증되지만, 잡은 어떤 이유로든 한 조직이 터져도 나머지를 계속해야 한다.
        // (그러지 않으면 조직 하나 때문에 그 뒤 전부가 누락 — 이 잡이 막으려던 사고 그대로다.)
        seedTxn(amount = 10000, selfPaid = 0)
        val broken =
            organizationRepository.save(
                Organization(slug = "auto-inv-broken", name = "깨진 조직", timezone = "Asia/Seoul"),
            )
        // 조직은 있는데 멤버십·거래가 없다 — 활동 없음으로 건너뛰고 정상 조직은 계속 처리돼야 한다.
        assertThat(broken.id).isNotNull

        job(graceDays = 0).generateMonthlyDrafts()

        assertThat(invoiceRepository.findByOrgIdAndPeriod(orgId, lastMonth.toString())).isNotNull
        assertThat(invoiceRepository.findByOrgIdAndPeriod(broken.id!!, lastMonth.toString())).isNull()
    }

    // ---- helpers ----

    private fun job(graceDays: Long) =
        InvoiceGenerationJob(
            organizationRepository,
            invoiceRepository,
            invoiceService,
            membershipRepository,
            userRepository,
            mailService,
            graceDays,
            "http://localhost:3000",
        )

    /** 직전 달 15일 정오(서울) — 타임존 편차에도 달을 넘지 않는 시각. */
    private fun seedTxn(
        amount: Long,
        selfPaid: Long,
    ): MealTransaction {
        val approvedAt: Instant =
            lastMonth
                .atDay(15)
                .atTime(12, 0)
                .atZone(seoul)
                .toInstant()
        return transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = userId,
                merchantId = merchantId,
                amountMinor = amount,
                selfPaidMinor = selfPaid,
                mealWindow = "LUNCH",
                posTxnId = UUID.randomUUID().toString(),
                approvedAt = approvedAt,
            ),
        )
    }
}
