package com.taspa.server.billing

import com.taspa.server.audit.AuditEventService
import com.taspa.server.billing.dto.DepartmentSubtotalView
import com.taspa.server.billing.dto.InvoiceDetailView
import com.taspa.server.billing.dto.InvoiceLineView
import com.taspa.server.billing.dto.InvoiceView
import com.taspa.server.billing.dto.UnfinalizedInvoiceLine
import com.taspa.server.billing.dto.UnfinalizedInvoicesView
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.billing.Invoice
import com.taspa.server.domain.billing.InvoiceLine
import com.taspa.server.domain.billing.InvoiceLineRepository
import com.taspa.server.domain.billing.InvoiceRepository
import com.taspa.server.domain.billing.InvoiceStatus
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.OrganizationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 정산 집계 — 조직 월 청구서 draft→finalize (설계 §4.3·§6.6). meal_transactions 의 **APPROVED** 거래만
 * org 타임존 월 경계 [1일 00:00, 익월 1일 00:00) 로 집계해 사용자별 분해 라인을 만든다. 청구액은
 * **조직부담(amount − self_paid)만** — 개인부담은 제외한다. DRAFT 는 재생성(라인 full-replace) 가능,
 * FINALIZED 이후는 generate/finalize 모두 409 로 불변이다. 수수료·부가세·세금계산서·실 수납은 범위 밖
 * (실 자금이동 없음 — 확정된 청구서 데이터까지가 이 서비스의 책임).
 *
 * 부서 라인 스냅샷: 생성 시점의 **현재 멤버십** department_id → 부서명을 라인에 굳힌다(청구서 불변).
 * 거래 후 멤버십이 제거된 사용자는 부서 null 로 남는다(집계 자체는 거래 사실 기준이라 누락되지 않는다).
 */
@Service
class InvoiceService(
    private val organizationService: OrganizationService,
    private val organizationRepository: OrganizationRepository,
    private val transactionRepository: MealTransactionRepository,
    private val invoiceRepository: InvoiceRepository,
    private val lineRepository: InvoiceLineRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val departmentRepository: DepartmentRepository,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
    private val graceWindow: InvoiceGraceWindow,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(InvoiceService::class.java)

    companion object {
        /** 'YYYY-MM' 정준형만 허용(월 01~12) — 통과하면 YearMonth.parse 가 항상 성공한다. */
        private val PERIOD_PATTERN = Regex("""\d{4}-(0[1-9]|1[0-2])""")

        /** 미확정 현황이 한 번에 훑을 조직 수 상한 — 넘치면 `skipped` 로 **드러낸다**. */
        private const val MAX_ORGS_SCANNED = 500

        /** 목록 정렬 — 심각한 것이 위로. */
        private val STATE_ORDER = listOf("MISSING", "DRAFT", "PENDING")
    }

    /**
     * period 의 실효 집계 창 — **청구서와 다른 화면이 같은 숫자를 보게 하는 단일 출처**다.
     *
     * OrgSpendService(진행 중인 달 미리보기)가 이 함수를 그대로 쓴다. 창 계산을 복제하면 월 경계 한 시간
     * 차이로 대시보드와 청구서가 어긋나고, 그 순간 어느 쪽을 믿어야 할지 알 수 없어 두 화면이 모두 쓸모없어진다.
     */
    data class PeriodWindow(
        val period: String,
        val zone: ZoneId,
        val start: Instant,
        val end: Instant,
    )

    /**
     * period 검증 + org 타임존 월 경계 [1일 00:00, 익월 1일 00:00) 해석.
     *
     * UTC 절단이 아니라 org-로컬 달력 앵커다(KST 7/1 00:30 거래가 6월로 새는 오귀속 방지 — V18 소비 집계와
     * 같은 원칙). 인접 월이 이미 FINALIZED 면 그 창 스냅샷과 정합시킨다 — 확정 후 org 타임존이 바뀌어도
     * 두 청구서 사이에 미청구 갭/이중청구 구간이 생기지 않는다(확정 창은 불변이므로 이쪽이 양보).
     */
    @Transactional(readOnly = true)
    fun periodWindow(
        orgId: UUID,
        timezone: String,
        period: String,
    ): PeriodWindow {
        val zone = zoneOf(timezone)
        val ym = parsePeriod(period, zone)
        val start =
            invoiceRepository
                .findByOrgIdAndPeriod(orgId, ym.minusMonths(1).toString())
                ?.takeIf { it.statusEnum() == InvoiceStatus.FINALIZED }
                ?.periodEnd
                ?: ym.atDay(1).atStartOfDay(zone).toInstant()
        val end =
            invoiceRepository
                .findByOrgIdAndPeriod(orgId, ym.plusMonths(1).toString())
                ?.takeIf { it.statusEnum() == InvoiceStatus.FINALIZED }
                ?.periodStart
                ?: ym
                    .plusMonths(1)
                    .atDay(1)
                    .atStartOfDay(zone)
                    .toInstant()
        return PeriodWindow(ym.toString(), zone, start, end)
    }

    /**
     * draft 생성/재생성. 기존 DRAFT 는 라인을 전량 삭제 후 재계산(full-replace)하고, FINALIZED 가 이미
     * 있으면 409 다. 동시성: (org, period) 행 FOR UPDATE 로 재생성끼리 직렬화하고, 행이 없는 최초 동시
     * 생성은 UNIQUE(org_id, period) 위반 → GlobalExceptionHandler 가 409 로 매핑한다(재시도 안전).
     */

    /**
     * @param actorId 이 생성을 요청한 사람. **null 은 스케줄 잡**(월 자동 생성)을 뜻한다 — 감사 로그의
     *   행위자가 비면 "사람이 아니라 시스템이 만들었다"가 그대로 읽힌다(가짜 행위자를 지어내지 않는다).
     */
    @Transactional
    fun generate(
        orgId: UUID,
        period: String,
        actorId: UUID?,
    ): InvoiceDetailView {
        val org = organizationService.requireOrg(orgId)

        val existing = invoiceRepository.findByOrgIdAndPeriodForUpdate(orgId, period)
        if (existing != null && existing.statusEnum() == InvoiceStatus.FINALIZED) {
            throw AuthException(ErrorCode.INVOICE_ALREADY_FINALIZED)
        }

        val window = periodWindow(orgId, org.timezone, period)
        val from = window.start
        val to = window.end

        /*
         * ★**거래가 없으면 만들지 않는다.**
         *
         * 청구서는 확정하면 불변으로 굳고 삭제·확정취소 API 가 없다 — 즉 이 함수는 **되돌릴 수 없는
         * 문서를 만드는 버튼**이다. 그런 버튼이 빈 결과를 만들면 안 된다. 실제로 조직 개요의
         * '처리 대기'가 활동 없는 달을 재촉했고, 따라가 누르면 0원 초안이 생기고, 그러면 항목이
         * '확정하기'로 바뀌어 다시 재촉해 결국 **0원 확정 청구서**가 영구히 남았다(재촉은 그제서야
         * 멈춘다 — 사용자를 그 결말로 몰고 간 셈이다).
         *
         * 자동 생성 잡은 이미 [hasBillableActivity] 로 같은 판정을 하고 있었다. 수동 경로에만 없었다는
         * 것은 규칙이 아니라 누락이었다는 뜻이다 — 판정을 여기로 옮겨 두 경로가 같은 규칙을 쓴다.
         *
         * 이미 있는 초안의 재생성은 막지 않는다: 거래가 모두 취소돼 0원이 된 상황을 초안에 반영하는
         * 것은 정당하다(그건 새 문서를 만드는 일이 아니다).
         */
        if (existing == null && transactionRepository.countApprovedForOrgInWindow(orgId, from, to) == 0L) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "$period 에는 청구할 거래가 없어 청구서를 만들지 않았습니다",
            )
        }

        val rows = transactionRepository.aggregateOrgPaidByUser(orgId, from, to)
        val userIds = rows.map { it[0] as UUID }
        val emailById = userRepository.findAllById(userIds).associate { it.id!! to it.email }
        val membershipByUser = membershipRepository.findByOrgId(orgId).associateBy { it.userId }
        val deptNameById = departmentRepository.findByOrgId(orgId).associate { it.id!! to it.name }

        var subtotal = 0L
        var txnCount = 0

        data class LineData(
            val userId: UUID,
            val email: String,
            val deptId: UUID?,
            val deptName: String?,
            val count: Int,
            val amount: Long,
        )
        val lineData =
            rows
                .map { row ->
                    val userId = row[0] as UUID
                    val count = (row[1] as Long).toInt()
                    val amount = row[2] as Long
                    subtotal += amount
                    txnCount += count
                    val deptId = membershipByUser[userId]?.departmentId
                    LineData(
                        userId = userId,
                        // users 행이 사라진(탈퇴) 사용자도 거래 사실은 청구된다 — 라벨만 UUID 로 대체.
                        email = emailById[userId] ?: "(deleted:$userId)",
                        deptId = deptId,
                        deptName = deptId?.let { deptNameById[it] },
                        count = count,
                        amount = amount,
                    )
                }.sortedBy { it.email.lowercase() }

        val invoice =
            if (existing != null) {
                lineRepository.deleteByInvoiceId(existing.id!!)
                existing.periodStart = from
                existing.periodEnd = to
                existing.subtotalMinor = subtotal
                existing.txnCount = txnCount
                existing.generatedAt = Instant.now()
                existing
            } else {
                invoiceRepository.save(
                    Invoice(
                        orgId = orgId,
                        period = period,
                        periodStart = from,
                        periodEnd = to,
                        subtotalMinor = subtotal,
                        txnCount = txnCount,
                    ),
                )
            }
        val lines =
            lineRepository.saveAll(
                lineData.map {
                    InvoiceLine(
                        invoiceId = invoice.id!!,
                        userId = it.userId,
                        userEmail = it.email,
                        departmentId = it.deptId,
                        departmentName = it.deptName,
                        txnCount = it.count,
                        amountMinor = it.amount,
                    )
                },
            )

        auditEventService.record(
            "INVOICE_GENERATED",
            actorId,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "period" to period,
                "subtotalMinor" to subtotal,
                "txnCount" to txnCount,
                "regenerated" to (existing != null),
            ),
        )
        return toDetailView(invoice, lines)
    }

    /**
     * DRAFT → FINALIZED(불변화). 이미 FINALIZED 면 409 — 멱등 200 은 "확정됨"의 의미를 흐린다.
     * 확정 전 **stale draft 검증**: 마지막 generate 이후 void/늦은 승인으로 집계가 달라졌으면
     * 409(INVOICE_STALE) — FINALIZED 는 정정 경로가 없으므로 낡은 합계를 조용히 굳히지 않는다.
     */
    @Transactional
    fun finalize(
        orgId: UUID,
        invoiceId: UUID,
        actorId: UUID,
    ): InvoiceView {
        organizationService.requireOrg(orgId)
        val invoice =
            invoiceRepository.findByIdAndOrgIdForUpdate(invoiceId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "청구서를 찾을 수 없습니다")
        if (invoice.statusEnum() == InvoiceStatus.FINALIZED) {
            throw AuthException(ErrorCode.INVOICE_ALREADY_FINALIZED)
        }
        // draft 와 같은 창(스냅샷)으로 재집계해 합계·건수가 여전히 일치하는지 확인한다. FOR UPDATE 행 잠금
        // 아래라 동시 generate 와 직렬화된다(같은 트랜잭션 안에서 판정·확정이 원자적).
        val rows = transactionRepository.aggregateOrgPaidByUser(orgId, invoice.periodStart, invoice.periodEnd)
        val currentSubtotal = rows.sumOf { it[2] as Long }
        val currentTxnCount = rows.sumOf { (it[1] as Long).toInt() }
        if (currentSubtotal != invoice.subtotalMinor || currentTxnCount != invoice.txnCount) {
            throw AuthException(ErrorCode.INVOICE_STALE, "생성 이후 거래가 변경되었습니다. 재생성 후 확정하세요")
        }
        invoice.status = InvoiceStatus.FINALIZED.name
        invoice.finalizedAt = Instant.now()
        auditEventService.record(
            "INVOICE_FINALIZED",
            actorId,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "invoiceId" to invoice.id.toString(),
                "period" to invoice.period,
                "subtotalMinor" to invoice.subtotalMinor,
            ),
        )
        return toView(invoice)
    }

    /**
     * 그 기간에 **청구할 거래가 있는가**. 자동 생성 잡이 빈 청구서를 만들지 않도록 먼저 묻는다 —
     * 만들었다가 지우면 존재하지 않는 청구서의 감사 이벤트가 활동로그에 남는다.
     */
    @Transactional(readOnly = true)
    fun hasBillableActivity(
        orgId: UUID,
        period: String,
    ): Boolean {
        val org = organizationService.requireOrg(orgId)
        val window = periodWindow(orgId, org.timezone, period)
        return transactionRepository.countApprovedForOrgInWindow(orgId, window.start, window.end) > 0
    }

    /**
     * **확정되지 않은 청구서 현황** — 자동 생성 루프의 마지막 구멍.
     *
     * 초안은 자동으로 만들어지고 조직관리자에게 메일까지 나가지만, 확정하지 않으면 그대로 방치된다.
     * 그러면 회사가 쓴 식대를 우리가 **끝내 청구하지 않고** 그 사실을 아무도 모른다 — 자동 생성 잡이
     * 막으려던 사고(알람이 울리지 않는 매출 누락)가 한 단계 뒤에서 그대로 반복된다.
     *
     * 대상은 그 달에 **청구할 거래가 있었던 조직**이다(`orgIdsWithActivity`). 활동이 없으면 청구서가
     * 없는 것이 정상이므로 목록에 올리지 않는다 — 정상 상태가 경보에 섞이면 진짜 누락이 묻힌다.
     *
     * ★`@Transactional` 을 걸지 않는다(`reconcileAll`·`platformPayables` 와 같은 이유): 최대 500개
     * 조직을 훑는 동안 커넥션 하나를 계속 쥐지 않고, 조직 하나의 실패가 요청 전체를 rollback-only 로
     * 물들이지 않게 한다.
     */
    fun listUnfinalized(
        period: String,
        limit: Int = MAX_ORGS_SCANNED,
    ): UnfinalizedInvoicesView {
        val month = parsePeriodOrThrow(period)
        // 타임존 최대 편차(±14시간)를 덮도록 하루씩 넓힌다 — 좁으면 경계의 조직을 놓친다.
        val from =
            month
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .minus(1, ChronoUnit.DAYS)
        val to =
            month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .plus(1, ChronoUnit.DAYS)

        val candidates = transactionRepository.orgIdsWithActivity(from, to)
        val scanned = candidates.take(limit)
        val orgs = organizationRepository.findAllById(scanned).associateBy { it.id }
        val lines = mutableListOf<UnfinalizedInvoiceLine>()
        var failed = 0

        for (orgId in scanned) {
            val org = orgs[orgId] ?: continue
            try {
                val invoice = invoiceRepository.findByOrgIdAndPeriod(orgId, month.toString())
                when {
                    // 확정됐다 — 정상이므로 목록에 올리지 않는다.
                    invoice != null && invoice.statusEnum() == InvoiceStatus.FINALIZED -> Unit
                    invoice != null ->
                        lines +=
                            line(orgId, org.name, org.timezone, "DRAFT", invoice.subtotalMinor, invoice.txnCount, invoice.generatedAt)
                    // ★아직 만들 시점이 아니다(유예 기간) — 잡과 **같은 판정**을 쓴다. 이 분기가 없으면
                    // 매달 1~2일에 모든 조직이 "청구서 없음" 적색 경보로 뜨고 진짜 실패가 그 속에 묻힌다.
                    graceWindow.notYetDue(org.timezone, month) -> lines += line(orgId, org.name, org.timezone, "PENDING")
                    // 청구서 행이 아예 없다 — 그 달에 청구할 거래가 실제로 있을 때만 문제다
                    // (UTC 로 넓힌 창에만 걸린 경계 거래는 org 로컬 달에는 없을 수 있다).
                    hasBillableActivity(orgId, month.toString()) -> lines += line(orgId, org.name, org.timezone, "MISSING")
                    else -> Unit
                }
            } catch (ex: Exception) {
                // ★삼키되 **세어서 드러낸다**. 조용히 빼면 "전부 확정됨"이 거짓이 되고, 하필 문제가 있는
                // 조직일수록 목록에서 사라진다 — 이 화면이 존재하는 이유가 그 지점에서 뒤집힌다.
                failed++
                log.warn("unfinalized scan failed orgId={} period={}", orgId, month, ex)
            }
        }

        return UnfinalizedInvoicesView(
            period = month.toString(),
            scanned = scanned.size,
            skipped = (candidates.size - scanned.size).coerceAtLeast(0),
            failed = failed,
            // 심각도 순 — MISSING(시스템이 못 만듦) → DRAFT(사람이 안 누름) → PENDING(아직 시점 아님).
            lines = lines.sortedWith(compareBy({ STATE_ORDER.indexOf(it.state) }, { it.orgName })),
        )
    }

    private fun line(
        orgId: UUID,
        orgName: String,
        timezone: String,
        state: String,
        subtotalMinor: Long? = null,
        txnCount: Int? = null,
        generatedAt: Instant? = null,
    ) = UnfinalizedInvoiceLine(orgId, orgName, timezone, state, subtotalMinor, txnCount, generatedAt)

    @Transactional(readOnly = true)
    fun list(orgId: UUID): List<InvoiceView> {
        organizationService.requireOrg(orgId)
        return invoiceRepository.findByOrgIdOrderByPeriodDesc(orgId).map { toView(it) }
    }

    @Transactional(readOnly = true)
    fun detail(
        orgId: UUID,
        invoiceId: UUID,
    ): InvoiceDetailView {
        organizationService.requireOrg(orgId)
        val invoice =
            invoiceRepository.findByIdAndOrgId(invoiceId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "청구서를 찾을 수 없습니다")
        return toDetailView(invoice, lineRepository.findByInvoiceIdOrderByUserEmailAsc(invoiceId))
    }

    /**
     * 형식만 검증하는 전역용 파서. 조직마다 타임존이 달라 "미래 월"의 기준이 하나로 정해지지 않으므로
     * 전역 조회(listUnfinalized)에서는 미래 월 거절을 하지 않는다 — 조회일 뿐이고 결과는 빈 목록이다.
     */
    private fun parsePeriodOrThrow(period: String): YearMonth {
        if (!PERIOD_PATTERN.matches(period)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "period 는 'YYYY-MM' 형식이어야 합니다")
        }
        return YearMonth.parse(period)
    }

    /** 형식(YYYY-MM) 검증 + org 타임존 기준 미래 월 거절(400). 당월은 월중 draft 미리보기로 허용한다. */
    private fun parsePeriod(
        period: String,
        zone: ZoneId,
    ): YearMonth {
        if (!PERIOD_PATTERN.matches(period)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "period 는 'YYYY-MM' 형식이어야 합니다")
        }
        val ym = YearMonth.parse(period)
        if (ym.isAfter(YearMonth.now(zone))) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "미래 월은 청구할 수 없습니다")
        }
        return ym
    }

    /** MealRedeemService.zoneOf 와 동일한 방어 — 저장 시 검증되지만 유사시 UTC 로 강등한다. */
    private fun zoneOf(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    private fun toView(invoice: Invoice): InvoiceView =
        InvoiceView(
            id = invoice.id!!,
            period = invoice.period,
            status = invoice.status,
            subtotalMinor = invoice.subtotalMinor,
            txnCount = invoice.txnCount,
            generatedAt = invoice.generatedAt,
            finalizedAt = invoice.finalizedAt,
        )

    private fun toDetailView(
        invoice: Invoice,
        lines: List<InvoiceLine>,
    ): InvoiceDetailView {
        val lineViews =
            lines.map {
                InvoiceLineView(
                    userId = it.userId,
                    userEmail = it.userEmail,
                    departmentId = it.departmentId,
                    departmentName = it.departmentName,
                    txnCount = it.txnCount,
                    amountMinor = it.amountMinor,
                )
            }
        // 부서 소계는 저장하지 않고 라인 스냅샷에서 파생한다(미배정은 departmentId null 그룹, 이름순·미배정 뒤).
        val subtotals =
            lineViews
                .groupBy { it.departmentId to it.departmentName }
                .map { (key, group) ->
                    DepartmentSubtotalView(
                        departmentId = key.first,
                        departmentName = key.second,
                        txnCount = group.sumOf { it.txnCount },
                        amountMinor = group.sumOf { it.amountMinor },
                    )
                }.sortedWith(compareBy({ it.departmentName == null }, { it.departmentName?.lowercase() }))
        return InvoiceDetailView(
            id = invoice.id!!,
            period = invoice.period,
            status = invoice.status,
            subtotalMinor = invoice.subtotalMinor,
            txnCount = invoice.txnCount,
            generatedAt = invoice.generatedAt,
            finalizedAt = invoice.finalizedAt,
            lines = lineViews,
            departmentSubtotals = subtotals,
        )
    }
}
