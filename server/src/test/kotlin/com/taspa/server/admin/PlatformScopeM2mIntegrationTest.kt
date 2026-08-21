package com.taspa.server.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.admin.dto.ClientRegisterRequest
import com.taspa.server.common.exception.AuthException
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64
import java.util.UUID

/**
 * Phase 0-B(scope 설정화) + 0-D(M2M) 통합 테스트. 플랫폼 scope 로 클라이언트를 등록할 수 있고(하드코딩
 * 제거), 미허용 scope 는 거부되며, 신규 scope 로 client_credentials 토큰이 발급되는지 확인한다.
 */
class PlatformScopeM2mIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var adminClientService: AdminClientService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val clientId = "forecast-m2m"

    @BeforeEach
    fun setUp() {
        // 공유 컨테이너 — registered_client 잔존 제거(등록 중복 회피).
        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId)
    }

    @Test
    fun `플랫폼 scope 로 클라이언트를 등록하고 client_credentials 토큰을 발급한다`() {
        val response =
            adminClientService.register(
                ClientRegisterRequest(
                    clientId = clientId,
                    clientName = "Forecast M2M",
                    scopes = listOf("meal.forecast.write", "calendar.read"),
                    grantTypes = listOf("client_credentials"),
                    publicClient = false,
                ),
                UUID.randomUUID(),
            )
        assertThat(response.client.scopes).contains("meal.forecast.write", "calendar.read")
        val secret = requireNotNull(response.clientSecret)

        // 신규 scope 로 client_credentials 토큰 발급 확인(D).
        val basic = "Basic " + Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray())
        val body =
            mockMvc
                .perform(
                    post("/oauth2/token")
                        .header("Authorization", basic)
                        .param("grant_type", "client_credentials")
                        .param("scope", "meal.forecast.write"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)
        assertThat(json.get("access_token").asText()).isNotBlank()
        assertThat(json.get("scope").asText()).contains("meal.forecast.write")
    }

    @Test
    fun `화이트리스트에 없는 scope 는 거부된다`() {
        assertThatThrownBy {
            adminClientService.register(
                ClientRegisterRequest(
                    clientId = "bad-scope-client",
                    clientName = "Bad",
                    scopes = listOf("totally.bogus.scope"),
                    grantTypes = listOf("client_credentials"),
                ),
                UUID.randomUUID(),
            )
        }.isInstanceOf(AuthException::class.java)
    }
}
