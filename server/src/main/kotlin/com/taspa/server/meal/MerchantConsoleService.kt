package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.meal.MealRefundRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantMemberRepository
import com.taspa.server.domain.meal.MerchantMemberStatus
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.meal.MerchantRole
import com.taspa.server.domain.meal.MerchantStatus
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.meal.dto.BlockedMerchantView
import com.taspa.server.meal.dto.MerchantSettlementLine
import com.taspa.server.meal.dto.MerchantSettlementView
import com.taspa.server.meal.dto.MerchantTransactionView
import com.taspa.server.meal.dto.MerchantTransactionsResponse
import com.taspa.server.meal.dto.MyMerchantView
import com.taspa.server.meal.dto.MyMerchantsResponse
import com.taspa.server.meal.dto.PlatformPayableLine
import com.taspa.server.meal.dto.PlatformPayablesView
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 가맹 관리자 콘솔(사람 신원 — V29) 조회 서비스. **결제 승인은 여전히 기계 전용**이다(POS = M2M +
 * merchant_id 클레임): 여기에는 어떤 승인·취소 경로도 없고, 계정 탈취가 곧 무단 결제가 되지 않도록
 * 조회와 승인의 신원을 분리해 둔다.
 *
 * 개인정보 최소화가 이 서비스의 설계 제약이다 — 매장에 나가는 어떤 응답도 손님의 userId·이메일·이름을
 * 담지 않는다(DTO 에 자리 자체가 없다). 매장이 필요로 하는 것은 인분 수와 정산 대사용 조직·금액이다.
 */
@Service
class MerchantConsoleService(
    private val merchantRepository: MerchantRepository,
    private val merchantMemberRepository: MerchantMemberRepository,
    private val transactionRepository: MealTransactionRepository,
    private val refundRepository: MealRefundRepository,
    private val organizationRepository: OrganizationRepository,
    // 항목마다 프록시를 거쳐 짧은 트랜잭션을 열기 위한 자기 참조(자기 호출은 프록시를 타지 않는다).
    private val self: ObjectProvider<MerchantConsoleService>,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(MerchantConsoleService::class.java)

    /**
     * 내가 관리하는 가맹점 목록(매장 선택).
     *
     * `merchants`(진입 가능 집합)는 멤버십 ACTIVE ∧ 역할 MERCHANT_ADMIN ∧ 매장 ACTIVE 만 —
     * [isActiveMerchantAdmin] 이 요구하는 조건과 **정확히 같은 집합**이라, 목록에 보이는데 열면 403 인
     * 항목이 생기지 않는다. 두 곳의 조건을 따로 고치지 말 것.
     *
     * `blocked` 는 그 조건 중 **매장 상태만** 어긋난 항목이다. 진입 집합이 아니므로 위 불변식은 그대로고,
     * 대신 "지정은 됐는데 아무것도 안 보인다"는 침묵이 사라진다(사유는 [BlockedMerchantView] 참조).
     */
    @Transactional(readOnly = true)
    fun listMyMerchants(userId: UUID): MyMerchantsResponse {
        val memberships =
            merchantMemberRepository
                .findByUserIdAndStatus(userId, MerchantMemberStatus.ACTIVE.name)
                .filter { it.roleEnum() == MerchantRole.MERCHANT_ADMIN }
        if (memberships.isEmpty()) return MyMerchantsResponse(emptyList(), emptyList())
        val merchants =
            merchantRepository
                .findAllById(memberships.map { it.merchantId })
                .associateBy { it.id }
        val open = mutableListOf<MyMerchantView>()
        val blocked = mutableListOf<BlockedMerchantView>()
        memberships.forEach { membership ->
            val merchant = merchants[membership.merchantId] ?: return@forEach
            if (merchant.statusEnum() != MerchantStatus.ACTIVE) {
                // ★진입 집합에는 넣지 않는다(위 불변식) — 대신 **사유와 함께** 존재를 알린다.
                blocked += BlockedMerchantView(merchant.id!!, merchant.name, merchant.status)
                return@forEach
            }
            open +=
                MyMerchantView(
                    merchantId = merchant.id!!,
                    name = merchant.name,
                    category = merchant.category,
                    status = merchant.status,
                    timezone = merchant.timezone,
                    role = membership.role,
                )
        }
        return MyMerchantsResponse(open.sortedBy { it.name }, blocked.sortedBy { it.name })
    }

    /**
     * **인가 판정에 넘길 사실 하나**(판정이 아니다) — 요청 경로의 가맹점에 대한 활성 관리 멤버십 여부.
     * 멤버십 ACTIVE ∧ **역할 MERCHANT_ADMIN** ∧ 매장 ACTIVE 셋 다여야 true 다: 정지된 매장의 데이터는
     * 잔여 멤버십으로도 열리지 않고, 역할을 보지 않으면 나중에 추가될 조회 전용 역할이 이 경로로
     * 관리자 권한을 얻는다. 컨트롤러는 이 값을 `decideSession(merchantAdminOf = ...)` 로만 쓰고
     * 직접 allow/deny 를 합성하지 않는다.
     */
    @Transactional(readOnly = true)
    fun isActiveMerchantAdmin(
        merchantId: UUID,
        userId: UUID,
    ): Boolean {
        val membership = merchantMemberRepository.findByMerchantIdAndUserId(merchantId, userId) ?: return false
        if (membership.statusEnum() != MerchantMemberStatus.ACTIVE) return false
        if (membership.roleEnum() != MerchantRole.MERCHANT_ADMIN) return false
        val merchant = merchantRepository.findById(merchantId).orElse(null) ?: return false
        return merchant.statusEnum() == MerchantStatus.ACTIVE
    }

    /**
     * 자기 매장 거래(식수 로그). 날짜는 **매장 타임존** 기준 [from, to] 로컬 달력이고, 조회 창은
     * [MAX_WINDOW_DAYS]일로 상한한다 — 상한에 걸리면 조용히 자르지 않고 응답의 from/to(실효)와
     * windowTruncated 로 드러낸다. 행 상한(limit)도 마찬가지로 rowsTruncated 로 알린다.
     *
     * VOIDED 거래도 반환한다 — 취소는 매장이 알아야 할 사실이다(집계에서 빠지는 것과 별개).
     */
    @Transactional(readOnly = true)
    fun transactions(
        merchantId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int?,
    ): MerchantTransactionsResponse {
        val merchant = requireMerchant(merchantId)
        val zone = zoneOf(merchant)
        val requestedTo = to ?: LocalDate.now(zone)
        val requestedFrom = from ?: requestedTo.minusDays(DEFAULT_WINDOW_DAYS - 1)
        if (requestedTo.isBefore(requestedFrom)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "to 는 from 보다 뒤여야 합니다")
        }
        // 거래 로그는 최근순이 관심사라, 상한 초과 시 **from 을 앞으로 당겨** 최신 구간을 남긴다.
        val effectiveFrom = maxOf(requestedFrom, requestedTo.minusDays(MAX_WINDOW_DAYS - 1))
        val effectiveLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val rows =
            transactionRepository.findByMerchantInWindow(
                merchantId,
                effectiveFrom.atStartOfDay(zone).toInstant(),
                requestedTo.plusDays(1).atStartOfDay(zone).toInstant(),
                PageRequest.of(0, effectiveLimit),
            )
        // 조직명 배치 해소(N+1 방지). 조직이 사라진 과거 거래는 null 로 남긴다(로그는 불변).
        val orgNames =
            organizationRepository
                .findAllById(rows.mapTo(HashSet()) { it.orgId })
                .associate { it.id to it.name }
        // 환불 누계 배치 해소(N+1 방지). 환불이 있는 거래만 물어본다 — 대부분의 거래에는 없다.
        val refundedIds = rows.filter { it.refundedMinor > 0 }.mapNotNull { it.id }
        val refunds =
            if (refundedIds.isEmpty()) {
                emptyMap()
            } else {
                refundRepository.summarizeByTransactionIds(refundedIds).associateBy { it.getTransactionId() }
            }
        return MerchantTransactionsResponse(
            merchantId = merchantId,
            timezone = merchant.timezone,
            from = effectiveFrom,
            to = requestedTo,
            requestedFrom = requestedFrom,
            requestedTo = requestedTo,
            windowTruncated = effectiveFrom != requestedFrom,
            limit = effectiveLimit,
            rowsTruncated = rows.size >= effectiveLimit,
            rows =
                rows.map { tx ->
                    val refund = tx.id?.let { refunds[it] }
                    MerchantTransactionView(
                        authId = tx.authId,
                        posTxnId = tx.posTxnId,
                        orgName = orgNames[tx.orgId],
                        mealWindow = tx.mealWindow,
                        amountMinor = tx.amountMinor,
                        orgPaidMinor = tx.orgPaidMinor(),
                        selfPaidMinor = tx.selfPaidMinor,
                        status = tx.status,
                        approvedAt = tx.approvedAt,
                        voidedAt = tx.voidedAt,
                        refundedMinor = tx.refundedMinor,
                        orgRefundedMinor = refund?.getOrgRefunded() ?: 0,
                        selfRefundedMinor = refund?.getSelfRefunded() ?: 0,
                        originalAmountMinor = tx.originalAmountMinor(),
                        refundCount = refund?.getRefundCount()?.toInt() ?: 0,
                        lastRefundedAt = refund?.getLastRefundedAt(),
                    )
                },
        )
    }

    /**
     * 월 정산 명세 — "이번 달 우리가 얼마를 받는가".
     *
     * 그전까지 매장은 거래 로그를 눈으로 더해야 했다. 창은 **매장 타임존** 월 경계다(조직 달력을 빌릴 수
     * 없다 — 한 매장이 여러 조직 손님을 받는다). 그래서 조직 청구서 합계와 경계일 거래만큼 다를 수 있고,
     * 그건 결함이 아니라 두 문서가 서로 다른 달력을 쓴다는 사실이다.
     *
     * ★지급 대상은 **조직부담 합**뿐이다. 개인부담은 손님이 계산대에서 이미 냈으므로 여기 더하면 매장이
     * 받을 돈을 두 배로 기대하게 된다 — DTO 가 두 주머니를 갈라 두는 이유.
     */
    @Transactional(readOnly = true)
    fun settlement(
        merchantId: UUID,
        period: String,
    ): MerchantSettlementView {
        val merchant = requireMerchant(merchantId)
        val month =
            try {
                YearMonth.parse(period.trim())
            } catch (_: DateTimeParseException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "기간 형식이 올바르지 않습니다 (YYYY-MM)")
            }
        val zone = zoneOf(merchant)
        val from = month.atDay(1).atStartOfDay(zone).toInstant()
        val to =
            month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(zone)
                .toInstant()

        val rows = transactionRepository.aggregateMerchantSettlementByOrg(merchantId, from, to)
        val orgNames =
            organizationRepository
                .findAllById(rows.map { it[0] as UUID })
                .associate { it.id to it.name }
        val lines =
            rows
                .map { row ->
                    val orgId = row[0] as UUID
                    MerchantSettlementLine(
                        orgId = orgId,
                        orgName = orgNames[orgId],
                        approvedCount = (row[1] as Number).toLong(),
                        orgPaidMinor = (row[2] as Number).toLong(),
                        selfPaidMinor = (row[3] as Number).toLong(),
                        refundedMinor = (row[4] as Number).toLong(),
                    )
                }
                // 취소만 있었던 조직은 싣지 않는다 — 전부 0 인 줄은 읽는 사람의 주의만 소모한다.
                // (전액 **환불**된 조직은 refundedMinor > 0 이라 남는다: "환불 때문에 0원"은 사실이고 보여야 한다.)
                .filter { it.approvedCount > 0 || it.refundedMinor > 0 }
                .sortedByDescending { it.orgPaidMinor }

        return MerchantSettlementView(
            merchantId = merchantId,
            merchantName = merchant.name,
            period = month.toString(),
            timezone = merchant.timezone,
            periodStart = from,
            periodEnd = to,
            approvedCount = lines.sumOf { it.approvedCount },
            voidedCount = transactionRepository.countVoidedForMerchantInWindow(merchantId, from, to),
            payableMinor = lines.sumOf { it.orgPaidMinor },
            selfPaidTotalMinor = lines.sumOf { it.selfPaidMinor },
            refundedTotalMinor = lines.sumOf { it.refundedMinor },
            lines = lines,
        )
    }

    /** 매장 로컬 달의 시작 — 화면이 기본 기간을 서버와 같은 달력으로 고르게 한다. */
    fun currentPeriod(merchantId: UUID): String = YearMonth.from(LocalDate.now(zoneOf(requireMerchant(merchantId)))).toString()

    /**
     * **플랫폼 전역 지급 현황** — 이번 달 전 매장에 나갈 금액.
     *
     * ★매장별 [settlement] 을 **그대로 재사용**한다. 여기서 따로 집계하면 운영자가 보는 총액과 매장이
     * 자기 화면에서 보는 금액이 갈릴 수 있고, 그 순간 어느 쪽이 맞는지 아무도 답할 수 없다.
     * 대가는 매장 수만큼의 질의인데, 대상이 **활동 있는 매장**으로 좁혀져 실제 사용량에 비례한다.
     *
     * 매장마다 달 경계가 다르므로(각자 타임존) UTC ±1일로 넓힌 창에서 후보를 추린 뒤 매장별로 정확히
     * 계산한다(전역 대사와 같은 형태).
     *
     * ★`@Transactional` 이 **없다**(의도적, ReconciliationService.reconcileAll 과 같은 이유):
     * 읽기 트랜잭션 하나로 최대 500개 매장을 훑으면 그동안 커넥션 1개를 계속 쥐고, 매장 하나가
     * 던지면 참여 트랜잭션이 rollback-only 로 표시돼 아래 runCatching 이 삼켜도 요청 전체가 500 이 된다.
     * 항목마다 프록시(`self`)로 짧은 트랜잭션을 열어 매장 단위 일관성은 지키고 실패는 그 매장에 가둔다.
     */
    fun platformPayables(
        period: String,
        limit: Int = MAX_MERCHANTS,
    ): PlatformPayablesView {
        val month =
            try {
                YearMonth.parse(period.trim())
            } catch (_: DateTimeParseException) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "기간 형식이 올바르지 않습니다 (YYYY-MM)")
            }
        // 타임존 최대 편차(±14시간)를 덮도록 하루씩 넓힌다 — 좁으면 경계의 매장을 놓친다.
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

        val candidates = transactionRepository.merchantIdsWithActivity(from, to)
        val scanned = candidates.take(limit)
        var failed = 0
        val lines =
            scanned
                .mapNotNull { merchantId ->
                    // 매장이 지워졌으면 건너뛴다(거래는 남아도 매장 행이 없으면 이름을 말할 수 없다).
                    // ★그 외의 실패(커넥션 획득 타임아웃 등)를 조용히 삼키면 그 매장의 지급액이 총액에서
                    // 사라지는데 화면은 "전부 집계됨"이라고 말한다 — 자금 담당이 적은 금액으로 이체를 계획한다.
                    val view =
                        try {
                            self.getObject().settlement(merchantId, month.toString())
                        } catch (ex: AuthException) {
                            // 매장 부재(NOT_FOUND)는 정상 스킵 — 거래는 남아도 매장 행이 사라진 경우다.
                            if (ex.errorCode != ErrorCode.NOT_FOUND) {
                                failed++
                                log.warn("settlement failed merchantId={} period={}", merchantId, month, ex)
                            }
                            return@mapNotNull null
                        } catch (ex: Exception) {
                            failed++
                            log.warn("settlement failed merchantId={} period={}", merchantId, month, ex)
                            return@mapNotNull null
                        }
                    // 그 매장의 로컬 달에는 활동이 없었던 경우(UTC 넓힌 창에만 걸린 경계 거래) — 줄에 싣지 않는다.
                    if (view.approvedCount == 0L && view.refundedTotalMinor == 0L) return@mapNotNull null
                    PlatformPayableLine(
                        merchantId = merchantId,
                        merchantName = view.merchantName,
                        timezone = view.timezone,
                        approvedCount = view.approvedCount,
                        payableMinor = view.payableMinor,
                        refundedMinor = view.refundedTotalMinor,
                    )
                }.sortedByDescending { it.payableMinor }

        return PlatformPayablesView(
            period = month.toString(),
            scanned = scanned.size,
            skipped = (candidates.size - scanned.size).coerceAtLeast(0),
            failed = failed,
            totalPayableMinor = lines.sumOf { it.payableMinor },
            totalRefundedMinor = lines.sumOf { it.refundedMinor },
            totalApprovedCount = lines.sumOf { it.approvedCount },
            lines = lines,
        )
    }

    private fun requireMerchant(merchantId: UUID): Merchant =
        merchantRepository.findById(merchantId).orElse(null)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "가맹점을 찾을 수 없습니다")

    /** 저장 시 검증되므로 도달 불가 방어선 — 깨진 값이어도 콘솔이 500 으로 죽지 않게 UTC 로 낙하한다. */
    private fun zoneOf(merchant: Merchant): ZoneId =
        try {
            ZoneId.of(merchant.timezone)
        } catch (ex: Exception) {
            ZoneId.of("UTC")
        }

    private companion object {
        const val DEFAULT_WINDOW_DAYS = 7L
        const val MAX_WINDOW_DAYS = 92L
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        /** 전역 지급 현황이 한 번에 훑을 매장 수 상한 — 자원고갈 방지. 넘치면 `skipped` 로 **드러낸다**. */
        const val MAX_MERCHANTS = 500
    }
}
