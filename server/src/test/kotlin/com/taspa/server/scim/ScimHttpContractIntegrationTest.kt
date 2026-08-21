package com.taspa.server.scim

import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.token.JwkStorageService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SCIM 표면의 HTTP 프로토콜 오류 계약(405/415)을 잠그는 회귀 테스트.
 *
 * 왜 별도 파일인가: 프로비저너(Azure AD)는 **500 을 quarantine 사유로 취급**한다. 잘못된 메서드 한 번에
 * 그 조직의 프로비저닝 연동 전체가 격리될 수 있으므로, 클라이언트 입력 오류가 500 으로 새는 경로는
 * 개별 기능 테스트가 아니라 프로토콜 계약으로 고정해야 한다. 형식도 함께 잠근다 — 상태 코드만 맞고
 * 본문이 앱 공용 포맷(errorCode/message)이면 SCIM 클라이언트는 파싱하지 못한다(RFC 7644 §3.12).
 */
class ScimHttpContractIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var historyRepository: MembershipHistoryRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private lateinit var org: UUID

    @BeforeEach
    fun setUp() {
        historyRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        org = organizationRepository.save(Organization(slug = "scim-http", name = "SCIM HTTP")).id!!
    }

    // ---- A. 405 (메서드 미지원) ----

    @Test
    fun `컬렉션에 지원하지 않는 메서드는 405 + Allow 헤더 + SCIM 오류 스키마다`() {
        // /scim/v2/Users 는 GET(목록)·POST(생성)만 있다. PUT 은 항목 경로 전용.
        mockMvc
            .perform(put("/scim/v2/Users").header("Authorization", "Bearer ${scimToken()}"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(header().string("Allow", containsString("POST")))
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.status").value("405"))
            .andExpect(jsonPath("$.detail").exists())
            // 앱 공용 포맷이 새어 나오면 SCIM 클라이언트가 파싱하지 못한다.
            .andExpect(jsonPath("$.errorCode").doesNotExist())
    }

    @Test
    fun `디스커버리 엔드포인트의 쓰기 메서드도 405 SCIM 오류다`() {
        mockMvc
            .perform(
                post("/scim/v2/ServiceProviderConfig")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(ScimMediaType.SCIM_JSON)
                    .content("{}"),
            ).andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.status").value("405"))
    }

    @Test
    fun `항목 경로의 미지원 메서드도 405 다(무토큰이면 401 이 먼저다)`() {
        val id = UUID.randomUUID()
        // POST 는 컬렉션 전용 — 항목 경로에는 GET/PUT/PATCH/DELETE 만 있다.
        mockMvc
            .perform(
                post("/scim/v2/Users/$id")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(ScimMediaType.SCIM_JSON)
                    .content("{}"),
            ).andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.status").value("405"))

        // 대조: 인증이 없으면 체인이 먼저 잘라 401 이다(405 가 인증 우회 관측면이 되지 않는다).
        mockMvc
            .perform(post("/scim/v2/Users/$id").contentType(ScimMediaType.SCIM_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }

    // ---- B. 415 (Content-Type 미지원) ----

    @Test
    fun `본문 Content-Type 이 지원 대상이 아니면 415 SCIM 오류다`() {
        mockMvc
            .perform(
                post("/scim/v2/Users")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("userName=x"),
            ).andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.status").value("415"))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
    }

    @Test
    fun `PATCH 의 Content-Type 미지원도 415 다`() {
        mockMvc
            .perform(
                patch("/scim/v2/Users/${UUID.randomUUID()}")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("op=replace"),
            ).andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.status").value("415"))
    }

    @Test
    fun `SCIM·JSON 본문은 그대로 수용된다(415 핸들러가 정상 경로를 막지 않는다)`() {
        // application/json 은 RFC 7644 상 필수는 아니지만 프로비저너 호환을 위해 계속 수용해야 한다.
        mockMvc
            .perform(
                post("/scim/v2/Users")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userName":"ct-json@corp.example.com"}"""),
            ).andExpect(status().isCreated)

        mockMvc
            .perform(
                post("/scim/v2/Users")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(ScimMediaType.SCIM_JSON)
                    .content("""{"userName":"ct-scim@corp.example.com"}"""),
            ).andExpect(status().isCreated)
    }

    // ---- C. 대조군 — 기존 SCIM 오류 형식은 그대로다 ----

    @Test
    fun `대조군 -- 미지원 필터는 여전히 400 invalidFilter 다`() {
        mockMvc
            .perform(
                get("/scim/v2/Users")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .param("filter", """emails co "corp""""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.scimType").value("invalidFilter"))
            .andExpect(jsonPath("$.status").value("400"))
    }

    @Test
    fun `대조군 -- 파손된 JSON 본문은 여전히 400 invalidSyntax 다`() {
        mockMvc
            .perform(
                post("/scim/v2/Users")
                    .header("Authorization", "Bearer ${scimToken()}")
                    .contentType(ScimMediaType.SCIM_JSON)
                    .content("{bad"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.scimType").value("invalidSyntax"))
            .andExpect(jsonPath("$.status").value("400"))
    }

    @Test
    fun `대조군 -- 없는 리소스는 여전히 404 SCIM 오류다`() {
        mockMvc
            .perform(
                delete("/scim/v2/Users/${UUID.randomUUID()}").header("Authorization", "Bearer ${scimToken()}"),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.status").value("404"))
    }

    // ---- D. 대조군 — SCIM 밖 표면의 405/415 포맷은 건드리지 않는다 ----

    @Test
    fun `대조군 -- SCIM 밖 경로의 405 415 는 앱 공용 포맷을 유지한다`() {
        // SCIM 전용 처리가 전역 dispatch 를 가로채면 여기가 먼저 깨진다(HttpContractIntegrationTest 와 짝).
        mockMvc
            .perform(get("/api/accounts/signup"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.schemas").doesNotExist())

        mockMvc
            .perform(post("/api/accounts/signup").contentType(MediaType.TEXT_PLAIN).content("x"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.schemas").doesNotExist())
    }

    // ---- helpers ----

    /** 앱 JWKSource 로 RS256 서명한 M2M 프로비저너 토큰(ScimApiIntegrationTest 와 동일 방식). */
    private fun scimToken(): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject("svc-hr-scim")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", "org.scim")
                .claim("org_id", org.toString())
                .build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }
}
