package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantMember
import com.taspa.server.domain.meal.MerchantMemberRepository
import com.taspa.server.domain.meal.MerchantMemberStatus
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.meal.MerchantStatus
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
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 가맹 관리자 콘솔(`/api/merchant-console` 이하, 사람 세션 전용 조회 표면) 테넌시 불변식 통합 테스트.
 *  - 인가는 정책 엔진 하나가 판정한다(MerchantConsoleController.authorize) — 요청 경로 가맹점의
 *    활성 관리 멤버십(멤버십 ACTIVE ∧ 역할 MERCHANT_ADMIN ∧ 매장 ACTIVE)일 때만 허용.
 *  - 존재하지 않는 merchantId 도 비관리자에겐 403 으로 수렴한다(멤버십이 없어 엔진이 거부) — 404 로
 *    새지 않아야 열거 공격(가맹 id 존재 여부 프로빙)이 막힌다.
 *  - 결제 승인(redeem)은 이 콘솔에 없다 — /api/merchant/redeem 은 별도 STATELESS M2M 전용 체인(@Order(-1))
 *    이라 사람 세션 쿠키는 그 체인에 아예 인증되지 않는다(401). 계정 탈취가 곧 무단 결제가 되지 않는다.
 */
// 정산 명세 테스트가 한 손님으로 연속 결제를 만든다 — QR 발급 쿨다운(기본 10초)은 여기서 관심사가
// 아니다(그 규칙은 MealQrIntegrationTest 가 지킨다).
@TestPropertySource(properties = ["taspa.meal.qr-issue-cooldown=0s"])
class MerchantConsoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var merchantMemberRepository: MerchantMemberRepository

    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var qrTokenRepository: MealQrTokenRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

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
    private lateinit var orgId: UUID
    private lateinit var adminAEmail: String
    private lateinit var adminAId: UUID
    private lateinit var merchantAId: UUID
    private lateinit var merchantBId: UUID

    @BeforeEach
    fun setUp() {
        transactionRepository.deleteAll()
        eventRepository.deleteAll()
        qrTokenRepository.deleteAll()
        policyRepository.deleteAll()
        merchantMemberRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId = organizationRepository.save(Organization(slug = "merch-con", name = "Merchant Console Org")).id!!
        savePolicy() // 하루 전체 창(결정적 판정) — redeem 이 필요한 테스트(개인정보 비노출)에서 재사용.

        adminAEmail = "merchant-console-admin-a@example.com"
        adminAId =
            userRepository
                .save(
                    User(email = adminAEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        merchantAId = merchantRepository.save(Merchant(name = "가맹 A")).id!!
        merchantBId = merchantRepository.save(Merchant(name = "가맹 B")).id!!
        merchantMemberRepository.save(
            MerchantMember(merchantId = merchantAId, userId = adminAId, role = "MERCHANT_ADMIN", status = "ACTIVE"),
        )
    }

    // ---- 1. 자기 매장만 보인다 ----

    @Test
    fun `타 가맹점 거래 조회는 403 이다(자기 매장만 관리)`() {
        val session = login(adminAEmail)
        session
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantBId))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `존재하지 않는 merchantId 도 비관리자에게 403 이다(열거 방지 — 404 아님)`() {
        val session = login(adminAEmail)
        session
            .perform(get("/api/merchant-console/{merchantId}/transactions", UUID.randomUUID()))
            .andExpect(status().isForbidden)
    }

    // ---- 2. mine 은 자기 멤버십만 ----

    @Test
    fun `mine 은 자기 멤버십의 가맹점만 반환한다(다른 가맹 미포함)`() {
        // adminB 는 merchantB 의 관리자 — adminA 의 결과에 새어 들어오면 안 된다.
        val adminB =
            userRepository.save(
                User(email = "merchant-console-admin-b@example.com", passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        merchantMemberRepository.save(
            MerchantMember(merchantId = merchantBId, userId = adminB.id!!, role = "MERCHANT_ADMIN", status = "ACTIVE"),
        )

        val session = login(adminAEmail)
        val body =
            session
                .perform(get("/api/merchant-console/mine"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val list = objectMapper.readTree(body).get("merchants")
        assertThat(list.size()).isEqualTo(1)
        assertThat(list[0].get("merchantId").asText()).isEqualTo(merchantAId.toString())
    }

    // ---- 3. 매장이 ACTIVE 가 아니면 잔여 멤버십으로도 열리지 않는다 ----

    @Test
    fun `매장이 SUSPENDED 면 잔여 멤버십이 있어도 거래 조회가 403 이다`() {
        suspendMerchantA()
        val session = login(adminAEmail)
        session
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantAId))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `매장이 SUSPENDED 면 mine 목록에서도 사라진다`() {
        suspendMerchantA()
        val session = login(adminAEmail)
        val body =
            session
                .perform(get("/api/merchant-console/mine"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val response = objectMapper.readTree(body)
        // 진입 가능 집합에서는 사라진다(= 인가 조건과 같은 집합).
        assertThat(response.get("merchants").size()).isZero()
        /*
         * ★그러나 **존재까지 지우지는 않는다.** 지우면 담당자로 지정된 사람에게 매장이 없는 것과
         * 똑같이 보여, 신규 가맹 온보딩이 아무 설명 없이 멈춘다(등록 기본값이 PENDING 이던 시절의
         * 기본 경로였다). 사유를 갖는 별도 목록으로 알린다.
         */
        val blocked = response.get("blocked")
        assertThat(blocked.size()).isEqualTo(1)
        assertThat(blocked[0].get("merchantId").asText()).isEqualTo(merchantAId.toString())
        assertThat(blocked[0].get("status").asText()).isEqualTo("SUSPENDED")
    }

    // ---- 4. 멤버십이 SUSPENDED 면 접근 불가 ----

    @Test
    fun `멤버십이 SUSPENDED 면 거래 조회가 403 이다`() {
        val membership = merchantMemberRepository.findByMerchantIdAndUserId(merchantAId, adminAId)!!
        membership.status = MerchantMemberStatus.SUSPENDED.name
        merchantMemberRepository.save(membership)

        val session = login(adminAEmail)
        session
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantAId))
            .andExpect(status().isForbidden)
    }

    // ---- 5. 사람은 절대 결제할 수 없다(기계 전용 불변식) ----

    @Test
    fun `가맹 관리자 세션 쿠키로는 redeem 이 승인되지 않는다(STATELESS 베어러 전용 체인)`() {
        // /api/merchant 이하는 SecurityConfig @Order(-1) 의 STATELESS 리소스서버 체인이라 세션 쿠키는
        // 이 체인에서 아예 인증되지 않는다 — Authorization 헤더가 없으니 401(핵심 안전 장치).
        val session = login(adminAEmail)
        session
            .perform(
                post("/api/merchant/redeem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"x","amountMinor":10000,"posTxnId":"console-sess-1"}"""),
            ).andExpect(status().isUnauthorized)
    }

    // ---- 6. 위임 베어러(사용자 토큰)는 콘솔에서 거부 ----

    @Test
    fun `위임 베어러 토큰으로는 콘솔에 접근할 수 없다(로그인 세션 전용)`() {
        // /api/merchant-console 이하는 기본 체인(anyRequest().authenticated(), oauth2ResourceServer 미설정)
        // 소속이라 Authorization 헤더는 이 체인에서 파싱되지 않고 요청은 익명으로 취급된다 — 그 결과
        // API 경로 진입점(ApiAuthenticationEntryPoint)이 401 을 돌려준다.
        // 즉 컨트롤러의 JwtAuthenticationToken 하드 게이트(403)에 도달하는 요청 경로 자체가 없다.
        // 상태코드가 무엇이든 **위임 베어러로는 콘솔 데이터에 도달하지 못한다**는 것이 여기서 잠그는 불변식이다.
        val token = userBearerToken(adminAId, "openid profile")
        mockMvc
            .perform(
                get("/api/merchant-console/mine").header("Authorization", "Bearer $token"),
            ).andExpect(status().isUnauthorized)
    }

    // ---- 7. 거래 로그 응답에 손님 개인정보가 없다 ----

    @Test
    fun `거래 로그 응답에 손님의 email·userId 가 포함되지 않는다`() {
        val customerEmail = "merchant-console-customer@example.com"
        val customerId =
            userRepository
                .save(
                    User(email = customerEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = customerId, role = "MEMBER"))

        // 실제 승인 거래를 하나 만든다 — redeem 은 M2M(가맹 결속 토큰) 전용.
        val customerSession = login(customerEmail)
        val qrBody =
            customerSession
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val qrToken = objectMapper.readTree(qrBody).get("token").asText()

        mockMvc
            .perform(
                post("/api/merchant/redeem")
                    .header("Authorization", "Bearer ${merchantToken(merchantAId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$qrToken","amountMinor":9000,"posTxnId":"console-priv-1"}"""),
            ).andExpect(status().isOk)

        val session = login(adminAEmail)
        val body =
            session
                .perform(get("/api/merchant-console/{merchantId}/transactions", merchantAId))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        // sanity: 데이터가 실제로 실려 있어야 아래 부재 단언이 공허하지 않다.
        assertThat(body).contains("console-priv-1")
        assertThat(body).doesNotContain(customerEmail)
        assertThat(body).doesNotContain(customerId.toString())
    }

    // ---- 7b. 환불이 매장 대사에 드러난다 ----

    @Test
    fun `★거래 로그가 환불 누계와 원금을 함께 보여준다(줄어든 결제액만 남기지 않는다)`() {
        // 매장은 이 화면으로 자기 POS 기록·영수증과 대사한다. 부분 환불이 amountMinor 를 소급 변경하는데
        // 원금·환불액이 없으면 "우리 기록은 9,000인데 여기는 6,000"이 되고 대사가 성립하지 않는다.
        val customerEmail = "merchant-console-refund@example.com"
        val customerId =
            userRepository
                .save(
                    User(email = customerEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = customerId, role = "MEMBER"))

        val customerSession = login(customerEmail)
        val qrBody =
            customerSession
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val qrToken = objectMapper.readTree(qrBody).get("token").asText()

        val redeemBody =
            mockMvc
                .perform(
                    post("/api/merchant/redeem")
                        .header("Authorization", "Bearer ${merchantToken(merchantAId)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"token":"$qrToken","amountMinor":9000,"posTxnId":"console-refund-1"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val authId = objectMapper.readTree(redeemBody).get("authId").asText()

        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/refund", authId)
                    .header("Authorization", "Bearer ${merchantToken(merchantAId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":3000,"posRefundId":"console-rf-1"}"""),
            ).andExpect(status().isOk)

        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantAId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows[0].posTxnId").value("console-refund-1"))
            .andExpect(jsonPath("$.rows[0].amountMinor").value(6000))
            .andExpect(jsonPath("$.rows[0].refundedMinor").value(3000))
            .andExpect(jsonPath("$.rows[0].originalAmountMinor").value(9000))
            .andExpect(jsonPath("$.rows[0].refundCount").value(1))
            // 환불에서도 손님 개인정보는 늘지 않는다 — 매장이 아는 것은 금액과 조직뿐이다.
            .andExpect(jsonPath("$.rows[0].orgRefundedMinor").value(3000))
            .andExpect(jsonPath("$.rows[0].lastRefundedAt").isNotEmpty)
    }

    // ---- 7c. 월 정산 명세 ----

    @Test
    fun `★정산 명세는 조직 부담만 지급 대상으로 잡고 개인 부담은 따로 둔다`() {
        // 개인부담은 손님이 계산대에서 **이미 냈다**. 지급액에 더하면 매장이 받을 돈을 두 배로 기대한다.
        val customerEmail = "merchant-settle-customer@example.com"
        val customerId =
            userRepository
                .save(
                    User(email = customerEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = customerId, role = "MEMBER"))

        // 1식 한도(기본 12,000)를 넘겨 개인부담이 생기게 한다: 15,000 → 조직 12,000 + 개인 3,000.
        approveFor(customerEmail, 15000, "settle-1")
        // 한도 안 거래 하나 더: 9,000 → 조직 9,000 + 개인 0.
        approveFor(customerEmail, 9000, "settle-2")

        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/settlement", merchantAId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.merchantId").value(merchantAId.toString()))
            .andExpect(jsonPath("$.approvedCount").value(2))
            // ★지급 대상 = 조직부담 합(12,000 + 9,000). 개인부담 3,000 은 여기 없다.
            .andExpect(jsonPath("$.payableMinor").value(21000))
            .andExpect(jsonPath("$.selfPaidTotalMinor").value(3000))
            .andExpect(jsonPath("$.refundedTotalMinor").value(0))
            .andExpect(jsonPath("$.lines.length()").value(1))
            .andExpect(jsonPath("$.lines[0].orgPaidMinor").value(21000))
            .andExpect(jsonPath("$.lines[0].approvedCount").value(2))
            // 창은 **매장 타임존** 앵커다(조직 달력을 빌리지 않는다).
            .andExpect(jsonPath("$.timezone").value("UTC"))
    }

    @Test
    fun `★전액 환불된 거래도 정산 명세의 환불 합계에 남는다(VOIDED 로 수렴해도 사라지지 않는다)`() {
        // 전액 환불은 거래를 VOIDED 로 만든다. 금액축과 환불축을 같은 status 필터로 묶으면 그 순간
        // 환불 10,000원이 명세에서 통째로 사라진다 — 매장이 "환불 3,000원"만 보게 되는 결함이었다.
        val customerEmail = "merchant-settle-full-refund@example.com"
        val customerId =
            userRepository
                .save(
                    User(email = customerEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = customerId, role = "MEMBER"))

        val fullAuthId = approveFor(customerEmail, 10000, "settle-full")
        val partialAuthId = approveFor(customerEmail, 8000, "settle-partial")
        refundOf(fullAuthId, 10000, "rf-full")
        refundOf(partialAuthId, 3000, "rf-partial")

        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/settlement", merchantAId))
            .andExpect(status().isOk)
            // 전액 환불 건은 승인에서 빠지고(VOIDED), 지급액은 부분 환불 후 남은 5,000 뿐이다.
            .andExpect(jsonPath("$.approvedCount").value(1))
            .andExpect(jsonPath("$.voidedCount").value(1))
            .andExpect(jsonPath("$.payableMinor").value(5000))
            // ★환불 합계는 **둘 다** 잡아야 한다(10,000 + 3,000).
            .andExpect(jsonPath("$.refundedTotalMinor").value(13000))
            .andExpect(jsonPath("$.lines[0].refundedMinor").value(13000))
    }

    @Test
    fun `★정산 CSV 는 화면과 같은 권한·같은 숫자를 내고 매장 이름의 수식은 무력화된다`() {
        // 매장·조직 이름은 사용자가 정한다 — 회계 담당자의 엑셀에서 실행되면 안 된다.
        val evilOrgId =
            organizationRepository
                .save(
                    Organization(slug = "csv-evil", name = "=HYPERLINK(\"http://evil\",\"click\")"),
                ).id!!
        val customerEmail = "merchant-csv-customer@example.com"
        val customerId =
            userRepository
                .save(
                    User(email = customerEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = evilOrgId, userId = customerId, role = "MEMBER"))
        policyRepository.save(
            MealPolicy(
                orgId = evilOrgId,
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
        approveIn(evilOrgId, customerEmail, 7000, "csv-1")

        val body =
            login(adminAEmail)
                .perform(get("/api/merchant-console/{merchantId}/settlement/csv", merchantAId))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Disposition", containsString("attachment;")))
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)

        // ★수식 문자로 **시작하지 않는다**(따옴표 안에서도) — 작은따옴표가 텍스트로 못박는다.
        assertThat(body).doesNotContain("\"=HYPERLINK")
        assertThat(body).contains("'=HYPERLINK")
        // 화면과 같은 숫자.
        assertThat(body).contains("7000")
        // 엑셀 한글 깨짐 방지 BOM.
        assertThat(body.first()).isEqualTo('\uFEFF')
    }

    @Test
    fun `타 가맹점의 정산 CSV 도 403 이다(형식이 권한을 우회하지 않는다)`() {
        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/settlement/csv", merchantBId))
            .andExpect(status().isForbidden)
        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/transactions/csv", merchantBId))
            .andExpect(status().isForbidden)
    }

    /** 지정한 org 로 승인한다(정산 CSV 테스트가 별도 조직을 쓰기 위해). */
    private fun approveIn(
        targetOrgId: UUID,
        customerEmail: String,
        amountMinor: Long,
        posTxnId: String,
    ) {
        val session = login(customerEmail)
        val qrBody =
            session
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$targetOrgId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val qrToken = objectMapper.readTree(qrBody).get("token").asText()
        mockMvc
            .perform(
                post("/api/merchant/redeem")
                    .header("Authorization", "Bearer ${merchantToken(merchantAId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$qrToken","amountMinor":$amountMinor,"posTxnId":"$posTxnId"}"""),
            ).andExpect(status().isOk)
    }

    @Test
    fun `타 가맹점의 정산 명세는 403 이다`() {
        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/settlement", merchantBId))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `정산 기간 형식이 틀리면 400 이다`() {
        login(adminAEmail)
            .perform(get("/api/merchant-console/{merchantId}/settlement", merchantAId).param("period", "이번달"))
            .andExpect(status().isBadRequest)
    }

    /** 손님 세션으로 QR 을 받아 매장 A 의 M2M 토큰으로 승인한다(redeem 은 기계 전용). */
    private fun approveFor(
        customerEmail: String,
        amountMinor: Long,
        posTxnId: String,
    ): String {
        val session = login(customerEmail)
        val qrBody =
            session
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val qrToken = objectMapper.readTree(qrBody).get("token").asText()
        val body =
            mockMvc
                .perform(
                    post("/api/merchant/redeem")
                        .header("Authorization", "Bearer ${merchantToken(merchantAId)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"token":"$qrToken","amountMinor":$amountMinor,"posTxnId":"$posTxnId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("authId").asText()
    }

    /** 매장 A 의 M2M 토큰으로 환불한다(승인과 같은 기계 신원). */
    private fun refundOf(
        authId: String,
        amountMinor: Long,
        posRefundId: String,
    ) {
        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/refund", authId)
                    .header("Authorization", "Bearer ${merchantToken(merchantAId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":$amountMinor,"posRefundId":"$posRefundId"}"""),
            ).andExpect(status().isOk)
    }

    // ---- 8. 조회 창 상한이 조용히 자르지 않는다 ----

    @Test
    fun `92일을 넘는 조회 창은 windowTruncated=true 와 실효 from 을 드러낸다`() {
        // Merchant 기본 timezone=UTC — 매장-로컬 날짜 산술을 UTC 로 재현해 실효 from 을 결정적으로 계산한다.
        val today = LocalDate.now(ZoneOffset.UTC)
        val requestedFrom = today.minusDays(120) // 92일 상한을 넘는 과거
        val expectedEffectiveFrom = today.minusDays(91) // MAX_WINDOW_DAYS(92) - 1

        val session = login(adminAEmail)
        val body =
            session
                .perform(
                    get("/api/merchant-console/{merchantId}/transactions", merchantAId)
                        .param("from", requestedFrom.toString())
                        .param("to", today.toString()),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.windowTruncated").value(true))
                .andReturn()
                .response.contentAsString

        val json = objectMapper.readTree(body)
        assertThat(json.get("requestedFrom").asText()).isEqualTo(requestedFrom.toString())
        assertThat(json.get("from").asText()).isEqualTo(expectedEffectiveFrom.toString())
    }

    // ---- 헬퍼 ----

    private fun suspendMerchantA() {
        val merchant = merchantRepository.findById(merchantAId).orElseThrow()
        merchant.status = MerchantStatus.SUSPENDED.name
        merchantRepository.save(merchant)
    }

    /** 결정적 정책 저장 — 창을 하루 전체로 깔아 실행 시각과 무관하게 redeem 이 열려 있게 한다(MealRedeemIntegrationTest 와 동일 근거). */
    private fun savePolicy() {
        policyRepository.save(
            MealPolicy(
                orgId = orgId,
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
    }

    private fun merchantToken(merchantId: UUID): String {
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

    /** 사용자(위임) 베어러 토큰 모사 — subject=users.id(OrgConsoleApiIntegrationTest 와 동일 패턴). */
    private fun userBearerToken(
        userId: UUID,
        scope: String,
    ): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", scope)
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
