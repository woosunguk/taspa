package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealTransaction
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgStatus
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

/**
 * 내 식대 자격 조회(GET /api/meal/entitlement) 통합 테스트.
 *
 * ★이 API 의 존재 이유는 "화면이 말하는 가능/불가능"과 "POS 가 실제로 내리는 판정"이 어긋나지 않게
 * 하는 것이다. 그래서 마지막 케이스는 **같은 시점의 entitlement 와 redeem 을 나란히 호출해** 둘이
 * 같은 답(창 없음 ↔ MEAL_WINDOW_CLOSED)을 내는지 본다 — 이게 깨지면 직원이 계산대 앞에서 거절당한다.
 *
 * 끼니창 판정을 결정적으로 만드는 방식은 MealRedeemIntegrationTest 와 같다: 열린 경우는 하루 전체를
 * 덮는 창, 닫힌 경우는 지금에서 멀리 떨어진 1분짜리 창(`closedWindowAwayFromNow`).
 * QR 발급 쿨다운은 0 으로 꺼서 자격 조회 검증이 발급 레이트리밋에 얽히지 않게 한다.
 */
@TestPropertySource(properties = ["taspa.meal.qr-issue-cooldown=0s"])
class MealEntitlementIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

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
    private lateinit var memberEmail: String
    private lateinit var memberId: UUID
    private lateinit var outsiderEmail: String
    private lateinit var merchantId: UUID

    @BeforeEach
    fun setUp() {
        transactionRepository.deleteAll()
        qrTokenRepository.deleteAll()
        eventRepository.deleteAll()
        policyRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId = organizationRepository.save(Organization(slug = "meal-ent", name = "Meal Entitlement Org")).id!!
        memberEmail = "meal-ent-member@example.com"
        memberId =
            userRepository
                .save(
                    User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        membershipRepository.save(OrgMembership(orgId = orgId, userId = memberId, role = "MEMBER"))
        outsiderEmail = "meal-ent-outsider@example.com"
        userRepository.save(
            User(email = outsiderEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
        merchantId = merchantRepository.save(Merchant(name = "본사 식당")).id!!
        savePolicy()
    }

    // ---- 정상 조회 ----

    @Test
    fun `활성 멤버는 조직 정책과 열린 끼니창을 그대로 받는다`() {
        val session = login(memberEmail)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orgId").value(orgId.toString()))
            .andExpect(jsonPath("$.orgName").value("Meal Entitlement Org"))
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andExpect(jsonPath("$.serverNow").isNotEmpty)
            // 정책 행의 값이 그대로 실려야 한다(화면이 한도를 자체 상수로 갖지 않게 하는 것이 목적).
            .andExpect(jsonPath("$.perMealLimitMinor").value(15000))
            .andExpect(jsonPath("$.dailyMealCount").value(2))
            .andExpect(jsonPath("$.monthlyCapMinor").value(300000))
            // 하루 전체를 덮는 창이라 언제 돌려도 열려 있다.
            .andExpect(jsonPath("$.currentWindow").isNotEmpty)
            .andExpect(jsonPath("$.currentWindow.window").isNotEmpty)
            .andExpect(jsonPath("$.canIssueNow").value(true))
            .andExpect(jsonPath("$.todayApprovedCount").value(0))
            .andExpect(jsonPath("$.dailyRemaining").value(2))
            .andExpect(jsonPath("$.monthOrgPaidMinor").value(0))
            .andExpect(jsonPath("$.monthRemainingMinor").value(300000))
            // 화면이 "무슨 기간의 숫자"인지 말할 수 있어야 한다.
            .andExpect(jsonPath("$.periodStart").isNotEmpty)
            .andExpect(jsonPath("$.periodEnd").isNotEmpty)
    }

    @Test
    fun `오늘 승인된 거래가 사용 횟수와 이번 달 누계에 반영된다`() {
        // 조직부담 8000 = 9000 − 1000(개인부담). 승인 경로가 월 cap 판정에 쓰는 합과 같은 정의다.
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = memberId,
                merchantId = merchantId,
                amountMinor = 9000,
                selfPaidMinor = 1000,
                mealWindow = "LUNCH",
                posTxnId = "ent-today-1",
            ),
        )

        val session = login(memberEmail)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.todayApprovedCount").value(1))
            .andExpect(jsonPath("$.dailyRemaining").value(1))
            .andExpect(jsonPath("$.monthApprovedCount").value(1))
            .andExpect(jsonPath("$.monthOrgPaidMinor").value(8000))
            .andExpect(jsonPath("$.monthSelfPaidMinor").value(1000))
            .andExpect(jsonPath("$.monthRemainingMinor").value(292000))
            // 일 허용 2회 중 1회를 썼을 뿐이라 아직 발급 가능하다.
            .andExpect(jsonPath("$.canIssueNow").value(true))
    }

    @Test
    fun `거래 이력에는 조직 구분이 담긴다`() {
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = memberId,
                merchantId = merchantId,
                amountMinor = 7000,
                mealWindow = "LUNCH",
                posTxnId = "ent-hist-1",
            ),
        )
        val session = login(memberEmail)
        session
            .perform(get("/api/meal/transactions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].orgId").value(orgId.toString()))
    }

    // ---- 인가 ----

    @Test
    fun `비멤버는 403 이다`() {
        val session = login(outsiderEmail)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `정지된 조직의 멤버도 403 이다`() {
        // isActiveMember 가 org SUSPENDED 를 함께 접는다 — QR 발급과 같은 판정식이라 자격도 같이 닫힌다.
        val org = organizationRepository.findById(orgId).get()
        org.status = OrgStatus.SUSPENDED.name
        organizationRepository.save(org)

        val session = login(memberEmail)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 조회는 401 이다`() {
        mockMvc
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
    }

    @Test
    fun `위임 베어러는 이웃 표면(QR 발급)과 똑같이 401 이다`() {
        // /api/meal 이하는 default 체인 소속이고 그 체인에는 리소스 서버가 붙어 있지 않다 — 세션 전용이다.
        // 자격 조회만 베어러를 받아들이면 그 순간 이 표면의 계약이 이웃과 갈라진다.
        val bearer = userBearerToken()
        mockMvc
            .perform(
                get("/api/meal/entitlement")
                    .param("orgId", orgId.toString())
                    .header("Authorization", "Bearer $bearer"),
            ).andExpect(status().isUnauthorized)

        mockMvc
            .perform(
                post("/api/meal/qr")
                    .header("Authorization", "Bearer $bearer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"orgId":"$orgId"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `orgId 없이 부르면 400 이다`() {
        val session = login(memberEmail)
        session
            .perform(get("/api/meal/entitlement"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
    }

    // ---- redeem 과의 일치(이 API 의 존재 이유) ----

    @Test
    fun `끼니창 밖이면 자격은 현재 창 없음을 말하고 같은 시점의 redeem 도 MEAL_WINDOW_CLOSED 다`() {
        val closed = closedWindowAwayFromNow()
        savePolicy(breakfast = closed, lunch = closed, dinner = closed)

        val session = login(memberEmail)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentWindow").doesNotExist())
            .andExpect(jsonPath("$.canIssueNow").value(false))
            // 화면이 "그럼 언제 되나"를 말할 수 있어야 버튼을 비활성해도 막다른 길이 아니다.
            .andExpect(jsonPath("$.nextWindow").isNotEmpty)
            .andExpect(jsonPath("$.nextWindow.startsAt").isNotEmpty)

        // 같은 정책·같은 시점에서 실제 승인은 거절된다 — 화면과 계산대가 어긋나지 않는다는 증거.
        val qr = issueQr(session)
        mockMvc
            .perform(
                post("/api/merchant/redeem")
                    .header("Authorization", "Bearer ${merchantToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$qr","amountMinor":10000,"posTxnId":"ent-closed-1"}"""),
            ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("MEAL_WINDOW_CLOSED"))
        assertThat(transactionRepository.count()).isZero()
    }

    @Test
    fun `일 횟수를 다 쓰면 자격은 발급 불가를 말하고 같은 시점의 redeem 도 DAILY_MEAL_LIMIT 다`() {
        savePolicy(dailyMealCount = 1)
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = orgId,
                userId = memberId,
                merchantId = merchantId,
                amountMinor = 9000,
                mealWindow = "LUNCH",
                posTxnId = "ent-daily-1",
            ),
        )

        val session = login(memberEmail)
        session
            .perform(get("/api/meal/entitlement").param("orgId", orgId.toString()))
            .andExpect(status().isOk)
            // 창은 열려 있지만 횟수가 없다 — 두 사유를 화면이 구분해 말할 수 있어야 한다.
            .andExpect(jsonPath("$.currentWindow").isNotEmpty)
            .andExpect(jsonPath("$.dailyRemaining").value(0))
            .andExpect(jsonPath("$.canIssueNow").value(false))

        val qr = issueQr(session)
        mockMvc
            .perform(
                post("/api/merchant/redeem")
                    .header("Authorization", "Bearer ${merchantToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$qr","amountMinor":10000,"posTxnId":"ent-daily-2"}"""),
            ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("DAILY_MEAL_LIMIT"))
        assertThat(transactionRepository.count()).isEqualTo(1)
    }

    // ---- 헬퍼 ----

    /**
     * 기본은 하루 전체를 덮는 창(어느 시각에 돌려도 열림) + 검증용 한도(1식 15000·일 2회·월 300000).
     * ★하루 끝은 [MealPolicyTimeRoundTripTest.END_OF_DAY](밀리초 최대값)다 — 마이크로초 최대값은
     *   DB 왕복에서 00:00 으로 넘어가 창이 [16:00, 00:00) 이 되고, UTC 16시 이후에 도는 케이스가
     *   전부 깨진다(낮에는 초록불이라 발견이 늦는다). 그 테스트에서 측정한 값이다.
     */
    private fun savePolicy(
        perMealLimitMinor: Long = 15000,
        dailyMealCount: Int = 2,
        monthlyCapMinor: Long = 300000,
        breakfast: Pair<LocalTime, LocalTime> = LocalTime.MIDNIGHT to LocalTime.of(8, 0),
        lunch: Pair<LocalTime, LocalTime> = LocalTime.of(8, 0) to LocalTime.of(16, 0),
        dinner: Pair<LocalTime, LocalTime> = LocalTime.of(16, 0) to MealPolicyTimeRoundTripTest.END_OF_DAY,
    ) {
        policyRepository.save(
            MealPolicy(
                orgId = orgId,
                perMealLimitMinor = perMealLimitMinor,
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

    /**
     * 지금을 포함하지 않는 1분짜리 창(org 타임존 UTC 기준).
     *
     * zero-width 창(NOON to NOON)을 쓰면 "다음 끼니"가 존재하지 않아 nextWindow 단언을 할 수 없다.
     * 4시간 간격 후보 중 현재 시각에서 원형 거리가 가장 먼 것을 골라 최소 몇 시간의 여유를 두고,
     * 시작이 20:00 을 넘지 않아 자정을 넘는 창(start >= end — 판정 자체가 달라진다)이 생기지 않는다.
     */
    private fun closedWindowAwayFromNow(): Pair<LocalTime, LocalTime> {
        val nowSec = LocalTime.now(ZoneOffset.UTC).toSecondOfDay()
        val start =
            (0 until 6).map { LocalTime.of(it * 4, 0) }.maxByOrNull { candidate ->
                val diff = abs(candidate.toSecondOfDay() - nowSec)
                minOf(diff, SECONDS_PER_DAY - diff)
            }!!
        return start to start.plusMinutes(1)
    }

    private fun issueQr(session: WebSession): String {
        val body =
            session
                .perform(
                    post("/api/meal/qr").contentType(MediaType.APPLICATION_JSON).content("""{"orgId":"$orgId"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("token").asText()
    }

    private fun merchantToken(): String =
        signedToken("meal.redeem", "svc-pos") {
            it.claim("merchant_id", merchantId.toString())
        }

    /** 사용자 위임 토큰 모사 — sub 이 users.id(UUID)인 토큰(TokenCustomizerConfig 발급 규칙). */
    private fun userBearerToken(): String = signedToken("openid profile", memberId.toString()) { }

    private fun signedToken(
        scope: String,
        subject: String,
        customize: (JwtClaimsSet.Builder) -> Unit,
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
        customize(builder)
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).tokenValue
    }

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    private companion object {
        const val SECONDS_PER_DAY = 24 * 60 * 60
    }
}
