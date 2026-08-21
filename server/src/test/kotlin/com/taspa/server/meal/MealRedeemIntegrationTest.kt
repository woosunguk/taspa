package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealQrToken
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.meal.MerchantStatus
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.Site
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.JwkStorageService
import com.taspa.server.token.TokenCustomizerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * 식권 redeem/void(L1) 통합 테스트 — 가맹 M2M 경로.
 *  - 인가: meal.redeem scope + merchant_id 클레임 결속 + 가맹 ACTIVE (모두 fail-closed). 세션은 401.
 *  - 토큰: 만료/위조/이중사용 거절(FOR UPDATE 경로), 단일사용.
 *  - 정책: 끼니창 밖 거절, 일 횟수 초과 거절, per-meal/월 cap 초과분은 개인부담(selfPaid) 분리 승인.
 *  - 장부↔소비 seam: 거래와 소비 이벤트(source=payment, external_id=auth_id, site 귀속)가 원자적으로 남고,
 *    void 는 같은 멱등키의 VOIDED full-replace 로 집계에서 제외된다.
 *
 * 끼니창 판정을 결정적으로 만들기 위해 정책 행의 창을 하루 전체(00:00~08:00/08:00~16:00/16:00~23:59:59.999999,
 * org 타임존 UTC)로 깔아 어느 시각에 돌아도 창이 열려 있게 한다. 창 밖 거절은 zero-width 창으로 검증한다.
 * window 단언은 서버 응답의 mealWindow 를 기준으로 한다 — 테스트가 시각을 따로 다시 읽으면 창 경계
 * (08:00/16:00 UTC)를 사이에 둔 실행에서 플레이크가 난다.
 * QR 발급 쿨다운은 0 으로 꺼서(일 횟수 테스트가 연속 발급) 발급 자체 검증(MealQrIntegrationTest)과 분리한다.
 */
@TestPropertySource(properties = ["taspa.meal.qr-issue-cooldown=0s"])
class MealRedeemIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var siteRepository: SiteRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var qrTokenRepository: MealQrTokenRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgId: UUID
    private lateinit var memberEmail: String
    private lateinit var memberId: UUID
    private lateinit var siteId: UUID
    private lateinit var merchantId: UUID
    private lateinit var otherMerchantId: UUID

    @BeforeEach
    fun setUp() {
        transactionRepository.deleteAll()
        qrTokenRepository.deleteAll()
        eventRepository.deleteAll()
        policyRepository.deleteAll()
        merchantRepository.deleteAll()
        siteRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId = organizationRepository.save(Organization(slug = "meal-rd", name = "Meal Redeem Org")).id!!
        memberEmail = "meal-rd-member@example.com"
        memberId =
            userRepository
                .save(
                    User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = memberId, role = "MEMBER"))
        siteId = siteRepository.save(Site(orgId = orgId, name = "본사 구내식당")).id!!
        merchantId = merchantRepository.save(Merchant(name = "본사 식당", siteId = siteId)).id!!
        otherMerchantId = merchantRepository.save(Merchant(name = "다른 식당")).id!!
        savePolicy() // 하루 전체 창(결정적 판정) + 기본 한도(per-meal 12000·일 1회·월 200000)
    }

    // ---- redeem 정상 + seam ----

    @Test
    fun `redeem 은 거래와 소비 이벤트를 같은 트랜잭션으로 남긴다(external_id=auth_id·site·window)`() {
        val qr = issueQr()
        val response =
            redeem(qr, 10000, "pos-ok-1", merchantToken())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.approvedAmountMinor").value(10000))
                .andExpect(jsonPath("$.selfPaidMinor").value(0))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn()
                .response.contentAsString
                .let { objectMapper.readTree(it) }
        val authId = response.get("authId").asText()

        val tx = transactionRepository.findByAuthId(authId)!!
        assertThat(tx.orgId).isEqualTo(orgId)
        assertThat(tx.userId).isEqualTo(memberId)
        assertThat(tx.merchantId).isEqualTo(merchantId)
        // 서버가 판정한 창(응답)과 저장된 거래가 일치하는지 단언한다 — 시각을 다시 읽는 재판정은 경계 플레이크.
        assertThat(tx.mealWindow).isEqualTo(response.get("mealWindow").asText())
        assertThat(tx.mealWindow).isIn("BREAKFAST", "LUNCH", "DINNER")

        // 소비 이벤트 seam — 거래 auth_id 가 멱등키(external_id)다.
        val event = eventRepository.findByOrgIdAndSourceAndExternalId(orgId, "payment", authId)!!
        assertThat(event.userSub).isEqualTo(memberId)
        assertThat(event.merchantId).isEqualTo(merchantId)
        assertThat(event.siteId).isEqualTo(siteId)
        assertThat(event.mealWindow).isEqualTo(tx.mealWindow)
        assertThat(event.status).isEqualTo("CONFIRMED")
        assertThat(event.quantity).isEqualTo(1)

        // QR 은 단일사용 마킹된다.
        assertThat(qrTokenRepository.findAll().single().usedAt).isNotNull()
    }

    // ---- 토큰 검증 ----

    @Test
    fun `만료된 토큰은 거절된다`() {
        val raw = SecureTokenGenerator.generateToken()
        qrTokenRepository.save(
            MealQrToken(
                tokenHash = SecureTokenGenerator.hashToken(raw),
                userId = memberId,
                orgId = orgId,
                expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            ),
        )
        redeem(raw, 10000, "pos-exp-1", merchantToken())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("QR_TOKEN_EXPIRED"))
        assertThat(transactionRepository.count()).isZero()
    }

    @Test
    fun `사용된 토큰의 재사용은 거절된다(단일사용)`() {
        val qr = issueQr()
        redeem(qr, 10000, "pos-dup-1", merchantToken()).andExpect(status().isOk)
        redeem(qr, 10000, "pos-dup-2", merchantToken())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("QR_TOKEN_ALREADY_USED"))
        assertThat(transactionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `위조(미존재) 토큰은 거절된다`() {
        redeem(SecureTokenGenerator.generateToken(), 10000, "pos-forged-1", merchantToken())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("QR_TOKEN_INVALID"))
    }

    // ---- 정책 평가 ----

    @Test
    fun `끼니창 밖이면 거절된다`() {
        // zero-width 창([start, end) 공집합) — 어느 시각에도 열리지 않는다.
        savePolicy(
            breakfast = LocalTime.NOON to LocalTime.NOON,
            lunch = LocalTime.NOON to LocalTime.NOON,
            dinner = LocalTime.NOON to LocalTime.NOON,
        )
        val qr = issueQr()
        redeem(qr, 10000, "pos-window-1", merchantToken())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("MEAL_WINDOW_CLOSED"))
        assertThat(transactionRepository.count()).isZero()
    }

    @Test
    fun `일 횟수(daily_meal_count) 초과는 거절된다`() {
        redeem(issueQr(), 8000, "pos-daily-1", merchantToken()).andExpect(status().isOk)
        redeem(issueQr(), 8000, "pos-daily-2", merchantToken())
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("DAILY_MEAL_LIMIT"))
        assertThat(transactionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `per-meal 한도 초과분은 개인부담으로 분리 승인된다`() {
        redeem(issueQr(), 15000, "pos-split-1", merchantToken())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(12000))
            .andExpect(jsonPath("$.selfPaidMinor").value(3000))
        val tx = transactionRepository.findAll().single()
        assertThat(tx.amountMinor).isEqualTo(15000)
        assertThat(tx.selfPaidMinor).isEqualTo(3000)
    }

    @Test
    fun `월 cap 잔여를 넘는 부분은 개인부담이 된다`() {
        savePolicy(dailyMealCount = 10, monthlyCapMinor = 20000)
        redeem(issueQr(), 12000, "pos-cap-1", merchantToken())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(12000))
        // 당월 조직 부담 12000 사용 → 잔여 8000. 12000 요청 → 조직 8000 + 개인 4000.
        redeem(issueQr(), 12000, "pos-cap-2", merchantToken())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(8000))
            .andExpect(jsonPath("$.selfPaidMinor").value(4000))
    }

    // ---- POS 멱등 ----

    @Test
    fun `같은 posTxnId 재전송은 기존 승인 결과를 재반환한다(새 거래 없음)`() {
        val qr = issueQr()
        val first =
            redeem(qr, 10000, "pos-idem-1", merchantToken())
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val firstAuthId = objectMapper.readTree(first).get("authId").asText()

        // 재전송 — 토큰은 이미 소비됐지만 멱등 확인이 먼저라 같은 결과가 돌아온다.
        redeem(qr, 10000, "pos-idem-1", merchantToken())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authId").value(firstAuthId))
            .andExpect(jsonPath("$.approvedAmountMinor").value(10000))
        assertThat(transactionRepository.count()).isEqualTo(1)
        assertThat(eventRepository.count()).isEqualTo(1)
    }

    // ---- void ----

    @Test
    fun `void 는 거래를 VOIDED 로 만들고 소비 이벤트를 full-replace 해 집계에서 제외한다`() {
        val qr = issueQr()
        val authId =
            redeem(qr, 10000, "pos-void-1", merchantToken())
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
                .let { objectMapper.readTree(it).get("authId").asText() }

        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/void", authId)
                    .header("Authorization", "Bearer ${merchantToken()}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VOIDED"))

        val tx = transactionRepository.findByAuthId(authId)!!
        assertThat(tx.status).isEqualTo("VOIDED")
        assertThat(tx.voidedAt).isNotNull()

        // 같은 external_id 의 VOIDED 재적재(full-replace) — CONFIRMED 만 세는 집계에서 자동 제외.
        val event = eventRepository.findByOrgIdAndSourceAndExternalId(orgId, "payment", authId)!!
        assertThat(event.status).isEqualTo("VOIDED")
        assertThat(eventRepository.count()).isEqualTo(1)
        val rows =
            eventRepository.aggregateByDateWindow(
                orgId,
                Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                "UTC",
                100,
            )
        assertThat(rows).isEmpty()

        // 멱등 — 재-void 도 200 이고 상태는 그대로다.
        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/void", authId)
                    .header("Authorization", "Bearer ${merchantToken()}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VOIDED"))
    }

    @Test
    fun `타 가맹 거래의 void 는 404 다(존재 오라클 없음)`() {
        val authId =
            redeem(issueQr(), 10000, "pos-cross-1", merchantToken())
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
                .let { objectMapper.readTree(it).get("authId").asText() }

        mockMvc
            .perform(
                post("/api/merchant/redeem/{authId}/void", authId)
                    .header("Authorization", "Bearer ${merchantToken(otherMerchantId)}"),
            ).andExpect(status().isNotFound)
        assertThat(transactionRepository.findByAuthId(authId)!!.status).isEqualTo("APPROVED")
    }

    // ---- 인가 ----

    @Test
    fun `SUSPENDED 가맹은 redeem 이 403 이다(fail-closed)`() {
        val merchant = merchantRepository.findById(merchantId).orElseThrow()
        merchant.status = MerchantStatus.SUSPENDED.name
        merchantRepository.save(merchant)
        redeem(issueQr(), 10000, "pos-susp-1", merchantToken())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("MERCHANT_SUSPENDED"))
    }

    @Test
    fun `meal_redeem scope 가 없는 토큰은 403 이다`() {
        redeem(issueQr(), 10000, "pos-scope-1", signedToken("meal.consumption.write", merchantId))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `merchant 결속이 없는 토큰은 403 이다(fail-closed)`() {
        redeem(issueQr(), 10000, "pos-nobind-1", signedToken("meal.redeem", null))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `세션으로는 redeem 할 수 없다(STATELESS 베어러 전용 체인)`() {
        val session = login(memberEmail)
        session
            .perform(
                post("/api/merchant/redeem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"x","amountMinor":10000,"posTxnId":"pos-sess-1"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `merchant 결속 client_credentials 실발급 토큰으로 redeem 된다(가맹 온보딩 end-to-end)`() {
        // ★프로덕션 발급 경로: merchant 결속 클라이언트의 /oauth2/token 토큰에 TokenCustomizer 가
        //   merchant_id 클레임을 실어 redeem 인가에 도달한다(손수 서명 토큰 아님).
        val clientId = "meal-pos-terminal"
        val secret = "pos-terminal-secret"
        registerMerchantBoundClient(clientId, secret, merchantId)
        val token = clientCredentialsToken(clientId, secret, "meal.redeem")
        redeem(issueQr(), 9000, "pos-cc-1", token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmountMinor").value(9000))
    }

    // ---- 헬퍼 ----

    /**
     * 결정적 정책 저장 — 창을 하루 전체로 깔아(00~08/08~16/16~하루끝, UTC) 실행 시각과 무관하게 열려 있다.
     *
     * ★dinner_end 는 [MealPolicyTimeRoundTripTest.END_OF_DAY](밀리초 최대값)를 쓴다. 초 단위(23:59:59)로
     * 두면 [23:59:59, 24:00) 1초 사각이 생기고, **마이크로초 최대값(23:59:59.999999)은 DB 왕복에서
     * 00:00 으로 넘어간다** — 그러면 저녁 창이 [16:00, 00:00) 이 돼 반개구간 판정이 항상 거짓이 되고,
     * UTC 16시 이후(KST 새벽)에 도는 모든 케이스가 MEAL_WINDOW_CLOSED 로 깨진다(실제로 9건이 깨졌다).
     * 낮에는 초록불이라 발견이 늦는 종류의 결함이라, 안전한 값을 추측하지 않고 그 테스트에서 측정한다.
     * 창 밖/한도 케이스는 파라미터로 좁힌다.
     */
    private fun savePolicy(
        dailyMealCount: Int = 1,
        monthlyCapMinor: Long = 200000,
        breakfast: Pair<LocalTime, LocalTime> = LocalTime.MIDNIGHT to LocalTime.of(8, 0),
        lunch: Pair<LocalTime, LocalTime> = LocalTime.of(8, 0) to LocalTime.of(16, 0),
        dinner: Pair<LocalTime, LocalTime> = LocalTime.of(16, 0) to MealPolicyTimeRoundTripTest.END_OF_DAY,
    ) {
        policyRepository.save(
            MealPolicy(
                orgId = orgId,
                dailyMealCount = dailyMealCount,
                monthlyCapMinor = monthlyCapMinor,
                breakfastStart = breakfast.first,
                breakfastEnd = breakfast.second,
                lunchStart = lunch.first,
                lunchEnd = lunch.second,
                dinnerStart = dinner.first,
                dinnerEnd = dinner.second,
            ),
        )
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

    private fun redeem(
        qrToken: String,
        amountMinor: Long,
        posTxnId: String,
        bearer: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/merchant/redeem")
                .header("Authorization", "Bearer $bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$qrToken","amountMinor":$amountMinor,"posTxnId":"$posTxnId"}"""),
        )

    private fun merchantToken(id: UUID = merchantId): String = signedToken("meal.redeem", id)

    /** 앱의 JWKSource 로 RS256 서명한 M2M 토큰(iss·exp·scope[·merchant_id]) — 가맹 프로비저닝 토큰 모사. */
    private fun signedToken(
        scope: String,
        merchantId: UUID?,
        subject: String = "svc-pos",
    ): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val builder =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", scope)
        if (merchantId != null) builder.claim("merchant_id", merchantId.toString())
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).tokenValue
    }

    private fun registerMerchantBoundClient(
        clientId: String,
        secret: String,
        merchantId: UUID,
    ) {
        registeredClientRepository.findByClientId(clientId)?.let { return }
        registeredClientRepository.save(
            RegisteredClient
                .withId("$clientId-internal")
                .clientId(clientId)
                .clientName(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("meal.redeem")
                .clientSettings(
                    ClientSettings
                        .builder()
                        .setting(TokenCustomizerConfig.CLIENT_MERCHANT_ID_SETTING, merchantId.toString())
                        .build(),
                ).build(),
        )
    }

    private fun clientCredentialsToken(
        clientId: String,
        secret: String,
        scope: String,
    ): String {
        val basic = "Basic " + Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray())
        val body =
            mockMvc
                .perform(
                    post("/oauth2/token")
                        .header("Authorization", basic)
                        .param("grant_type", "client_credentials")
                        .param("scope", scope),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("access_token").asText()
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
