package com.taspa.server.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {
    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var springSessionRepository: SessionRepository<out Session>

    /** 브라우저 세션 시뮬레이터(SESSION 쿠키 체인) — MockHttpSession 공유 패턴의 대체. */
    protected fun webSession(): WebSession = WebSession(mockMvc, springSessionRepository)

    companion object {
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:16-alpine").apply {
                withDatabaseName("taspa")
                withUsername("taspa")
                withPassword("taspa")
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
