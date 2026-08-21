package com.taspa.server.billing.dto

import java.time.Instant
import java.util.UUID

/** 청구서 생성 요청 — period 는 org 타임존 달력 월 'YYYY-MM'. */
data class GenerateInvoiceRequest(
    val period: String,
)

/** 청구서 요약(목록·finalize 응답). */
data class InvoiceView(
    val id: UUID,
    val period: String,
    val status: String,
    val subtotalMinor: Long,
    val txnCount: Int,
    val generatedAt: Instant,
    val finalizedAt: Instant?,
)

/** 사용자 분해 라인 — email·부서명은 생성 시점 스냅샷. 멤버십이 없던 사용자는 부서 null. */
data class InvoiceLineView(
    val userId: UUID,
    val userEmail: String,
    val departmentId: UUID?,
    val departmentName: String?,
    val txnCount: Int,
    val amountMinor: Long,
)

/** 부서별 소계 — 저장하지 않고 응답에서 라인을 그룹핑해 파생한다(부서 미배정은 departmentId null 행). */
data class DepartmentSubtotalView(
    val departmentId: UUID?,
    val departmentName: String?,
    val txnCount: Int,
    val amountMinor: Long,
)

/** 청구서 상세(generate·단건 조회 응답) — 라인 + 부서 소계 포함. */
data class InvoiceDetailView(
    val id: UUID,
    val period: String,
    val status: String,
    val subtotalMinor: Long,
    val txnCount: Int,
    val generatedAt: Instant,
    val finalizedAt: Instant?,
    val lines: List<InvoiceLineView>,
    val departmentSubtotals: List<DepartmentSubtotalView>,
)
