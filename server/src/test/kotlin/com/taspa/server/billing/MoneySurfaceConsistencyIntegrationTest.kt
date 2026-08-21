package com.taspa.server.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.ledger.LedgerAccount
import com.taspa.server.domain.ledger.LedgerEntryRepository
import com.taspa.server.domain.ledger.LedgerPostingRepository
import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealRefundRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.meal.MealPolicyTimeRoundTripTest
import com.taspa.server.meal.MerchantConsoleService
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.JwkStorageService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * **돈 표면 교차 일관성** — 한 번의 결제·환불 흐름을 만들고, 그 사실을 말하는 **모든 화면이 같은 숫자를
 * 말하는지** 한 자리에서 확인한다.
 *
 * ★이 테스트가 존재하는 이유: 지금까지 이 시스템에서 실제로 터진 결함은 "계산이 틀렸다"보다
 * **"같은 사실을 두 화면이 다르게 말한다"** 쪽이었다.
 *  - 정산 명세가 환불축을 `status='APPROVED'` 로 묶어 **전액 환불을 통째로 누락**(서버).
 *  - 식수 로그 합계가 VOIDED 에서 early-return 해 같은 결함을 재현(프런트).
 *  - 사용내역이 전액 환불을 "0원 · 취소"로만 표시(프런트).
 * 표면마다 따로 있는 테스트는 전부 초록불이었는데도 그랬다 — **각자 자기 규칙 안에서는 맞았기 때문**이다.
 * 그래서 여기서는 표면을 **서로 맞대어** 본다.
 *
 * 창(窓) 주의: 조직 청구서는 org 타임존, 가맹 정산은 merchant 타임존 앵커라 일반적으로는 경계일 거래만큼
 * **정당하게 다를 수 있다**. 이 테스트는 둘 다 UTC 로 두어 그 차이를 제거한 뒤 나머지가 일치하는지를 본다
 * (달력 차이를 결함으로 오인하지 않기 위해 의도적으로 통제한 변수다).
 */
@TestPropertySource(properties = ["taspa.meal.qr-issue-cooldown=0s"])
class MoneySurfaceConsistencyIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var qrTokenRepository: MealQrTokenRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var refundRepository: MealRefundRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var entryRepository: LedgerEntryRepository

    @Autowired lateinit var postingRepository: LedgerPostingRepository

    @Autowired lateinit var invoiceService: InvoiceService

    @Autowired lateinit var reconciliationService: ReconciliationService

    @Autowired lateinit var merchantConsoleService: MerchantConsoleService

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val memberEmail = "consistency-member@example.com"
    private lateinit var orgId: UUID
    private lateinit var merchantId: UUID
    private lateinit var period: String

    @BeforeEach
    fun setUp() {
        postingRepository.deleteAll()
        entryRepository.deleteAll()
        refundRepository.deleteAll()
        eventRepository.deleteAll()
        transactionRepository.deleteAll()
        qrTokenRepository.deleteAll()
        merchantRepository.deleteAll()
        policyRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        period = YearMonth.now(ZoneOffset.UTC).toString()

        // 조직·매장 타임존을 **둘 다 UTC** 로 둔다 — 위 KDoc 의 "통제한 변수".
        orgId =
            organizationRepository
                .save(
                    Organization(slug = "consistency", name = "정합성 조직", timezone = "UTC"),
                ).id!!
        val user =
            userRepository.save(
                User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        membershipRepository.save(OrgMembership(orgId = orgId, userId = user.id!!, role = "MEMBER"))
        merchantId = merchantRepository.save(Merchant(name = "정합성 식당", timezone = "UTC")).id!!
        policyRepository.save(
            MealPolicy(
                orgId = orgId,
                perMealLimitMinor = 12000,
                dailyMealCount = 5,
                monthlyCapMinor = 200000,
                // 하루 전체를 창으로 깔아 실행 시각과 무관하게 결정적으로 만든다.
                breakfastStart = LocalTime.MIDNIGHT,
                breakfastEnd = LocalTime.of(8, 0),
                lunchStart = LocalTime.of(8, 0),
                lunchEnd = LocalTime.of(16, 0),
                dinnerStart = LocalTime.of(16, 0),
                dinnerEnd = MealPolicyTimeRoundTripTest.END_OF_DAY,
            ),
        )
    }

    @Test
    fun `★승인·부분환불·전액환불 뒤 모든 돈 화면이 같은 숫자를 말한다`() {
        // 1) 15,000 승인 → 조직 12,000(1식 한도) + 개인 3,000.
        val overLimit = approve(15000, "cons-1")
        // 2) 10,000 승인 → 조직 10,000(한도 안).
        val inLimit = approve(10000, "cons-2")
        // 3) 8,000 승인 후 **전액 환불** → VOIDED 로 수렴(청구·지급 대상에서 빠진다).
        val fullyRefunded = approve(8000, "cons-3")
        refund(fullyRefunded, 8000, "cons-rf-full")
        // 4) 첫 거래를 3,000 **부분 환불** → 남은 12,000 은 한도 안이라 조직 전액 부담, 개인은 0.
        refund(overLimit, 3000, "cons-rf-part")

        // 기대 조직 부담: 12,000(부분환불 후) + 10,000 = 22,000. 전액 환불 건은 0.
        val expectedOrgPaid = 22000L

        // ---- 1. 장부(청구서) ----
        val invoice = invoiceService.generate(orgId, period, null)
        assertThat(invoice.subtotalMinor)
            .describedAs("청구서 조직부담 합계")
            .isEqualTo(expectedOrgPaid)
        // 전액 환불 건은 VOIDED 라 건수에서도 빠진다.
        assertThat(invoice.txnCount).isEqualTo(2)

        // ---- 2. 원장 ----
        val from =
            YearMonth
                .parse(period)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        val to =
            YearMonth
                .parse(period)
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        val receivable = postingRepository.balance(orgId, LedgerAccount.ORG_RECEIVABLE.name, from, to)
        val payable = postingRepository.balance(orgId, LedgerAccount.MERCHANT_PAYABLE.name, from, to)
        assertThat(receivable)
            .describedAs("원장 미수금 — 청구서와 같아야 한다")
            .isEqualTo(expectedOrgPaid)
        // 플랫폼은 통과 지점이다 — 받을 돈과 줄 돈이 정확히 상쇄된다.
        assertThat(receivable + payable).describedAs("통과 잔여").isZero()

        // ---- 3. 3-way 대사 ----
        val report = reconciliationService.reconcile(orgId, period)
        assertThat(report.balanced)
            .describedAs("대사: 금액·건수·대차·통과 네 축 전부")
            .isTrue()

        // ---- 4. 가맹 정산 명세 ----
        val settlement = merchantConsoleService.settlement(merchantId, period)
        assertThat(settlement.payableMinor)
            .describedAs("매장 지급 예정액 — 조직 청구액과 같아야 한다(같은 달력·같은 결제)")
            .isEqualTo(expectedOrgPaid)
        // ★환불축은 상태 무관 — 전액 환불 8,000 + 부분 환불 3,000.
        assertThat(settlement.refundedTotalMinor)
            .describedAs("환불 합계 — 전액 환불이 VOIDED 로 수렴해도 사라지지 않는다")
            .isEqualTo(11000)
        // 개인부담은 매장이 계산대에서 이미 받았다 — 지급액에 섞이지 않는다.
        assertThat(settlement.selfPaidTotalMinor).isZero()

        // ---- 5. 전역 지급 현황 ----
        val payables = merchantConsoleService.platformPayables(period)
        val line = payables.lines.single { it.merchantId == merchantId }
        assertThat(line.payableMinor)
            .describedAs("전역 지급의 매장 줄 — 매장이 자기 화면에서 보는 금액과 같아야 한다")
            .isEqualTo(settlement.payableMinor)
        assertThat(line.refundedMinor).isEqualTo(settlement.refundedTotalMinor)
        assertThat(payables.totalPayableMinor).isEqualTo(expectedOrgPaid)

        // ---- 6. 소비 이벤트(예측의 정답데이터) ----
        // 부분 환불은 이벤트를 건드리지 않는다(식사는 실제로 있었다). 전액 환불만 제외된다.
        val confirmed = eventRepository.countConfirmedInWindow(orgId, from, to)
        assertThat(confirmed)
            .describedAs("확정 소비 이벤트 — 장부 승인 건수와 같아야 한다(대사의 건수축)")
            .isEqualTo(invoice.txnCount.toLong())

        // 사용하지 않은 참조를 남기지 않는다 — 승인 3건이 모두 서로 다른 거래였음을 확인.
        assertThat(setOf(overLimit, inLimit, fullyRefunded)).hasSize(3)
    }

    @Test
    fun `환불이 하나도 없어도 같은 결론이다(대조군)`() {
        // 이 대조군이 없으면 위 테스트가 "환불 경로에서만 맞는지"인지 "항상 맞는지"인지 구별되지 않는다.
        approve(9000, "plain-1")
        approve(11000, "plain-2")

        val invoice = invoiceService.generate(orgId, period, null)
        val settlement = merchantConsoleService.settlement(merchantId, period)
        val payables = merchantConsoleService.platformPayables(period)

        assertThat(invoice.subtotalMinor).isEqualTo(20000)
        assertThat(settlement.payableMinor).isEqualTo(20000)
        assertThat(settlement.refundedTotalMinor).isZero()
        assertThat(payables.totalPayableMinor).isEqualTo(20000)
        assertThat(reconciliationService.reconcile(orgId, period).balanced).isTrue()
    }

    // ---- helpers ----

    private fun approve(
        amountMinor: Long,
        posTxnId: String,
    ): String {
        val qr = issueQr()
        val body =
            mockMvc
                .perform(
                    post("/api/merchant/redeem")
                        .header("Authorization", "Bearer ${merchantToken()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"token":"$qr","amountMinor":$amountMinor,"posTxnId":"$posTxnId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("authId").asText()
    }

    private fun refund(
        authId: String,
        amountMinor: Long,
        posRefundId: String,
    ) {
        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/refund", authId)
                    .header("Authorization", "Bearer ${merchantToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":$amountMinor,"posRefundId":"$posRefundId"}"""),
            ).andExpect(status().isOk)
    }

    private fun issueQr(): String {
        val session = login(memberEmail)
        val body =
            session
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("token").asText()
    }

    private fun merchantToken(): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject("svc-pos")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", "meal.redeem")
                .claim("merchant_id", merchantId.toString())
                .build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
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
