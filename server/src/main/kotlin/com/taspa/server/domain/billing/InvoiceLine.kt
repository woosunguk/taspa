package com.taspa.server.domain.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * 청구서 사용자 분해 라인. user_email·department_name 은 생성 시점 스냅샷 — 이후 사용자 이메일 변경·
 * 부서 개편/삭제가 확정 청구서를 바꾸지 않는다(department_id 는 의도적으로 FK 없음, 이력 불변).
 */
@Entity
@Table(name = "invoice_lines")
class InvoiceLine(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "invoice_id", nullable = false)
    val invoiceId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "user_email", nullable = false, length = 100)
    val userEmail: String,
    @Column(name = "department_id")
    val departmentId: UUID? = null,
    @Column(name = "department_name", length = 120)
    val departmentName: String? = null,
    @Column(name = "txn_count", nullable = false)
    val txnCount: Int,
    /** 조직부담 합(개인부담 제외). */
    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,
)
