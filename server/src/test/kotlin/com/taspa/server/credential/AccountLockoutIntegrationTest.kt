package com.taspa.server.credential

import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl

class AccountLockoutIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val email = "lockme@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `five failed attempts lock the account even for the correct password`() {
        repeat(4) {
            mockMvc
                .perform(loginAttempt("wrong-password"))
                .andExpect(redirectedUrl("/login/password?error"))
        }
        // 5번째 실패가 잠금을 발동시킨다.
        mockMvc
            .perform(loginAttempt("wrong-password"))
            .andExpect(redirectedUrl("/login/password?error=locked"))

        // 잠긴 뒤에는 올바른 비밀번호도 잠금 응답을 받는다.
        mockMvc
            .perform(loginAttempt(password))
            .andExpect(redirectedUrl("/login/password?error=locked"))

        assertThat(userRepository.findByEmail(email)!!.lockedUntil).isNotNull()
    }

    private fun loginAttempt(password: String) =
        post("/login/password")
            .param("username", email)
            .param("password", password)
            .with(csrf())
}
