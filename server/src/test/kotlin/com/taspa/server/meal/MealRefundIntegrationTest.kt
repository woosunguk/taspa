package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.consumption.ConsumptionEventRepository
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 부분 환불 통합 테스트.
 *
 * ★이 기능의 전부는 **분담 재계산**이다. 조직 12,000 + 개인 3,000 인 거래에서 3,000을 환불하면
 * 남은 12,000은 한도 안에 온전히 들어가므로 조직이 전액 부담하고 **개인이 3,000을 돌려받아야** 한다.
 * 반대로 돌려주면 직원이 자기 돈을 못 받고 회사만 이득을 본다 — 숫자가 맞아 보여도 사람에게 틀린다.
 *
 * 정책: 1식 한도 12,000 / 월 200,000. 끼니창은 하루 전체로 깔아 어느 시각에 돌아도 열려 있게 한다.
 */
@TestPropertySource(properties = ["taspa.meal.qr-issue-cooldown=0s"])
class MealRefundIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var qrTokenRepository: MealQrTokenRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var refundRepository: MealRefundRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val memberEmail = "refund-member@example.com"
    private lateinit var orgId: UUID
    private lateinit var merchantId: UUID
    private lateinit var otherMerchantId: UUID

    @BeforeEach
    fun setUp() {
        refundRepository.deleteAll()
        eventRepository.deleteAll()
        transactionRepository.deleteAll()
        qrTokenRepository.deleteAll()
        merchantRepository.deleteAll()
        policyRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId = organizationRepository.save(Organization(slug = "refund", name = "환불 테스트")).id!!
        val user =
            userRepository.save(
                User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        membershipRepository.save(OrgMembership(orgId = orgId, userId = user.id!!, role = "MEMBER"))
        merchantId = merchantRepository.save(Merchant(name = "구내식당")).id!!
        otherMerchantId = merchantRepository.save(Merchant(name = "다른 식당")).id!!
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
    fun `★한도 초과분이 있는 거래를 부분 환불하면 개인부담이 먼저 돌아간다`() {
        // 15,000 결제 → 조직 12,000(한도) + 개인 3,000.
        val authId = approve(15000, "tx-1")
        assertSplit(authId, orgPaid = 12000, selfPaid = 3000)

        // 3,000 환불 → 남은 12,000은 한도 안이므로 조직이 전액 부담, 개인은 0.
        refund(authId, 3000, "rf-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(12000))
            .andExpect(jsonPath("$.selfPaidMinor").value(0))

        val record = refundRepository.findByMerchantIdAndPosRefundId(merchantId, "rf-1")!!
        // ★돈이 누구에게 돌아갔는가 — 이 두 줄이 이 기능의 핵심 단언이다.
        assertThat(record.selfRefundedMinor).isEqualTo(3000)
        assertThat(record.orgRefundedMinor).isZero()
    }

    @Test
    fun `개인부담을 넘는 환불은 나머지가 조직으로 돌아간다`() {
        val authId = approve(15000, "tx-2")

        // 14,000 환불 → 남은 1,000. 개인 3,000 전액 + 조직 11,000.
        refund(authId, 14000, "rf-2")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(1000))
            .andExpect(jsonPath("$.selfPaidMinor").value(0))

        val record = refundRepository.findByMerchantIdAndPosRefundId(merchantId, "rf-2")!!
        assertThat(record.selfRefundedMinor).isEqualTo(3000)
        assertThat(record.orgRefundedMinor).isEqualTo(11000)
    }

    @Test
    fun `한도 안 거래의 환불은 전액 조직으로 돌아간다`() {
        val authId = approve(10000, "tx-3")
        assertSplit(authId, orgPaid = 10000, selfPaid = 0)

        refund(authId, 4000, "rf-3")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(6000))

        val record = refundRepository.findByMerchantIdAndPosRefundId(merchantId, "rf-3")!!
        assertThat(record.orgRefundedMinor).isEqualTo(4000)
        assertThat(record.selfRefundedMinor).isZero()
    }

    @Test
    fun `여러 번 부분 환불할 수 있고 누계가 남는다`() {
        val authId = approve(10000, "tx-4")
        refund(authId, 3000, "rf-4a").andExpect(status().isOk)
        refund(authId, 2000, "rf-4b")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(5000))

        val transaction = transactionRepository.findByAuthId(authId)!!
        assertThat(transaction.refundedMinor).isEqualTo(5000)
        // 원금은 현재값 + 환불누계로 복원된다(별도 컬럼을 두지 않은 이유).
        assertThat(transaction.originalAmountMinor()).isEqualTo(10000)
        assertThat(refundRepository.findByTransactionIdOrderByRefundedAtAsc(transaction.id!!)).hasSize(2)
    }

    @Test
    fun `★직원 이력이 환불 사실과 원금을 함께 보여준다(줄어든 금액만 남기지 않는다)`() {
        // 이 단언이 지키는 것: 환불은 amountMinor·selfPaidMinor 를 **소급 변경**한다. 이력에 12,000원만
        // 남으면 영수증(15,000)과 다른 숫자를 화면이 설명하지 못하고, 사용자는 기록이 틀렸다고 의심한다.
        val authId = approve(15000, "tx-hist")
        refund(authId, 3000, "rf-hist").andExpect(status().isOk)

        login(memberEmail)
            .perform(get("/api/meal/transactions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].authId").value(authId))
            // 현재값 — 환불이 반영된 뒤의 금액.
            .andExpect(jsonPath("$[0].amountMinor").value(12000))
            .andExpect(jsonPath("$[0].selfPaidMinor").value(0))
            // ★"왜 줄었는가"를 답하는 세 값.
            .andExpect(jsonPath("$[0].refundedMinor").value(3000))
            .andExpect(jsonPath("$[0].selfRefundedMinor").value(3000))
            .andExpect(jsonPath("$[0].originalAmountMinor").value(15000))
            .andExpect(jsonPath("$[0].lastRefundedAt").isNotEmpty)
    }

    @Test
    fun `환불이 없는 거래의 이력에는 환불 필드가 0 이고 원금이 결제액과 같다`() {
        // 대조군 — 없으면 위 테스트가 "항상 채워지는 값"을 봤을 뿐인지 구별되지 않는다.
        val authId = approve(9000, "tx-clean")

        login(memberEmail)
            .perform(get("/api/meal/transactions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].authId").value(authId))
            .andExpect(jsonPath("$[0].refundedMinor").value(0))
            .andExpect(jsonPath("$[0].selfRefundedMinor").value(0))
            .andExpect(jsonPath("$[0].originalAmountMinor").value(9000))
            .andExpect(jsonPath("$[0].lastRefundedAt").doesNotExist())
    }

    @Test
    fun `★전액 환불은 취소와 같은 상태로 수렴한다(소비 이벤트도 제외된다)`() {
        // 두 경로가 다른 결과를 내면 "전액 환불했는데 예측에는 남아 있다"가 된다.
        val authId = approve(10000, "tx-5")
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgId, "payment", authId)!!.status)
            .isEqualTo("CONFIRMED")

        refund(authId, 10000, "rf-5")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VOIDED"))

        val transaction = transactionRepository.findByAuthId(authId)!!
        assertThat(transaction.status).isEqualTo("VOIDED")
        assertThat(transaction.voidedAt).isNotNull()
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgId, "payment", authId)!!.status)
            .isEqualTo("VOIDED")
    }

    @Test
    fun `부분 환불은 소비 이벤트를 건드리지 않는다(식사는 실제로 있었다)`() {
        // 소비 이벤트는 **인분 수**를 나른다 — 금액이 줄어도 먹은 사람 수는 그대로다.
        val authId = approve(10000, "tx-6")
        refund(authId, 4000, "rf-6").andExpect(status().isOk)

        val event = eventRepository.findByOrgIdAndSourceAndExternalId(orgId, "payment", authId)!!
        assertThat(event.status).isEqualTo("CONFIRMED")
        assertThat(event.quantity).isEqualTo(1)
    }

    @Test
    fun `★같은 환불키 재전송은 이중 환불이 되지 않는다`() {
        // 통신 단절 후 재시도가 이중 환불이면 그건 그대로 회사·직원의 손실이다.
        val authId = approve(10000, "tx-7")
        refund(authId, 3000, "rf-7").andExpect(status().isOk)
        refund(authId, 3000, "rf-7")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(7000))

        assertThat(transactionRepository.findByAuthId(authId)!!.refundedMinor).isEqualTo(3000)
        assertThat(refundRepository.count()).isEqualTo(1)
    }

    @Test
    fun `남은 금액을 초과하는 환불은 거절된다`() {
        val authId = approve(10000, "tx-8")
        refund(authId, 10001, "rf-8a").andExpect(status().isBadRequest)
        refund(authId, 6000, "rf-8b").andExpect(status().isOk)
        // 이미 6,000을 돌려줬으니 남은 건 4,000뿐이다.
        refund(authId, 5000, "rf-8c").andExpect(status().isBadRequest)
    }

    @Test
    fun `이미 취소된 거래는 환불할 수 없다`() {
        val authId = approve(10000, "tx-9")
        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/void", authId).header("Authorization", "Bearer ${merchantToken()}"),
            ).andExpect(status().isOk)

        refund(authId, 1000, "rf-9").andExpect(status().isBadRequest)
    }

    @Test
    fun `0 이하 금액은 거절된다`() {
        val authId = approve(10000, "tx-10")
        refund(authId, 0, "rf-10a").andExpect(status().isBadRequest)
        refund(authId, -5000, "rf-10b").andExpect(status().isBadRequest)
    }

    @Test
    fun `★타 가맹은 남의 거래를 환불할 수 없다(존재 여부도 노출하지 않는다)`() {
        val authId = approve(10000, "tx-11")
        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/refund", authId)
                    .header("Authorization", "Bearer ${merchantToken(otherMerchantId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":1000,"posRefundId":"rf-11"}"""),
            ).andExpect(status().isNotFound)

        assertThat(transactionRepository.findByAuthId(authId)!!.refundedMinor).isZero()
    }

    @Test
    fun `세션 인증으로는 환불할 수 없다(사람이 아니라 단말의 조작이다)`() {
        val authId = approve(10000, "tx-12")
        login(memberEmail)
            .perform(
                post("/api/merchant/redeem/{authId}/refund", authId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":1000,"posRefundId":"rf-12"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `★환불은 월 한도를 되돌려 다시 쓸 수 있게 한다`() {
        // 한도를 다 쓴 뒤 환불하면 그만큼 다시 쓸 수 있어야 한다 — 안 그러면 잘못 찍힌 결제 하나가
        // 그 달 내내 직원의 식대를 잡아먹는다.
        val big = approve(12000, "tx-13a")
        assertSplit(big, orgPaid = 12000, selfPaid = 0)

        refund(big, 12000, "rf-13").andExpect(status().isOk)

        // 전액 환불(=취소) 후 다시 결제하면 조직이 전액 부담해야 한다.
        val again = approve(12000, "tx-13b")
        assertSplit(again, orgPaid = 12000, selfPaid = 0)
    }

    @Test
    fun `★★승인 후 한도가 내려가도 환불이 직원 부담을 늘리지 않는다`() {
        // 실제로 넣었다가 잡은 결함이다. 환불 후 금액으로 분담을 "다시 계산"하기만 하면, 승인과 환불
        // 사이에 정책이 하향된 경우 그 차이가 통째로 조직 환불로 잡히고 **직원 부담이 늘어난다**
        // (실측: 3,000 → 9,000, selfRefunded = −6,000). 환불이 직원에게 청구서를 보내는 셈이다.
        val authId = approve(15000, "tx-14")
        assertSplit(authId, orgPaid = 12000, selfPaid = 3000)

        // 조직이 1식 한도를 12,000 → 5,000 으로 낮춘다(정상적인 운영 행위다).
        val policy = policyRepository.findById(orgId).orElseThrow()
        policy.perMealLimitMinor = 5000
        policyRepository.saveAndFlush(policy)

        refund(authId, 1000, "rf-14").andExpect(status().isOk)

        val record = refundRepository.findByMerchantIdAndPosRefundId(merchantId, "rf-14")!!
        // 두 환불의 합은 정확히 요청 금액이고, 어느 쪽도 음수가 아니다.
        assertThat(record.orgRefundedMinor + record.selfRefundedMinor).isEqualTo(1000)
        assertThat(record.orgRefundedMinor).isNotNegative()
        assertThat(record.selfRefundedMinor).isNotNegative()

        val after = transactionRepository.findByAuthId(authId)!!
        // ★핵심: 직원 부담은 **줄기만 한다**. 정책 변경이 소급해서 직원에게 청구되지 않는다.
        assertThat(after.selfPaidMinor).isLessThanOrEqualTo(3000)
        assertThat(after.orgPaidMinor()).isLessThanOrEqualTo(12000)
        assertThat(after.orgPaidMinor() + after.selfPaidMinor).isEqualTo(14000)
    }

    @Test
    fun `월 한도가 내려가도 마찬가지다`() {
        val authId = approve(15000, "tx-15")

        val policy = policyRepository.findById(orgId).orElseThrow()
        policy.monthlyCapMinor = 1000 // 이미 쓴 12,000 보다 낮게
        policyRepository.saveAndFlush(policy)

        refund(authId, 2000, "rf-15").andExpect(status().isOk)

        val record = refundRepository.findByMerchantIdAndPosRefundId(merchantId, "rf-15")!!
        assertThat(record.orgRefundedMinor + record.selfRefundedMinor).isEqualTo(2000)
        assertThat(record.selfRefundedMinor).isNotNegative()
        val after = transactionRepository.findByAuthId(authId)!!
        assertThat(after.selfPaidMinor).isLessThanOrEqualTo(3000)
        assertThat(after.orgPaidMinor() + after.selfPaidMinor).isEqualTo(13000)
    }

    @Test
    fun `★환불 응답이 계산원에게 돌려줄 현금을 알려 준다`() {
        // 단말은 환불 후의 selfPaidMinor 로 이 값을 유추할 수 없다 — 그건 "앞으로 받을 금액"이지
        // "지금 돌려줄 금액"이 아니다(손님은 이미 옛 금액을 냈다). 유추가 틀리면 현금이 틀린다.
        val authId = approve(15000, "tx-16")

        refund(authId, 3000, "rf-16")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.selfRefundedMinor").value(3000))
            .andExpect(jsonPath("$.orgRefundedMinor").value(0))
            // 대조: 환불 후 개인부담은 0 이다 — 이 값으로는 "3,000 을 돌려줘야 한다"를 알 수 없다.
            .andExpect(jsonPath("$.selfPaidMinor").value(0))

        // 재전송도 **같은 분담**을 돌려준다 — 다른 답이면 계산원이 현금을 두 번 센다.
        refund(authId, 3000, "rf-16")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.selfRefundedMinor").value(3000))
    }

    @Test
    fun `승인 응답에는 환불 분담 필드가 없다`() {
        // 있으면 단말이 승인 화면에서도 "돌려줄 금액"을 그리게 된다.
        val qr = issueQr()
        mockMvc
            .perform(
                post("/api/merchant/redeem")
                    .header("Authorization", "Bearer ${merchantToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$qr","amountMinor":10000,"posTxnId":"tx-17"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.selfRefundedMinor").doesNotExist())
            .andExpect(jsonPath("$.orgRefundedMinor").doesNotExist())
    }

    // ---- helpers ----

    private fun assertSplit(
        authId: String,
        orgPaid: Long,
        selfPaid: Long,
    ) {
        val transaction = transactionRepository.findByAuthId(authId)!!
        assertThat(transaction.orgPaidMinor()).isEqualTo(orgPaid)
        assertThat(transaction.selfPaidMinor).isEqualTo(selfPaid)
    }

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
    ): ResultActions =
        mockMvc.perform(
            post("/api/merchant/redeem/{authId}/refund", authId)
                .header("Authorization", "Bearer ${merchantToken()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":$amountMinor,"posRefundId":"$posRefundId"}"""),
        )

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

    private fun merchantToken(id: UUID = merchantId): String {
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
                .claim("merchant_id", id.toString())
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
