package com.taspa.server.billing.dto

import java.time.Instant
import java.util.UUID

/**
 * 진행 중인 달의 식대 집계 응답 — **청구서를 만들기 전에 같은 숫자를 미리 보는 것**이 존재 이유다.
 * 그래서 금액은 청구서와 정확히 같은 규칙(APPROVED 만, 조직부담=amount−selfPaid, org 타임존 월 경계)으로
 * 계산되고, 창은 `periodStart`/`periodEnd` 로 노출해 청구서의 스냅샷 창과 직접 대조할 수 있게 한다.
 *
 * 개인별 라인은 없다 — 그건 정산 문서(InvoiceLineView)의 것이고, 상시로 열어 두는 대시보드에 개인 금액이
 * 올라오면 화면 성격이 정산에서 감시로 바뀐다.
 */
data class OrgSpendView(
    val orgId: UUID,
    /** 집계 대상 월 'YYYY-MM'(org 타임존 달력). */
    val period: String,
    /** 창 계산의 근거가 된 org 타임존 — 화면이 "어느 달력인가"를 말할 수 있어야 한다. */
    val timezone: String,
    /** 실효 창 시작(포함). 인접 월이 확정돼 있으면 그 스냅샷 창에 맞춰진 값이다. */
    val periodStart: Instant,
    /** 실효 창 끝(제외). */
    val periodEnd: Instant,
    /** 이 응답을 만든 시각. periodEnd 보다 이르면 아직 늘어날 수 있는 값이다. */
    val asOf: Instant,
    /** 진행 중인 달인지(asOf < periodEnd). 화면이 "확정 전 진행값"임을 말하는 근거. */
    val inProgress: Boolean,
    /** 조직 부담 합계 = Σ(amount − selfPaid). 청구서 subtotalMinor 와 같은 정의다. */
    val orgPaidMinor: Long,
    /** 개인 부담 합계 = Σ selfPaid. 청구 대상이 아니다(참고값). */
    val selfPaidMinor: Long,
    val txnCount: Int,
    /** 부서별 분해(조직부담 내림차순, 미배정은 departmentId null). */
    val departments: List<DepartmentSpendView>,
    /** 전월 **동기간** 비교. 비교 창을 잡을 수 없으면 null. */
    val previous: PreviousSpendView?,
    /** 같은 period 의 청구서 상태. 아직 생성 전이면 null — 이 조회는 청구서를 만들지 않는다. */
    val invoice: SpendInvoiceView?,
)

/** 부서별 조직부담 소계. 부서명은 **현재 멤버십 기준**이다(청구서는 생성 시점 스냅샷 — 확정 전이라 다를 수 있다). */
data class DepartmentSpendView(
    val departmentId: UUID?,
    val departmentName: String?,
    val txnCount: Int,
    val orgPaidMinor: Long,
)

/**
 * 전월 동기간. 진행 중인 달을 **전월 전체**와 비교하면 언제나 "줄었다"로 보이므로, 전월 창의 시작에서
 * 이번 달과 **같은 경과 시간**만큼만 잘라서 비교한다(전월 끝을 넘지 않게 클램프).
 */
data class PreviousSpendView(
    val period: String,
    val periodStart: Instant,
    /** 비교에 실제로 쓰인 끝(제외) — 전월 전체가 아니라 경과분까지일 수 있다. */
    val periodEnd: Instant,
    val orgPaidMinor: Long,
    val txnCount: Int,
    /** (이번 − 전월) / 전월. 전월이 0 이면 비율이 정의되지 않아 null 이다(0 에서 늘어난 것을 %로 못 쓴다). */
    val changeRatio: Double?,
)

/** 대시보드가 "확정 전 진행값"임을 말하기 위해 필요한 최소한의 청구서 상태. */
data class SpendInvoiceView(
    val id: UUID,
    val status: String,
    val subtotalMinor: Long,
    val generatedAt: Instant,
    val finalizedAt: Instant?,
)
