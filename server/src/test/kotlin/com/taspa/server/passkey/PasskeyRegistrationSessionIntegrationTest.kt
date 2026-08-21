package com.taspa.server.passkey

import com.ninjasquad.springmockk.SpykBean
import com.taspa.server.domain.passkey.PasskeyCredentialRepository
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations
import org.springframework.session.SessionRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * JDBC 세션 하의 패스키 등록/로그인 옵션 왕복(B-4 #4) — 직렬화 예외 회귀 방지.
 *
 * PublicKeyCredentialCreationOptions 는 Serializable 이 아니므로(유일한 직렬화 블로커) 세션에
 * 직접 저장되면 응답 커밋 시점에 등록이 즉사한다. 이 테스트는 JdbcCreationOptionsRepository
 * 경유(세션에는 불투명 토큰 문자열만, 옵션은 DB 행)로 옵션 발급 → 등록까지 왕복이 성공함을 검증한다.
 * 어테스테이션 암호 검증은 이 테스트의 관심사가 아니므로 registerCredential 만 스텁한다(SpykBean —
 * 옵션 발급·JSON 파싱·저장소 왕복·필터 체인은 전부 실제 경로).
 */
class PasskeyRegistrationSessionIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passkeyUserEntityRepository: PasskeyUserEntityRepository

    @Autowired lateinit var passkeyCredentialRepository: PasskeyCredentialRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var jdbcCreationOptionsRepository: JdbcCreationOptionsRepository

    @SpykBean lateinit var relyingPartyOperations: WebAuthnRelyingPartyOperations

    private val email = "passkey-session@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        TestSecurityContextHolder.clearContext()
        SecurityContextHolder.clearContext()
        passkeyCredentialRepository.deleteAll()
        passkeyUserEntityRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `registration round trip succeeds under jdbc sessions via the cached options repository`() {
        val session = login()

        // 1) 옵션 발급: 커스텀 필터 + 커스텀 저장소 경유. 응답 커밋(=세션 직렬화)이 성공해야 200 이 온다.
        session
            .perform(post("/webauthn/register/options").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").exists())
            .andExpect(jsonPath("$.user.name").value(email))

        // 세션에는 옵션 객체가 아니라 불투명 토큰 문자열만 저장된다(직렬화 블로커 우회의 핵심).
        val pointer = sessionAttribute(session, JdbcCreationOptionsRepository.SESSION_KEY)
        assertThat(pointer).isInstanceOf(String::class.java)

        // 2) 등록 확정: WebAuthnRegistrationFilter 가 같은 저장소에서 옵션을 로드·소비한다.
        //    어테스테이션 검증만 스텁(관심사 아님) — JSON 파싱/저장소 왕복/응답은 실제 경로.
        every { relyingPartyOperations.registerCredential(any()) } returns fakeCredentialRecord()
        session
            .perform(
                post("/webauthn/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registrationRequestJson())
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
        verify(exactly = 1) { relyingPartyOperations.registerCredential(any()) }

        // 소비 후 세션 키와 DB 행이 정리된다.
        assertThat(sessionAttribute(session, JdbcCreationOptionsRepository.SESSION_KEY)).isNull()

        // 세션은 계속 유효하다(등록 과정에서 세션이 깨지지 않음).
        session.perform(post("/webauthn/register/options").with(csrf())).andExpect(status().isOk)
    }

    @Test
    fun `authentication options round trip also survives jdbc session serialization`() {
        // 로그인 옵션(PublicKeyCredentialRequestOptions)은 Serializable — 기본 세션 저장 경로 그대로.
        val session = login()
        session
            .perform(post("/webauthn/authenticate/options").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").exists())
        // 응답 커밋(직렬화)이 성공했고 세션도 계속 유효하다.
        session
            .perform(post("/webauthn/authenticate/options").with(csrf()))
            .andExpect(status().isOk)
    }

    // ---- helpers ----

    @Test
    fun `★DB 에서 복원한 옵션이 발급한 옵션과 검증 관련 필드까지 같다`() {
        // 등록 옵션을 인메모리에서 DB 로 옮기면서 객체를 통째로 저장할 수 없어 필드를 풀어 재구성한다.
        // 재구성이 부실하면 challenge 불일치·rpId 불일치로 등록이 조용히 깨진다 — 그런데 위 왕복
        // 테스트는 registerCredential 을 스텁하므로 그걸 못 본다. 여기서 **검증기에 실제로 전달되는**
        // 옵션을 붙잡아 발급 시 응답과 대조한다.
        val session = login()

        val issued =
            session
                .perform(post("/webauthn/register/options").with(csrf()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val issuedJson =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(issued)

        val captured = slot<org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest>()
        every { relyingPartyOperations.registerCredential(capture(captured)) } returns fakeCredentialRecord()
        session
            .perform(
                post("/webauthn/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registrationRequestJson())
                    .with(csrf()),
            ).andExpect(status().isOk)

        val restored = captured.captured.creationOptions
        // challenge 는 base64url 문자열로 응답에 실린다 — 한 글자만 달라도 검증이 실패한다.
        assertThat(restored.challenge.toBase64UrlString()).isEqualTo(issuedJson.get("challenge").asText())
        assertThat(restored.user.id.toBase64UrlString()).isEqualTo(issuedJson.get("user").get("id").asText())
        assertThat(restored.user.name).isEqualTo(issuedJson.get("user").get("name").asText())
        assertThat(restored.rp.id).isEqualTo(issuedJson.get("rp").get("id").asText())
        // 검증 강도 — 여기가 느슨해지면 등록은 성공하지만 약한 인증기가 통과한다(조용한 약화).
        assertThat(restored.authenticatorSelection?.userVerification?.value)
            .isEqualTo(issuedJson.path("authenticatorSelection").path("userVerification").asText(null))
        assertThat(restored.pubKeyCredParams.map { it.alg.value })
            .isEqualTo(issuedJson.get("pubKeyCredParams").map { it.get("alg").asLong() })
    }

    private fun login(): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password")
                    .param("username", email)
                    .param("password", password)
                    .with(csrf()),
            ).andExpect(status().is3xxRedirection)
        return session
    }

    private fun sessionAttribute(
        session: WebSession,
        name: String,
    ): Any? {
        @Suppress("UNCHECKED_CAST")
        val repository = springSessionRepository as SessionRepository<org.springframework.session.Session>
        return repository.findById(session.sessionId()!!)?.getAttribute(name)
    }

    /** 벤더링 JS(webauthn.js/account.html)가 보내는 등록 확정 본문과 동일한 구조. */
    private fun registrationRequestJson(): String {
        val rawId = Bytes.random().toBase64UrlString()
        return """
            {
              "publicKey": {
                "credential": {
                  "id": "$rawId",
                  "rawId": "$rawId",
                  "response": {
                    "attestationObject": "${Bytes.random().toBase64UrlString()}",
                    "clientDataJSON": "${Bytes.random().toBase64UrlString()}",
                    "transports": ["internal"]
                  },
                  "type": "public-key",
                  "clientExtensionResults": {},
                  "authenticatorAttachment": "platform"
                },
                "label": "테스트 패스키"
              }
            }
            """.trimIndent()
    }

    private fun fakeCredentialRecord() =
        ImmutableCredentialRecord
            .builder()
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(Bytes.random())
            .userEntityUserId(Bytes.random())
            .publicKey(ImmutablePublicKeyCose(byteArrayOf(1, 2, 3)))
            .signatureCount(0)
            .uvInitialized(false)
            .transports(emptySet())
            .backupEligible(false)
            .backupState(false)
            .label("테스트 패스키")
            .created(Instant.now())
            .lastUsed(Instant.now())
            .build()
}
