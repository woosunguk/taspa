package com.taspa.server.audit

import com.taspa.server.domain.audit.AuditEvent
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.maintenance.RetentionCleanupJob
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AuditEventPersistenceIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var auditEventService: AuditEventService

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var retentionCleanupJob: RetentionCleanupJob

    @BeforeEach
    fun setUp() {
        auditEventRepository.deleteAll()
    }

    @Test
    fun `record 는 감사 이벤트를 DB 에 JSON detail 로 저장한다`() {
        val userId = UUID.randomUUID()

        auditEventService.record("TEST_EVENT", userId, mapOf("email" to "a@example.com", "count" to 1))

        val saved = auditEventRepository.findAll().single()
        assertThat(saved.type).isEqualTo("TEST_EVENT")
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.detail).contains("\"email\":\"a@example.com\"").contains("\"count\":1")
        assertThat(saved.createdAt).isNotNull()
    }

    @Test
    fun `보존 기간을 넘긴 감사 이벤트는 정리 잡이 삭제한다`() {
        auditEventRepository.save(
            AuditEvent(type = "OLD_EVENT", createdAt = Instant.now().minus(Duration.ofDays(400))),
        )
        auditEventRepository.save(AuditEvent(type = "RECENT_EVENT"))

        retentionCleanupJob.cleanUp()

        assertThat(auditEventRepository.findAll()).extracting("type").containsExactly("RECENT_EVENT")
    }
}
