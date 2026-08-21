package com.taspa.server.billing

import com.taspa.server.billing.dto.PlatformReconciliationView
import com.taspa.server.billing.dto.ReconciliationLeg
import com.taspa.server.billing.dto.ReconciliationReport
import com.taspa.server.billing.dto.ReconciliationSummary
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.ledger.LedgerAccount
import com.taspa.server.domain.ledger.LedgerEntryRepository
import com.taspa.server.domain.ledger.LedgerPostingRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.meal.MealPolicyCalculus
import com.taspa.server.org.OrganizationService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 3-way 대사 — **원장 · 장부 · 소비이벤트**가 같은 사실을 말하는지 확인한다.
 *
 * 실 자금이동이 없는 지금 단계에서 은행 명세는 없다. 대신 이 시스템에는 서로 독립적으로 쓰이는
 * 세 기록이 있고, 그 셋이 어긋나는 것이 지금까지 실제로 문제를 일으킨 형태다:
 *  1. **원장**(ledger_postings) — 부호 있는 분개의 합. append-only 라 소급 변경이 없다.
 *  2. **장부**(meal_transactions) — 현재 상태. 환불이 금액을 **소급 변경**한다(V36).
 *  3. **소비이벤트**(consumption_events) — 예측의 정답데이터. full-replace 로 갱신된다.
 *
 * 금액축은 1↔2, 건수축은 2↔3 을 맞춘다. 어긋나면 그건 취향 차이가 아니라 **버그의 직접 증거**다 —
 * 세 기록은 같은 트랜잭션에서 쓰이므로 정상 동작에서는 절대 갈라지지 않는다.
 *
 * ★대사가 "항상 0" 이라 무의미한 것 아닌가 — 아니다. 이 시스템은 이미 세 번, 이 축에서 갈라졌다:
 * 소비 full-replace 의 site 귀속 드리프트, void 후 stale 청구서 확정, 그리고 환불이 만든 소급 변경.
 * 같은 트랜잭션이라는 전제가 깨지는 순간(새 경로 추가·예외 삼킴) 여기서 드러난다.
 */
@Service
class ReconciliationService(
    private val postingRepository: LedgerPostingRepository,
    private val entryRepository: LedgerEntryRepository,
    private val transactionRepository: MealTransactionRepository,
    private val consumptionEventRepository: ConsumptionEventRepository,
    private val organizationService: OrganizationService,
    private val organizationRepository: OrganizationRepository,
    // 항목마다 **프록시를 거쳐** 짧은 트랜잭션을 열기 위한 자기 참조. 같은 빈의 메서드를 그냥 부르면
    // 프록시를 타지 않아 @Transactional 이 적용되지 않는다(자기 호출 함정).
    private val self: ObjectProvider<ReconciliationService>,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ReconciliationService::class.java)

    @Transactional(readOnly = true)
    fun reconcile(
        orgId: UUID,
        period: String,
    ): ReconciliationReport {
        val org = organizationService.requireOrg(orgId)
        val month = parsePeriod(period)
        val zone = MealPolicyCalculus.zoneOf(org.timezone)
        // 청구서와 **같은 창**이어야 한다 — 창이 다르면 대사가 잡는 차이가 진짜 불일치인지
        // 창 차이인지 구분할 수 없고, 그 순간 이 보고는 쓸모가 없어진다.
        val from = month.atDay(1).atStartOfDay(zone).toInstant()
        val to =
            month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(zone)
                .toInstant()

        val ledgerOrgReceivable =
            postingRepository.balance(orgId, LedgerAccount.ORG_RECEIVABLE.name, from, to)
        val ledgerMerchantPayable =
            postingRepository.balance(orgId, LedgerAccount.MERCHANT_PAYABLE.name, from, to)

        // 같은 집계를 두 번 부르지 않는다 — 두 값이 다른 스냅샷에서 나오면 대사가 자기 질의 사이의
        // 커밋 때문에 어긋날 수 있다(읽기 트랜잭션 안이라 실제로는 같지만, 두 번 부를 이유가 없다).
        val bookRows = transactionRepository.aggregateOrgPaidByUser(orgId, from, to)
        val bookOrgPaid = bookRows.sumOf { (it[2] as Number).toLong() }
        val bookCount = bookRows.sumOf { (it[1] as Number).toLong() }
        val consumptionCount = consumptionEventRepository.countConfirmedInWindow(orgId, from, to)

        val unbalanced = postingRepository.unbalancedEntryIds(orgId, from, to)

        return ReconciliationReport(
            orgId = orgId,
            period = month.toString(),
            timezone = org.timezone,
            periodStart = from,
            periodEnd = to,
            legs =
                listOf(
                    ReconciliationLeg(
                        name = "원장 미수금(조직 부담)",
                        kind = "AMOUNT",
                        value = ledgerOrgReceivable,
                    ),
                    ReconciliationLeg(name = "장부 조직 부담", kind = "AMOUNT", value = bookOrgPaid),
                    ReconciliationLeg(name = "장부 승인 건수", kind = "COUNT", value = bookCount),
                    ReconciliationLeg(name = "소비 이벤트 건수", kind = "COUNT", value = consumptionCount),
                ),
            amountDrift = ledgerOrgReceivable - bookOrgPaid,
            countDrift = bookCount - consumptionCount,
            // 대차평형은 금액이 맞는지와 **별개**다. 합이 0 이 아닌 사건이 있으면 원장 자체가 깨진 것이고,
            // 그 상태에서는 잔액이 우연히 맞아도 신뢰할 수 없다.
            unbalancedEntryCount = unbalanced.size,
            // 우리가 가맹에 줄 돈은 조직 미수금의 정확한 반대여야 한다(통과 지점이므로).
            passThroughDrift = ledgerOrgReceivable + ledgerMerchantPayable,
        )
    }

    private fun parsePeriod(period: String): YearMonth =
        try {
            YearMonth.parse(period.trim())
        } catch (_: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "기간 형식이 올바르지 않습니다 (YYYY-MM)")
        }

    /**
     * **플랫폼 전역 대사** — 어느 조직이든 장부가 깨졌는지 한 번에 본다.
     *
     * org 마다 달 경계가 다르므로(타임존) 한 번의 group-by 로 끝낼 수 없다. 그래서 그 달을 포함하는
     * **넉넉한 UTC 창**으로 활동 있는 조직을 추린 뒤 조직별로 정확히 계산한다. 전 조직 순회가 아니라
     * 활동 있는 조직으로 한정되므로 비용이 실제 사용량에 비례한다.
     *
     * ★상한에 걸려 못 본 조직 수를 **함께 반환한다**. 조용히 자르면 "이상 없음"이 "안 봤음"과
     * 구별되지 않고, 그 화면은 있으나 마나가 된다.
     */
    /*
     * ★이 메서드에는 `@Transactional` 이 **없다**(의도적). 두 가지 이유가 있고 둘 다 실제 결함이었다:
     *
     * 1. 조직 하나가 사라져 `reconcile` 이 던지면, 그 예외가 다른 빈(`OrganizationService.requireOrg`,
     *    @Transactional)의 참여 트랜잭션을 지나며 **공유 트랜잭션을 rollback-only 로 표시**한다.
     *    아래 runCatching 이 예외를 삼켜 루프는 계속되지만, 커밋 시점에 UnexpectedRollbackException 이
     *    터져 **요청 전체가 500** 이 된다 — "건너뛴다"고 쓴 코드가 실제로는 건너뛰지 못한다.
     * 2. 읽기 트랜잭션 하나로 최대 500개 조직을 훑으면 그동안 커넥션 1개를 계속 쥔다. 커넥션 풀이
     *    워커 풀보다 20배 먼저 죽는 이 시스템에서(CLAUDE.md "운영 준비") 조회 화면이 그럴 이유가 없다.
     *
     * 대신 항목마다 **프록시를 거쳐**(`self`) 짧은 readOnly 트랜잭션 하나씩 열어, 조직 단위 일관성은
     * 지키면서 실패는 그 조직에만 가둔다.
     */
    fun reconcileAll(
        period: String,
        limit: Int = MAX_ORGS,
    ): PlatformReconciliationView {
        val month = parsePeriod(period)
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

        // ★후보는 **원장 ∪ 장부**다. 원장에서만 뽑으면 "원장이 통째로 빠진 조직"이 표본에서 구조적으로
        // 빠져, 조직별 대사는 불일치라고 하는데 전역 화면만 침묵한다(MealTransactionRepository
        // .orgIdsWithActivity KDoc 참조 — 소비이벤트를 후보에 넣지 않는 이유도 거기 있다).
        // ★순서가 의미를 갖는다: 상한에 걸려 잘릴 때 **장부에만 있는 조직**(= 원장이 빠진 조직, 이 합집합을
        // 도입한 바로 그 대상)이 먼저 잘리면 확장이 무의미해진다. 그래서 그쪽을 **앞에** 둔다.
        val ledgerOrgs = entryRepository.orgIdsWithActivity(from, to).toSet()
        val bookOrgs = transactionRepository.orgIdsWithActivity(from, to)
        val candidates = (bookOrgs.filterNot { it in ledgerOrgs } + ledgerOrgs).distinct()
        val scanned = candidates.take(limit)
        // 이름·타임존을 한 번에 읽어 둔다(조직마다 requireOrg 를 다시 부르지 않게) — 여기 없는 id 는
        // 그 사이 삭제된 조직이라 애초에 대사하지 않는다(예외 경로에 의존하지 않는 사전 필터).
        val orgs = organizationRepository.findAllById(scanned).associateBy { it.id }
        var failed = 0
        val summaries =
            scanned.mapNotNull { orgId ->
                val org = orgs[orgId] ?: return@mapNotNull null
                // 사전 필터를 통과해도 그 사이 삭제될 수 있다. ★그 외의 실패(일시적 DB 오류·풀 고갈)를 조용히
                // 삼키면 **불일치가 있는 조직일수록 목록에서 사라지고** 화면은 "전부 일치"라고 단언한다 —
                // 경보 화면의 목적이 정확히 뒤집힌다. 그래서 삼키되 세어서 드러낸다.
                val report =
                    try {
                        self.getObject().reconcile(orgId, month.toString())
                    } catch (ex: Exception) {
                        failed++
                        log.warn("reconcile failed orgId={} period={}", orgId, month, ex)
                        return@mapNotNull null
                    }
                if (report.balanced) return@mapNotNull null
                ReconciliationSummary(
                    orgId = orgId,
                    orgName = org.name,
                    timezone = org.timezone,
                    period = report.period,
                    balanced = false,
                    amountDrift = report.amountDrift,
                    countDrift = report.countDrift,
                    unbalancedEntryCount = report.unbalancedEntryCount,
                    passThroughDrift = report.passThroughDrift,
                )
            }
        return PlatformReconciliationView(
            period = month.toString(),
            scanned = scanned.size,
            unbalanced = summaries,
            skipped = (candidates.size - scanned.size).coerceAtLeast(0),
            failed = failed,
        )
    }

    /** 조직 로컬 달의 시작일 — 화면이 기본 기간을 고를 때 쓴다. */
    fun currentPeriod(timezone: String): String = YearMonth.from(LocalDate.now(MealPolicyCalculus.zoneOf(timezone))).toString()

    private companion object {
        /** 한 번에 훑을 조직 수 상한 — 자원고갈 방지. 넘치면 `skipped` 로 **드러낸다**. */
        const val MAX_ORGS = 500
    }
}
