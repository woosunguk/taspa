package com.taspa.server.admin

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AdminBootstrapConfig {
    private val log = LoggerFactory.getLogger(AdminBootstrapConfig::class.java)

    /**
     * 부트스트랩 관리자 승격: taspa.admin.emails 의 계정이 존재하면 ADMIN 으로 승격한다(감사 기록).
     * 계정이 아직 없으면 건너뛴다 — 가입 후 재기동하거나 README 의 첫 관리자 지정 SQL 을 사용한다.
     */
    @Bean
    fun adminBootstrapRunner(
        adminProperties: AdminProperties,
        userRepository: UserRepository,
        auditEventService: AuditEventService,
    ): ApplicationRunner =
        ApplicationRunner {
            adminProperties.emails.forEach { email ->
                val user = userRepository.findByEmail(email.trim())
                if (user == null) {
                    log.warn("admin bootstrap: account not found, skipping email={}", email)
                    return@forEach
                }
                if (user.role != UserRole.ADMIN.name) {
                    user.role = UserRole.ADMIN.name
                    userRepository.save(user)
                    auditEventService.record(
                        "ADMIN_ROLE_GRANTED",
                        user.id,
                        mapOf("email" to user.email, "source" to "bootstrap"),
                    )
                }
            }
        }
}
