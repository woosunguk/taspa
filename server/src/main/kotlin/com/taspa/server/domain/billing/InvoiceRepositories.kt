package com.taspa.server.domain.billing

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface InvoiceRepository : JpaRepository<Invoice, UUID> {
    fun findByOrgIdOrderByPeriodDesc(orgId: UUID): List<Invoice>

    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): Invoice?

    /** 인접 월 FINALIZED 창 정합 조회(잠금 불필요 — FINALIZED 는 불변). */
    fun findByOrgIdAndPeriod(
        orgId: UUID,
        period: String,
    ): Invoice?

    /**
     * generate 경로 — 같은 (org, period) 행을 FOR UPDATE 로 잠가 동시 draft 재생성의 라인 삭제/삽입
     * 교차(중복 라인·유실)를 직렬화한다. 행이 아직 없으면 동시 insert 는 UNIQUE(org_id, period) 위반으로
     * 한쪽이 409(DataIntegrityViolation → GlobalExceptionHandler)로 떨어진다 — 재시도 안전.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.orgId = :orgId AND i.period = :period")
    fun findByOrgIdAndPeriodForUpdate(
        @Param("orgId") orgId: UUID,
        @Param("period") period: String,
    ): Invoice?

    /** finalize 경로 — FOR UPDATE 로 동시 finalize/generate 와 직렬화한다(확정 후 불변식 보장). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id AND i.orgId = :orgId")
    fun findByIdAndOrgIdForUpdate(
        @Param("id") id: UUID,
        @Param("orgId") orgId: UUID,
    ): Invoice?
}

interface InvoiceLineRepository : JpaRepository<InvoiceLine, UUID> {
    fun findByInvoiceIdOrderByUserEmailAsc(invoiceId: UUID): List<InvoiceLine>

    /** draft 재생성의 full-replace — 기존 라인 일괄 삭제(벌크, 영속성 컨텍스트 미적재). */
    @Modifying
    @Query("DELETE FROM InvoiceLine l WHERE l.invoiceId = :invoiceId")
    fun deleteByInvoiceId(
        @Param("invoiceId") invoiceId: UUID,
    )
}
