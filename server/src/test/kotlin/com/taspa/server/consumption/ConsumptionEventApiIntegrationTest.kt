package com.taspa.server.consumption

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
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
import org.springframework.data.domain.Pageable
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * 소비 이벤트 API(Phase 0ب-C) 인가 통합 테스트 — 테넌시/집계노출/스코프.
 *  - 적재(write): 베어러 M2M(meal.consumption.write) + org 결속만 허용. 세션 쓰기 금지, 타 org·미결속·스코프 미충족 403.
 *  - 집계 조회(read): 전조회(read.all) vs org 결속(read), 세션 멤버 본인 org 만, 집계만 노출(개별 미노출).
 * org 결속 M2M 토큰은 앱의 JWKSource 로 org_id 클레임을 실어 서명해 재현한다(생산자 프로비저닝 토큰 모사).
 *
 * 배치 크기 상한(taspa.consumption.max-batch-size)은 테스트에서 3 으로 낮춰 초과/이내 경계를 검증한다 —
 * 기존 테스트는 전부 1건 배치라 무영향.
 */
@TestPropertySource(properties = ["taspa.consumption.max-batch-size=3"])
class ConsumptionEventApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

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
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID
    private lateinit var memberEmail: String

    private val readAllClientId = "consumption-forecast"
    private val readAllSecret = "forecast-secret"

    @BeforeEach
    fun setUp() {
        auditEventRepository.deleteAll()
        eventRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgA = organizationRepository.save(Organization(slug = "cx-a", name = "CX A")).id!!
        orgB = organizationRepository.save(Organization(slug = "cx-b", name = "CX B")).id!!

        memberEmail = "cx-member@example.com"
        val member =
            userRepository.save(
                User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = "MEMBER"))

        registerM2mClient(readAllClientId, readAllSecret, "meal.consumption.read.all")
    }

    // ---- 적재(write) ----

    @Test
    fun `org 결속 write 토큰으로 적재하고, 재전송은 멱등이다`() {
        val token = signedToken("meal.consumption.write", orgA)
        val body = """{"events":[{"source":"pos","externalId":"w-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isOk)
        assertThat(eventRepository.count()).isEqualTo(1)
        // 재전송 → insert 없음.
        ingest(orgA, token, body).andExpect(status().isOk)
        assertThat(eventRepository.count()).isEqualTo(1)
    }

    @Test
    fun `org 결속 토큰은 타 org 에 적재할 수 없다(테넌시 격리)`() {
        val token = signedToken("meal.consumption.write", orgA)
        val body = """{"events":[{"source":"pos","externalId":"x-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgB, token, body).andExpect(status().isForbidden)
        assertThat(eventRepository.count()).isZero()
    }

    @Test
    fun `org 결속 없는 write 토큰은 적재가 거부된다(fail-closed)`() {
        val token = signedToken("meal.consumption.write", null)
        val body = """{"events":[{"source":"pos","externalId":"u-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isForbidden)
        assertThat(eventRepository.count()).isZero()
    }

    @Test
    fun `write scope 이 없는 토큰은 적재가 403 이다`() {
        val token = signedToken("meal.consumption.read", orgA)
        val body = """{"events":[{"source":"pos","externalId":"n-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isForbidden)
    }

    @Test
    fun `세션 사용자는 적재할 수 없다(CSRF-off 체인에서 쓰기 금지)`() {
        val session = login(memberEmail)
        val body = """{"events":[{"source":"pos","externalId":"s-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        session
            .perform(
                post("/api/orgs/{orgId}/consumption-events", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 적재는 401 이다`() {
        val body = """{"events":[{"source":"pos","externalId":"a-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        mockMvc
            .perform(
                post("/api/orgs/{orgId}/consumption-events", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `source=payment 는 장부 예약 네임스페이스라 공개 적재 API 에서 400 으로 거부된다`() {
        // auth_id 는 비밀이 아니므로(POS 응답·거래 이력 노출) org 결속 생산자가 (payment, auth_id) 멱등키로
        // redemption 유래 이벤트를 full-replace 개변하지 못하게 API 경계에서 차단한다(장부-seam 정합 보호).
        val token = signedToken("meal.consumption.write", orgA)
        val body = """{"events":[{"source":"payment","externalId":"rsv-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isBadRequest)
        assertThat(eventRepository.count()).isZero()
    }

    @Test
    fun `merchant_id 는 공개 적재 API 에서 지정할 수 없다(가맹 그레인 예측 오염 차단)`() {
        // 가맹 그레인 집계는 테넌시 앵커가 org_id 가 아니라 merchant_id 라서 **org 를 넘어 합산**한다.
        // 본문의 merchant_id 를 믿으면 A 조직 생산자가 B 매장 UUID 로 적재해 그 매장의 식수예측을
        // 부풀릴 수 있다(매장은 오지 않을 손님 인분을 준비한다). 가맹 귀속은 실제 승인 거래에서만 선다.
        val token = signedToken("meal.consumption.write", orgA)
        val body =
            """{"events":[{"source":"pos","externalId":"m-1","merchantId":"${UUID.randomUUID()}",""" +
                """"mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isBadRequest)
        assertThat(eventRepository.count()).isZero()
    }

    @Test
    fun `경로 org 에 없는 site_id 는 적재가 거부된다`() {
        // site 는 org 스코프다. 검증하지 않으면 타 조직 사업장 UUID 가 이 org 의 이벤트에 스며들어
        // 사업장 그레인 예측이 조용히 어긋나고, 남의 사업장 UUID 존재 여부를 떠보는 신호도 된다.
        val token = signedToken("meal.consumption.write", orgA)
        val body =
            """{"events":[{"source":"pos","externalId":"s-9","siteId":"${UUID.randomUUID()}",""" +
                """"mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isBadRequest)
        assertThat(eventRepository.count()).isZero()
    }

    @Test
    fun `org 결속 client_credentials 로 실제 발급한 토큰으로 적재된다(생산자 온보딩 end-to-end)`() {
        // ★프로덕션에서 발급 가능한 실제 토큰 경로: org 에 결속된 client_credentials 클라이언트가 /oauth2/token
        //   으로 받은 토큰에는 TokenCustomizer 가 org_id 클레임을 실어 org 결속 write 에 도달한다(손수 서명 토큰 아님).
        val producerId = "consumption-producer"
        val producerSecret = "producer-secret"
        registerOrgBoundM2mClient(producerId, producerSecret, "meal.consumption.write", orgA)
        val token = clientCredentialsToken(producerId, producerSecret, "meal.consumption.write")

        val body = """{"events":[{"source":"pos","externalId":"p-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isOk)
        assertThat(eventRepository.count()).isEqualTo(1)
        // 결속되지 않은 타 org 에는 여전히 거부된다(테넌시 격리).
        ingest(orgB, token, body).andExpect(status().isForbidden)
    }

    @Test
    fun `적재 성공 후 CONSUMPTION_INGESTED audit 가 org_id·clientId·count 와 함께 기록되고 개별내용은 빠진다`() {
        val token = signedToken("meal.consumption.write", orgA, subject = "svc-payments")
        val userSub = UUID.randomUUID()
        val body = """{"events":[{"source":"pos","externalId":"aud-1","userSub":"$userSub","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgA, token, body).andExpect(status().isOk)

        val audits = auditEventRepository.findByTypeOrderByCreatedAtDesc("CONSUMPTION_INGESTED", Pageable.unpaged())
        assertThat(audits).hasSize(1)
        val audit = audits.first()
        // org 결속(활동로그 조회 축) + M2M 는 사용자 없음.
        assertThat(audit.orgId).isEqualTo(orgA)
        assertThat(audit.userId).isNull()
        // 행위자 귀속(clientId=토큰 sub) + 배치 카운트.
        val detail = objectMapper.readTree(audit.detail)
        assertThat(detail.get("clientId").asText()).isEqualTo("svc-payments")
        assertThat(detail.get("received").asInt()).isEqualTo(1)
        assertThat(detail.get("inserted").asInt()).isEqualTo(1)
        assertThat(detail.get("updated").asInt()).isEqualTo(0)
        assertThat(detail.get("sources").toString()).contains("pos")
        // ★PII/개별내용 미포함 — user_sub·external_id 는 audit detail 에 절대 없어야 한다(집계만 노출).
        assertThat(audit.detail)
            .doesNotContain(userSub.toString())
            .doesNotContain("aud-1")
            .doesNotContain("userSub")
            .doesNotContain("user_sub")
    }

    @Test
    fun `적재 거부(타 org)면 audit 가 남지 않는다`() {
        val token = signedToken("meal.consumption.write", orgA)
        val body = """{"events":[{"source":"pos","externalId":"noaud-1","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgB, token, body).andExpect(status().isForbidden)
        assertThat(auditEventRepository.findByTypeOrderByCreatedAtDesc("CONSUMPTION_INGESTED", Pageable.unpaged())).isEmpty()
    }

    // ---- 배치 크기 상한 + preload 멱등 (max-batch-size=3 오버라이드) ----

    @Test
    fun `배치 크기 상한 초과는 400 이고 아무것도 적재되지 않는다`() {
        val token = signedToken("meal.consumption.write", orgA)
        val events =
            (1..4).joinToString(",") { i ->
                """{"source":"pos","externalId":"lim-$i","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}"""
            }
        ingest(orgA, token, """{"events":[$events]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("한 번에 3건까지 적재할 수 있습니다"))
        assertThat(eventRepository.count()).isZero()
    }

    @Test
    fun `배치 크기 상한 이내는 정상 적재된다(경계값)`() {
        val token = signedToken("meal.consumption.write", orgA)
        val events =
            (1..3).joinToString(",") { i ->
                """{"source":"pos","externalId":"ok-$i","mealWindow":"LUNCH","occurredAt":"2026-08-10T12:00:00Z"}"""
            }
        ingest(orgA, token, """{"events":[$events]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inserted").value(3))
        assertThat(eventRepository.count()).isEqualTo(3)
    }

    @Test
    fun `preload 배치 조회 후에도 멱등 재전송은 full-replace 갱신이다`() {
        val token = signedToken("meal.consumption.write", orgA)
        val first = """{"events":[
            {"source":"pos","externalId":"pl-1","mealWindow":"LUNCH","quantity":1,"occurredAt":"2026-08-10T12:00:00Z"},
            {"source":"pos","externalId":"pl-2","mealWindow":"LUNCH","quantity":1,"occurredAt":"2026-08-10T12:00:00Z"}
        ]}"""
        ingest(orgA, token, first).andExpect(status().isOk).andExpect(jsonPath("$.inserted").value(2))

        // 같은 멱등키 2건 + 신규 1건 재전송 — preload 가 기존 2행을 IN 조회로 읽어 갱신, 신규는 insert.
        val second = """{"events":[
            {"source":"pos","externalId":"pl-1","mealWindow":"DINNER","quantity":5,"occurredAt":"2026-08-10T18:00:00Z"},
            {"source":"pos","externalId":"pl-2","mealWindow":"LUNCH","quantity":2,"occurredAt":"2026-08-10T12:00:00Z"},
            {"source":"pos","externalId":"pl-3","mealWindow":"LUNCH","quantity":1,"occurredAt":"2026-08-10T12:00:00Z"}
        ]}"""
        ingest(orgA, token, second)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inserted").value(1))
            .andExpect(jsonPath("$.updated").value(2))
        assertThat(eventRepository.count()).isEqualTo(3)
        val replaced = eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "pos", "pl-1")!!
        assertThat(replaced.quantity).isEqualTo(5)
        assertThat(replaced.mealWindow).isEqualTo("DINNER")
    }

    @Test
    fun `배치 내 동일 external_id 2건은 뒤가 앞을 갱신한다(한 행으로 접힘)`() {
        val token = signedToken("meal.consumption.write", orgA)
        val body = """{"events":[
            {"source":"pos","externalId":"dup-1","mealWindow":"LUNCH","quantity":1,"occurredAt":"2026-08-10T12:00:00Z"},
            {"source":"pos","externalId":"dup-1","mealWindow":"LUNCH","quantity":7,"occurredAt":"2026-08-10T12:00:00Z"}
        ]}"""
        // 배치 내 중복은 저장소 기존 건이 아니므로 updated 로 세지 않는다(기존 시맨틱 유지).
        ingest(orgA, token, body)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inserted").value(1))
            .andExpect(jsonPath("$.updated").value(0))
        assertThat(eventRepository.count()).isEqualTo(1)
        assertThat(eventRepository.findByOrgIdAndSourceAndExternalId(orgA, "pos", "dup-1")!!.quantity).isEqualTo(7)
    }

    // ---- 집계 조회(read) ----

    @Test
    fun `read_all 토큰으로 집계를 조회한다(전조회) - 집계만 노출된다`() {
        seed(orgA, "r-1", "LUNCH", 2)
        seed(orgA, "r-2", "LUNCH", 1)
        val token = clientCredentialsToken(readAllClientId, readAllSecret, "meal.consumption.read.all")
        val body =
            aggregate(orgA, token)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)
        val rows = json.get("rows")
        val lunch = rows.first { it.get("mealWindow").asText() == "LUNCH" }
        assertThat(lunch.get("count").asLong()).isEqualTo(2)
        assertThat(lunch.get("quantity").asLong()).isEqualTo(3)
        // ★집계만 노출 — 개별 이벤트·user_sub·id 는 응답 어디에도 없어야 한다.
        assertThat(body).doesNotContain("userSub").doesNotContain("user_sub").doesNotContain("externalId")
        rows.forEach {
            val fields = it.fieldNames().asSequence().toList()
            // 노출 필드는 집계 축·카운트로만 한정된다(개별 이벤트 필드 누출 금지). null menuRef 는 생략될 수 있다.
            assertThat(fields).isSubsetOf("date", "mealWindow", "menuRef", "count", "quantity")
        }
    }

    @Test
    fun `org 결속 read 토큰은 본인 org 만 집계하고 타 org 는 403 이다`() {
        seed(orgA, "b-1", "LUNCH", 1)
        val token = signedToken("meal.consumption.read", orgA)
        aggregate(orgA, token).andExpect(status().isOk)
        aggregate(orgB, token).andExpect(status().isForbidden)
    }

    @Test
    fun `org 결속 없는 read 토큰은 임의 org 집계가 거부된다(테넌시 격리)`() {
        val token = signedToken("meal.consumption.read", null)
        aggregate(orgA, token).andExpect(status().isForbidden)
        aggregate(orgB, token).andExpect(status().isForbidden)
    }

    @Test
    fun `read scope 이 없는 토큰은 집계가 403 이다`() {
        val token = signedToken("meal.pay", orgA)
        aggregate(orgA, token).andExpect(status().isForbidden)
    }

    @Test
    fun `세션 멤버는 본인 org 집계는 조회하고 타 org 는 403 이다`() {
        seed(orgA, "sm-1", "LUNCH", 1)
        val session = login(memberEmail)
        session.perform(aggregateGet(orgA)).andExpect(status().isOk)
        session.perform(aggregateGet(orgB)).andExpect(status().isForbidden)
    }

    // ---- helpers ----

    private fun ingest(
        orgId: UUID,
        token: String,
        body: String,
    ) = mockMvc.perform(
        post("/api/orgs/{orgId}/consumption-events", orgId)
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun aggregateGet(orgId: UUID) =
        get("/api/orgs/{orgId}/consumption-events/aggregate", orgId)
            .param("from", "2026-08-01T00:00:00Z")
            .param("to", "2026-09-01T00:00:00Z")

    private fun aggregate(
        orgId: UUID,
        token: String,
    ) = mockMvc.perform(aggregateGet(orgId).header("Authorization", "Bearer $token"))

    /** 서비스 토큰으로 직접 이벤트를 심는다(집계 조회 시나리오 준비 — write 경로와 독립). */
    private fun seed(
        orgId: UUID,
        externalId: String,
        window: String,
        quantity: Int,
    ) {
        val token = signedToken("meal.consumption.write", orgId)
        val body = """{"events":[{"source":"pos","externalId":"$externalId","mealWindow":"$window","quantity":$quantity,"occurredAt":"2026-08-10T12:00:00Z"}]}"""
        ingest(orgId, token, body).andExpect(status().isOk)
    }

    /** 앱의 JWKSource 로 RS256 서명한 M2M 토큰(iss·exp·scope[·org_id]). 생산자 프로비저닝 토큰을 모사한다. */
    private fun signedToken(
        scope: String,
        orgId: UUID?,
        subject: String = "svc-consumption",
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
        if (orgId != null) builder.claim("org_id", orgId.toString())
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

    private fun registerM2mClient(
        clientId: String,
        secret: String,
        scope: String,
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
                .scope(scope)
                .build(),
        )
    }

    /** org 에 결속된 client_credentials 클라이언트(생산자). client 설정의 org-id 를 TokenCustomizer 가 읽어 org_id 클레임을 발급한다. */
    private fun registerOrgBoundM2mClient(
        clientId: String,
        secret: String,
        scope: String,
        orgId: UUID,
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
                .scope(scope)
                .clientSettings(
                    ClientSettings
                        .builder()
                        .setting(TokenCustomizerConfig.CLIENT_ORG_ID_SETTING, orgId.toString())
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
}
