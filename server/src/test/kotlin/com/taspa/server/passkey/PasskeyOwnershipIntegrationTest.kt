package com.taspa.server.passkey

import com.taspa.server.domain.passkey.PasskeyCredential
import com.taspa.server.domain.passkey.PasskeyCredentialRepository
import com.taspa.server.domain.passkey.PasskeyUserEntity
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

class PasskeyOwnershipIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passkeyUserEntityRepository: PasskeyUserEntityRepository

    @Autowired lateinit var passkeyCredentialRepository: PasskeyCredentialRepository

    @Autowired lateinit var credentialAdapter: JpaUserCredentialRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val emailA = "owner-a@example.com"
    private val emailB = "owner-b@example.com"
    private val password = "SecureP@ssw0rd123"
    private lateinit var credentialId: String

    @BeforeEach
    fun setUp() {
        TestSecurityContextHolder.clearContext()
        SecurityContextHolder.clearContext()
        passkeyCredentialRepository.deleteAll()
        passkeyUserEntityRepository.deleteAll()
        userRepository.deleteAll()

        val userA = createUser(emailA)
        createUser(emailB)

        val externalId = Bytes.random().toBase64UrlString()
        passkeyUserEntityRepository.save(
            PasskeyUserEntity(userId = userA.id!!, externalId = externalId, name = emailA, displayName = emailA),
        )
        credentialId = Bytes.random().toBase64UrlString()
        passkeyCredentialRepository.save(
            PasskeyCredential(
                credentialId = credentialId,
                userEntityExternalId = externalId,
                credentialType = "public-key",
                publicKeyCose = byteArrayOf(1, 2, 3),
                label = "A의 패스키",
                createdAt = Instant.now(),
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `other user cannot delete or see the credential via api`() {
        val sessionB = login(emailB)

        // 존재 여부를 숨기기 위해 404 로 응답한다.
        sessionB
            .perform(delete("/api/passkeys/$credentialId"))
            .andExpect(status().isNotFound)
        assertThat(passkeyCredentialRepository.existsById(credentialId)).isTrue()

        val sessionA = login(emailA)
        sessionA
            .perform(delete("/api/passkeys/$credentialId"))
            .andExpect(status().isNoContent)
        assertThat(passkeyCredentialRepository.existsById(credentialId)).isFalse()
    }

    @Test
    fun `adapter delete guard ignores non-owner and anonymous requests`() {
        // SS 기본 DELETE /webauthn/register/{id} 경로에 소유권 검사가 없을 가능성을 방어하는 어댑터 가드.
        setAuthenticatedContext(emailB)
        credentialAdapter.delete(Bytes.fromBase64(credentialId))
        assertThat(passkeyCredentialRepository.existsById(credentialId)).isTrue()

        SecurityContextHolder.clearContext()
        credentialAdapter.delete(Bytes.fromBase64(credentialId))
        assertThat(passkeyCredentialRepository.existsById(credentialId)).isTrue()

        setAuthenticatedContext(emailA)
        credentialAdapter.delete(Bytes.fromBase64(credentialId))
        assertThat(passkeyCredentialRepository.existsById(credentialId)).isFalse()
    }

    private fun setAuthenticatedContext(email: String) {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                email,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.setContext(context)
    }

    private fun createUser(email: String): User =
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(status().is3xxRedirection)
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
