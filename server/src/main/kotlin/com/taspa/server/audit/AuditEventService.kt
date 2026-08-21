package com.taspa.server.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.domain.audit.AuditEvent
import com.taspa.server.domain.audit.AuditEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class AuditEventService(
    private val auditEventRepository: AuditEventRepository,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(AuditEventService::class.java)

    /**
     * REQUIRES_NEW: 호출부 트랜잭션과 무관하게 record() 안에서 자체 커밋한다.
     * 단순 save() 는 INSERT 가 외부 트랜잭션 커밋 시점까지 연기돼 아래 try/catch 를 벗어나고,
     * 감사 INSERT 실패가 비밀번호 재설정·MFA 활성화 같은 본 업무 트랜잭션 전체를 롤백시킨다.
     *
     * 트레이드오프(의도된 선택):
     *  - 외부 트랜잭션이 롤백돼도 감사 행은 남는다(과잉 기록 방향 — 격리 보장이 우선).
     *  - 트랜잭션 호출부에서는 INSERT 동안 잠깐 커넥션을 2개 점유한다(감사 단건 INSERT 한정,
     *    풀 크기 대비 짧은 창 — PasswordResetService 의 세션 폐기처럼 긴 중첩은 두지 말 것).
     */
    private val auditTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /**
     * 구조화 로그 + DB 영속화. 감사 저장 실패가 본 인증 흐름을 깨뜨리지 않도록 경고로 격리한다.
     * 기존 콜러 무변경을 위한 3-arg 오버로드 — orgId 없는 전역 이벤트(로그인·MFA 등)는 이 경로로 남는다.
     */
    fun record(
        type: String,
        userId: UUID?,
        detail: Map<String, Any?>,
    ) = record(type, userId, null, detail)

    /**
     * org 결속 감사 이벤트 기록 — org_id 컬럼을 채워 조직 스코프 활동로그 조회(WHERE org_id=?)를 지탱한다.
     * detail 맵의 orgId 키는 표시/하위호환용으로 유지하되 컬럼에도 병행 기록한다.
     */
    fun record(
        type: String,
        userId: UUID?,
        orgId: UUID?,
        detail: Map<String, Any?>,
    ) {
        // detail 에는 email 등 PII 가 담기므로 로그에는 type+userId+orgId 만 남기고 상세는 DB(audit_events)에만 보존한다.
        log.info("audit type={} userId={} orgId={}", type, userId, orgId)
        try {
            val detailJson = objectMapper.writeValueAsString(detail)
            // 커밋(=실제 INSERT)이 execute {} 안에서 끝나므로 실패가 이 try 에 잡힌다.
            auditTransaction.execute {
                auditEventRepository.save(AuditEvent(type = type, userId = userId, orgId = orgId, detail = detailJson))
            }
        } catch (e: Exception) {
            log.warn("audit persist failed: type={} userId={} orgId={}", type, userId, orgId, e)
        }
    }
}
