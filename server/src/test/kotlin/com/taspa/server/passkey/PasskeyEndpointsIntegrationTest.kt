package com.taspa.server.passkey

import com.taspa.server.domain.passkey.PasskeyCredentialRepository
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class PasskeyEndpointsIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passkeyUserEntityRepository: PasskeyUserEntityRepository

    @Autowired lateinit var passkeyCredentialRepository: PasskeyCredentialRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val email = "passkey-endpoints@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        // spring-security-test 의 MockMvc 통합은 이전 테스트의 인증을 TestSecurityContextHolder 로
        // 전파할 수 있으므로 미인증 시나리오의 정확성을 위해 명시적으로 비운다.
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
    fun `unauthenticated register options is rejected with 400`() {
        // PasskeyCreationOptionsFilter(원본 필터 동작 이식)는 미인증(익명) 요청에 400 을 반환한다(실측).
        mockMvc
            .perform(post("/webauthn/register/options").with(csrf()).with(anonymous()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `unauthenticated authenticate options returns 200 with empty allowCredentials`() {
        // 익명 어서션 옵션: allowCredentials 빈 배열 = discoverable credential(usernameless) 로그인.
        mockMvc
            .perform(post("/webauthn/authenticate/options").with(csrf()).with(anonymous()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").exists())
            .andExpect(jsonPath("$.rpId").value("localhost"))
            .andExpect(jsonPath("$.allowCredentials").isArray)
            .andExpect(jsonPath("$.allowCredentials").isEmpty)
    }

    @Test
    fun `authenticated register options returns rp and user entity`() {
        val session = login()
        session
            .perform(post("/webauthn/register/options").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rp.id").value("localhost"))
            .andExpect(jsonPath("$.rp.name").value("taspa"))
            .andExpect(jsonPath("$.user.name").value(email))
            .andExpect(jsonPath("$.challenge").exists())
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
}
