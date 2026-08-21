package com.taspa.server.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.ledger.LedgerAccount
import com.taspa.server.domain.ledger.LedgerEntryRepository
import com.taspa.server.domain.ledger.LedgerPosting
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
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.meal.MealPolicyTimeRoundTripTest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 이중부기 원장 + 3-way 대사 통합 테스트.
 *
 * ★대사는 정상 동작에서 항상 0 이다 — 세 기록이 같은 트랜잭션에서 쓰이기 때문이다. 그래서 "0 이 나온다"만
 * 확인하면 아무것도 증명하지 못한다(원장을 안 쓰고 대사가 상수 0 을 반환해도 통과한다).
 * 그래서 여기서는 **일부러 원장을 오염시켜 대사가 잡아내는지**를 함께 본다.
 */
@TestPropertySource(properties = ["taspa.meal.qr-issue-cooldown=0s"])
class LedgerReconciliationIntegrationTest : IntegrationTestBase() {
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

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Autowired lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val memberEmail = "ledger-member@example.com"
    private val adminEmail = "ledger-admin@example.com"
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

        orgId = organizationRepository.save(Organization(slug = "ledger", name = "원장 테스트")).id!!
        val member =
            userRepository.save(
                User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member.id!!, role = OrgRole.MEMBER.name))
        val admin =
            userRepository.save(
                User(email = adminEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        membershipRepository.save(OrgMembership(orgId = orgId, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        merchantId = merchantRepository.save(Merchant(name = "구내식당")).id!!
        policyRepository.save(
            MealPolicy(
                orgId = orgId,
                perMealLimitMinor = 12000,
                dailyMealCount = 5,
                monthlyCapMinor = 200000,
                breakfastStart = LocalTime.MIDNIGHT,
                breakfastEnd = LocalTime.of(8, 0),
                lunchStart = LocalTime.of(8, 0),
                lunchEnd = LocalTime.of(16, 0),
                dinnerStart = LocalTime.of(16, 0),
                dinnerEnd = MealPolicyTimeRoundTripTest.END_OF_DAY,
            ),
        )
        period = YearMonth.now(ZoneOffset.UTC).toString()
    }

    @Test
    fun `승인은 대차평형인 분개 두 줄을 남긴다`() {
        approve(10000, "led-1")

        val entries = entryRepository.findAll()
        assertThat(entries).hasSize(1)
        assertThat(entries.first().entryType).isEqualTo("REDEEM")

        val postings = postingRepository.findAll()
        assertThat(postings).hasSize(2)
        // 플랫폼은 통과 지점 — 조직 미수금과 가맹 미지급금이 정확히 반대다.
        assertThat(postings.first { it.account == LedgerAccount.ORG_RECEIVABLE.name }.amountMinor).isEqualTo(10000)
        assertThat(postings.first { it.account == LedgerAccount.MERCHANT_PAYABLE.name }.amountMinor).isEqualTo(-10000)
        assertThat(postings.sumOf { it.amountMinor }).isZero()
    }

    @Test
    fun `한도 초과 개인부담은 분개하지 않는다(우리 돈이 아니다)`() {
        // 15,000 결제 → 조직 12,000 + 개인 3,000. 원장에는 12,000 만 들어간다.
        approve(15000, "led-2")

        assertThat(balance(LedgerAccount.ORG_RECEIVABLE)).isEqualTo(12000)
        // 개인부담은 사건 메타로만 남는다 — 대사에서 "장부와 왜 다른가"를 설명하는 값이다.
        assertThat(entryRepository.findAll().first().selfPaidMinor).isEqualTo(3000)
    }

    @Test
    fun `★취소는 원 분개를 지우지 않고 반대 분개를 덧붙인다`() {
        val authId = approve(10000, "led-3")
        voidTransaction(authId)

        // 원 분개가 남아 있어야 "그날 얼마가 오갔다가 취소됐나"를 알 수 있다.
        assertThat(entryRepository.findAll().map { it.entryType }).containsExactlyInAnyOrder("REDEEM", "VOID")
        assertThat(postingRepository.findAll()).hasSize(4)
        // 잔액은 상계돼 0.
        assertThat(balance(LedgerAccount.ORG_RECEIVABLE)).isZero()
    }

    @Test
    fun `★환불은 조직에게 돌아간 몫만 분개한다`() {
        // 15,000(조직 12,000 + 개인 3,000)에서 3,000 환불 → 개인이 전액 회수, 조직 분개는 0원 변동.
        val authId = approve(15000, "led-4")
        refund(authId, 3000, "led-rf-4")

        assertThat(balance(LedgerAccount.ORG_RECEIVABLE)).isEqualTo(12000)
        val refundEntry = entryRepository.findAll().first { it.entryType == "REFUND" }
        // 0원 사건도 분개는 남긴다 — "일어났는데 금액이 0" 과 "일어나지 않음"은 다른 사실이다.
        assertThat(refundEntry.selfPaidMinor).isEqualTo(-3000)

        // 조직 몫이 실제로 돌아가는 경우도 확인.
        refund(authId, 5000, "led-rf-4b")
        assertThat(balance(LedgerAccount.ORG_RECEIVABLE)).isEqualTo(7000)
    }

    @Test
    fun `★대사는 정상 흐름에서 네 지표가 모두 0 이다`() {
        approve(10000, "led-5a")
        approve(15000, "led-5b")
        val voided = approve(9000, "led-5c")
        voidTransaction(voided)
        val refunded = approve(8000, "led-5d")
        refund(refunded, 2000, "led-rf-5")

        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId).param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(true))
            .andExpect(jsonPath("$.amountDrift").value(0))
            .andExpect(jsonPath("$.countDrift").value(0))
            .andExpect(jsonPath("$.unbalancedEntryCount").value(0))
            .andExpect(jsonPath("$.passThroughDrift").value(0))
    }

    @Test
    fun `★★원장이 장부와 어긋나면 대사가 잡아낸다`() {
        // 이 테스트가 없으면 "대사가 0 을 반환한다"는 사실이 아무것도 증명하지 않는다 —
        // 원장을 아예 안 쓰고 상수 0 을 돌려줘도 통과하기 때문이다. 일부러 오염시킨다.
        val authId = approve(10000, "led-6")
        val entry = entryRepository.findAll().first()
        postingRepository.save(
            LedgerPosting(
                entryId = entry.id!!,
                orgId = orgId,
                account = LedgerAccount.ORG_RECEIVABLE.name,
                amountMinor = 5000,
                merchantId = merchantId,
                occurredAt = entry.occurredAt,
            ),
        )

        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId).param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(false))
            .andExpect(jsonPath("$.amountDrift").value(5000))
            // 한 사건의 분개 합이 0 이 아니게 됐다 — 원장 자체가 깨진 것이라 잔액도 못 믿는다.
            .andExpect(jsonPath("$.unbalancedEntryCount").value(1))
            .andExpect(jsonPath("$.passThroughDrift").value(5000))
        assertThat(authId).isNotBlank()
    }

    @Test
    fun `★소비 이벤트가 유실되면 건수축이 잡아낸다`() {
        approve(10000, "led-7a")
        approve(10000, "led-7b")
        // 예측의 정답데이터가 조용히 사라진 상황(full-replace 경로의 과거 결함이 이 형태였다).
        eventRepository.deleteAll()

        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId).param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(false))
            .andExpect(jsonPath("$.amountDrift").value(0))
            .andExpect(jsonPath("$.countDrift").value(2))
    }

    @Test
    fun `대사는 조직관리자만 볼 수 있고 타 조직은 403 이다`() {
        val otherOrg = organizationRepository.save(Organization(slug = "ledger-other", name = "다른 조직")).id!!

        login(memberEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId))
            .andExpect(status().isForbidden)
        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", otherOrg))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `기간 형식이 틀리면 400 이다`() {
        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId).param("period", "2026년 7월"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `같은 사건을 두 번 분개하지 않는다(멱등)`() {
        val authId = approve(10000, "led-8")
        // 같은 posTxnId 재전송 = 멱등 재반환. 원장이 부풀면 조직 청구가 두 배가 된다.
        val qr = issueQr()
        mockMvc
            .perform(
                post("/api/merchant/redeem")
                    .header("Authorization", "Bearer ${merchantToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$qr","amountMinor":10000,"posTxnId":"led-8"}"""),
            ).andExpect(status().isOk)

        assertThat(entryRepository.findAll()).hasSize(1)
        assertThat(balance(LedgerAccount.ORG_RECEIVABLE)).isEqualTo(10000)
        assertThat(authId).isNotBlank()
    }

    @Test
    fun `★멱등 조회가 null 인자에서도 실제로 찾는다(죽은 가드가 아님을 확인)`() {
        // 파생 쿼리에 null 을 넘기면 SQL 이 `= NULL` 이 되어 **아무것도 못 찾는** 함정이 있다.
        // 그러면 REDEEM/VOID 의 멱등 가드가 죽은 코드가 되고, 재기록 시도가 DB UNIQUE 에 걸려
        // 500 이 된다(멱등이어야 할 자리에서 오류). 프레임워크 동작을 추측하지 않고 여기서 확인한다.
        approve(10000, "led-9")
        val entry = entryRepository.findAll().first { it.entryType == "REDEEM" }

        val found =
            entryRepository.findByEntryTypeAndTransactionIdAndRefundId(
                "REDEEM",
                entry.transactionId,
                null,
            )
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(entry.id)
    }

    @Test
    fun `★★월을 넘는 환불도 대사가 맞는다(같은 달만 보면 안 보이는 결함)`() {
        // 실제로 넣었다가 잡은 결함이다. 반대 분개를 **환불한 날**에 달면, 장부는 원 거래의 달에서
        // 금액이 소급 감소하는데(V36 이 amount 를 가변으로 만들었다) 원장만 다음 달에 달려
        // **두 달이 모두** 어긋난다 — 대사가 매달 허위 경보를 내고 아무도 안 보게 된다.
        // 승인·환불이 같은 달인 케이스만 있으면 이 결함이 통과한다.
        val authId = approve(10000, "led-10")

        // 거래를 지난달로 옮긴다(월 경계를 실제로 넘기기 위해).
        val transaction = transactionRepository.findByAuthId(authId)!!
        val lastMonth = Instant.now().minus(40, ChronoUnit.DAYS)
        transaction.approvedAt = lastMonth
        transactionRepository.saveAndFlush(transaction)
        // 소비 이벤트도 함께 옮긴다 — 프로덕션에서 셋은 **같은 approvedAt** 을 쓰므로, 하나만 옮기면
        // 제품 결함이 아니라 재현의 부정확이 잡힌다(실제로 처음엔 이걸 빠뜨려 건수축이 어긋났다).
        jdbcTemplate.update("UPDATE consumption_events SET occurred_at = ? WHERE org_id = ?", Timestamp.from(lastMonth), orgId)
        // 원 분개도 그 시각으로 옮긴다(승인 시점에 기록된 것을 재현).
        entryRepository.findAll().forEach { e ->
            postingRepository.findAll().filter { it.entryId == e.id }.forEach { pst ->
                jdbcTemplate.update("UPDATE ledger_postings SET occurred_at = ? WHERE id = ?", Timestamp.from(lastMonth), pst.id)
            }
            jdbcTemplate.update("UPDATE ledger_entries SET occurred_at = ? WHERE id = ?", Timestamp.from(lastMonth), e.id)
        }

        // 이번 달에 환불한다.
        refund(authId, 4000, "led-rf-10")

        val lastPeriod = YearMonth.from(lastMonth.atZone(ZoneOffset.UTC)).toString()
        // 지난달: 원장 6,000 = 장부 6,000(소급 반영).
        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId).param("period", lastPeriod))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(true))
        // 이번 달: 둘 다 0.
        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/reconciliation", orgId).param("period", period))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(true))
    }

    // ---- helpers ----

    private fun balance(account: LedgerAccount): Long =
        postingRepository.balance(
            orgId,
            account.name,
            Instant.now().minus(365, ChronoUnit.DAYS),
            Instant.now().plus(365, ChronoUnit.DAYS),
        )

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

    private fun voidTransaction(authId: String) {
        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/void", authId)
                    .header("Authorization", "Bearer ${merchantToken()}"),
            ).andExpect(status().isOk)
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
        val body =
            login(memberEmail)
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
