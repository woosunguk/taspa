package com.taspa.server.billing

import com.taspa.server.billing.dto.DepartmentSpendView
import com.taspa.server.billing.dto.OrgSpendView
import com.taspa.server.billing.dto.PreviousSpendView
import com.taspa.server.billing.dto.SpendInvoiceView
import com.taspa.server.domain.billing.Invoice
import com.taspa.server.domain.billing.InvoiceRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.org.OrganizationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 진행 중인 달의 식대 집계(읽기 전용) — "이번 달 얼마 나가고 있나"에 답한다.
 *
 * ★청구서를 만들지 않고 답한다는 것이 이 서비스의 존재 이유다. `InvoiceService.generate` 는 DRAFT 를
 * full-replace 하는 상태변경(+step-up +감사)이라, 대시보드를 열었다는 이유로 진행 중인 달을 문서로
 * 굳혀서는 안 된다. 소비 이벤트 집계에는 금액 축이 없고 개인 거래 조회는 본인 것뿐이라 대체재도 없다.
 *
 * ★숫자는 청구서와 **같아야 한다**. 그래서 창은 `InvoiceService.periodWindow`, 집계는 청구서가 쓰는
 * `aggregateOrgPaidByUser` 를 **그대로** 쓴다(같은 WHERE: APPROVED 만, 조직부담 = amount − selfPaid).
 * 계산을 복제하면 두 화면이 어긋나는 순간 어느 쪽도 믿을 수 없게 된다.
 *
 * 부수효과는 0 이다 — invoice 행을 만들지도 갱신하지도 않는다.
 */
@Service
class OrgSpendService(
    private val organizationService: OrganizationService,
    private val invoiceService: InvoiceService,
    private val invoiceRepository: InvoiceRepository,
    private val transactionRepository: MealTransactionRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val departmentRepository: DepartmentRepository,
) {
    @Transactional(readOnly = true)
    fun spend(
        orgId: UUID,
        requestedPeriod: String?,
    ): OrgSpendView {
        val org = organizationService.requireOrg(orgId)
        val now = Instant.now()
        // period 미지정이면 **org 타임존 기준** 이번 달이다. 서버 로컬 달력으로 정하면 KST 조직의 월초·월말에
        // 화면과 청구서가 다른 달을 가리킨다.
        val zone = runCatching { ZoneId.of(org.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val period = requestedPeriod ?: YearMonth.now(zone).toString()

        val window = invoiceService.periodWindow(orgId, org.timezone, period)
        val totals = aggregate(orgId, window.start, window.end)

        val previous = previousComparable(orgId, org.timezone, window, now, totals.orgPaid)

        return OrgSpendView(
            orgId = orgId,
            period = window.period,
            timezone = org.timezone,
            periodStart = window.start,
            periodEnd = window.end,
            asOf = now,
            inProgress = now.isBefore(window.end),
            orgPaidMinor = totals.orgPaid,
            selfPaidMinor = transactionRepository.sumSelfPaidByOrgInWindow(orgId, window.start, window.end),
            txnCount = totals.txnCount,
            departments = departmentBreakdown(orgId, totals.orgPaidByUser),
            previous = previous,
            invoice = invoiceRepository.findByOrgIdAndPeriod(orgId, window.period)?.let { toSpendInvoice(it) },
        )
    }

    private data class Totals(
        val orgPaid: Long,
        val txnCount: Int,
        /** userId → (건수, 조직부담). 부서 롤업에만 쓰고 **응답에는 개인 라인을 내보내지 않는다**. */
        val orgPaidByUser: Map<UUID, Pair<Int, Long>>,
    )

    /** 청구서와 같은 질의 — 여기서 갈라지면 "청구서 전에 같은 숫자를 본다"는 계약이 깨진다. */
    private fun aggregate(
        orgId: UUID,
        from: Instant,
        to: Instant,
    ): Totals {
        val rows = transactionRepository.aggregateOrgPaidByUser(orgId, from, to)
        var orgPaid = 0L
        var txnCount = 0
        val byUser = HashMap<UUID, Pair<Int, Long>>(rows.size)
        rows.forEach { row ->
            val userId = row[0] as UUID
            val count = (row[1] as Long).toInt()
            val amount = row[2] as Long
            orgPaid += amount
            txnCount += count
            byUser[userId] = count to amount
        }
        return Totals(orgPaid, txnCount, byUser)
    }

    /**
     * 부서 분해. 청구서는 생성 시점 부서명을 라인에 굳히지만(문서 불변), 이 화면은 확정 전 진행값이라
     * **현재 멤버십**을 그대로 읽는다 — 오늘의 조직도로 "지금 어디에 나가고 있나"를 보는 게 맞다.
     */
    private fun departmentBreakdown(
        orgId: UUID,
        byUser: Map<UUID, Pair<Int, Long>>,
    ): List<DepartmentSpendView> {
        if (byUser.isEmpty()) return emptyList()
        val deptByUser = membershipRepository.findByOrgId(orgId).associate { it.userId to it.departmentId }
        val deptNameById = departmentRepository.findByOrgId(orgId).associate { it.id!! to it.name }

        return byUser.entries
            .groupBy { deptByUser[it.key] }
            .map { (deptId, entries) ->
                DepartmentSpendView(
                    departmentId = deptId,
                    departmentName = deptId?.let { deptNameById[it] },
                    txnCount = entries.sumOf { it.value.first },
                    orgPaidMinor = entries.sumOf { it.value.second },
                )
            }
            // 금액 큰 순 — "어디에 많이 나가나"가 이 목록의 질문이라 미배정도 금액대로 자리한다
            // (금액이 가장 크면 1위로 온다). 동점일 때만 미배정을 뒤로 민다.
            // 청구서 상세는 이름순·미배정 뒤라 정렬 규칙이 다르다 — 그쪽은 대사용 명세라 목적이 다르다.
            .sortedWith(compareByDescending<DepartmentSpendView> { it.orgPaidMinor }.thenBy { it.departmentName == null })
    }

    /**
     * 전월 **동기간**. 진행 중인 달을 전월 전체와 비교하면 항상 "줄었다"로 보여 방향이 거짓말이 되므로,
     * 전월 창 시작에서 이번 달과 같은 경과 시간만큼만 잘라 비교한다.
     *
     * 단 **달이 이미 끝났으면 클램프하지 않는다.** 경과 시간으로 자르면 짧은 달이 긴 달을 볼 때 뒷부분이
     * 빠진다 — 2월(28일)로 1월을 보면 1/29~31 이 사라져 전월이 과소 집계되고, 완결 월끼리 비교하는
     * 가장 흔한 사용에서 증감이 실제보다 크게 나온다.
     */
    private fun previousComparable(
        orgId: UUID,
        timezone: String,
        window: InvoiceService.PeriodWindow,
        now: Instant,
        currentOrgPaid: Long,
    ): PreviousSpendView? {
        val currentMonth = YearMonth.parse(window.period)
        // ★예외를 던지게 두지 말고 **미리 판정**한다. periodWindow 는 @Transactional 프록시라 여기서
        //   던진 예외를 runCatching 으로 삼켜도 참여 트랜잭션이 rollback-only 로 표시되고, 커밋 시점에
        //   UnexpectedRollbackException → 500 이 된다. "비교만 생략" 하려던 의도와 정반대 결과다
        //   (예: period=0000-01 → 전월이 -0001-12 라 정준형을 벗어난다).
        if (currentMonth.year <= MIN_COMPARABLE_YEAR) return null
        val previousPeriod = currentMonth.minusMonths(1).toString()
        // 전월 창도 같은 함수로 얻는다 — 확정된 인접 월의 스냅샷 정합이 여기에도 그대로 적용돼야 한다.
        val previousWindow = invoiceService.periodWindow(orgId, timezone, previousPeriod)

        val monthClosed = !now.isBefore(window.end)
        val previousEnd =
            if (monthClosed) {
                previousWindow.end
            } else {
                val elapsed = Duration.between(window.start, now)
                if (elapsed.isNegative || elapsed.isZero) return null
                val cappedEnd = previousWindow.start.plus(elapsed)
                if (cappedEnd.isBefore(previousWindow.end)) cappedEnd else previousWindow.end
            }

        val totals = aggregate(orgId, previousWindow.start, previousEnd)
        return PreviousSpendView(
            period = previousWindow.period,
            periodStart = previousWindow.start,
            periodEnd = previousEnd,
            orgPaidMinor = totals.orgPaid,
            txnCount = totals.txnCount,
            // 전월이 0 이면 증감률이 정의되지 않는다 — "+100%"·"+∞%" 로 적으면 거짓말이 된다.
            changeRatio =
                if (totals.orgPaid == 0L) {
                    null
                } else {
                    (currentOrgPaid - totals.orgPaid).toDouble() / totals.orgPaid
                },
        )
    }

    private fun toSpendInvoice(invoice: Invoice) =
        SpendInvoiceView(
            id = invoice.id!!,
            status = invoice.status,
            subtotalMinor = invoice.subtotalMinor,
            generatedAt = invoice.generatedAt,
            finalizedAt = invoice.finalizedAt,
        )

    private companion object {
        /** 이 해 이하는 전월이 'YYYY-MM' 정준형을 벗어난다(0001-01 → 0000-12 는 표기 가능하나 그 아래가 깨진다). */
        const val MIN_COMPARABLE_YEAR = 1
    }
}
