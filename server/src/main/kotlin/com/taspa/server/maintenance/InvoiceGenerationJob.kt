package com.taspa.server.maintenance

import com.taspa.server.billing.InvoiceService
import com.taspa.server.domain.billing.InvoiceRepository
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.mail.MailService
import com.taspa.server.meal.MealPolicyCalculus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 월 청구서 자동 생성 — **누락 청구를 막는 유일한 장치**.
 *
 * 그전까지 청구서는 조직관리자가 콘솔에서 직접 누를 때만 만들어졌다. 즉 아무도 누르지 않으면 그 달의
 * 청구는 **조용히 없던 일이 된다** — 회사가 쓴 식대를 우리가 청구하지 않고, 아무도 그 사실을 모른다
 * (전역 대사도 청구서 유무는 보지 않는다). 매출 누락은 알람이 울리지 않는 종류의 사고다.
 *
 * 설계 결정 셋:
 *
 * 1. ★**DRAFT 까지만.** 확정(finalize)은 그대로 사람의 일이다 — 확정은 청구서를 불변으로 만들고 조직에
 *    보내는 문서를 확정하는 행위라, 자동화하면 잘못된 숫자가 되돌릴 수 없게 굳는다.
 * 2. ★**이미 있으면 건드리지 않는다.** 조직관리자가 손으로 재생성한 초안을 잡이 덮으면, 그 사람이
 *    확인한 숫자가 다음 날 새벽에 조용히 바뀐다. "없을 때만 만든다"는 규칙 하나로 멱등성과 이 안전성이
 *    동시에 성립한다(매일 돌아도 첫날 이후엔 아무 일도 하지 않는다).
 * 3. **org 로컬 달력으로 직전 달**을 대상으로 한다. 조직마다 타임존이 다르므로 "지난달"의 경계가 다르고,
 *    UTC 로 일괄 계산하면 한쪽 끝의 조직이 하루치를 잃거나 얻는다(청구서 창이 org 타임존 앵커인 것과
 *    같은 이유). 매일 돌면서 각 조직의 로컬 달이 넘어간 뒤에 자연히 생성된다.
 *
 * 활동이 없는 조직은 건너뛴다 — 0원짜리 청구서는 정보가 아니라 소음이고, 목록에서 진짜 청구서를 가린다.
 * SUSPENDED 조직도 제외한다(정지된 조직에 새 문서를 만들지 않는다. 정지 전 달의 청구서가 필요하면
 * 사람이 만든다).
 *
 * 한 조직의 실패가 나머지를 막지 않는다 — 조직별로 예외를 삼키고 로그만 남긴다. 그러지 않으면 깨진
 * 조직 하나 때문에 **그 뒤 모든 조직의 청구가 누락**되고, 그게 정확히 이 잡이 막으려던 사고다.
 */
@Component
@ConditionalOnProperty(name = ["taspa.billing.auto-generate-enabled"], havingValue = "true", matchIfMissing = true)
class InvoiceGenerationJob(
    private val organizationRepository: OrganizationRepository,
    private val invoiceRepository: InvoiceRepository,
    private val invoiceService: InvoiceService,
    private val membershipRepository: OrgMembershipRepository,
    private val userRepository: UserRepository,
    private val mailService: MailService,
    @Value("\${taspa.billing.auto-generate-grace-days:2}")
    private val graceDays: Long,
    // 콘솔 링크의 base URL. 초대 메일과 **같은 키**를 쓴다 — 새 키를 만들면 배포마다 두 곳을 맞춰야 하고,
    // 한쪽만 고친 순간 메일 링크가 조용히 잘못된 호스트를 가리킨다.
    @Value("\${taspa.org-invitation.base-url:http://localhost:9100}")
    private val consoleBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(InvoiceGenerationJob::class.java)

    @Scheduled(cron = "\${taspa.billing.auto-generate-cron:0 20 3 * * *}")
    fun generateMonthlyDrafts() {
        val organizations =
            organizationRepository
                .findAll()
                .filter { it.status == OrgStatus.ACTIVE.name }
        if (organizations.isEmpty()) return

        var created = 0
        var skipped = 0
        for (org in organizations) {
            val orgId = org.id ?: continue
            try {
                val zone = MealPolicyCalculus.zoneOf(org.timezone)
                val today = LocalDate.now(zone)
                val target = YearMonth.from(today).minusMonths(1)

                // ★유예 기간: 달이 바뀌자마자 만들지 않는다. 경계 직전 거래의 POS 재전송·취소가
                // 며칠 늦게 도착할 수 있고, 그때 이미 만들어진 초안은 옛 숫자로 남는다(규칙 2 때문에
                // 잡이 다시 손대지 않는다). 며칠 기다린 뒤 만드는 편이 첫 초안의 정확도를 높인다.
                if (today.dayOfMonth <= graceDays) continue

                if (invoiceRepository.findByOrgIdAndPeriod(orgId, target.toString()) != null) {
                    skipped++
                    continue
                }
                // ★활동 확인이 **생성보다 먼저**다. 만들었다가 지우는 방식이면 존재하지 않는 청구서의
                // INVOICE_GENERATED 가 조직 활동로그에 남아, 관리자가 사라진 청구서를 찾게 된다.
                if (!invoiceService.hasBillableActivity(orgId, target.toString())) {
                    skipped++
                    continue
                }

                // actorId=null → 감사 로그가 "시스템이 만들었다"를 그대로 말한다.
                val invoice = invoiceService.generate(orgId, target.toString(), null)
                created++
                log.info(
                    "invoice auto-generated orgId={} period={} subtotal={} txnCount={}",
                    orgId,
                    target,
                    invoice.subtotalMinor,
                    invoice.txnCount,
                )
                notifyOrgAdmins(orgId, org.name, invoice.period, invoice.subtotalMinor, invoice.txnCount)
            } catch (ex: DataIntegrityViolationException) {
                /*
                 * ★다중 인스턴스 경합의 **정상 종료**다 — 실패가 아니다.
                 *
                 * 위 "이미 있으면 건너뛴다" 가드는 잠금 없는 check-then-act 라, 두 인스턴스가 같은 새벽에
                 * 동시에 통과할 수 있다. 그다음은 DB 가 정리한다: `UNIQUE(org_id, period)` 때문에 한쪽만
                 * INSERT 에 성공하고 나머지는 여기로 떨어진다(그래서 **중복 청구서도 중복 메일도 없다**).
                 * 이걸 ERROR 로 남기면 매달 1일 새벽마다 허위 경보가 뜨고, 경보 피로가 진짜 실패를 가린다.
                 * 정합성은 DB 제약이 지키고 로그는 그 사실을 조용히 기록한다.
                 */
                skipped++
                log.info("invoice auto-generation skipped (another instance won) orgId={}", orgId)
            } catch (ex: Exception) {
                // 한 조직의 실패가 나머지 조직의 청구를 막지 않는다 — 이 잡의 존재 이유가 누락 방지다.
                log.error("invoice auto-generation failed orgId={}", orgId, ex)
            }
        }
        if (created > 0 || skipped > 0) {
            log.info("invoice auto-generation done created={} skipped={}", created, skipped)
        }
    }

    /**
     * 조직관리자에게 "초안이 준비됐다"를 알린다.
     *
     * ★이 알림이 없으면 자동 생성은 **아무 일도 하지 않은 것과 같다**: 청구서는 만들어졌지만 그 사실을
     * 아는 사람이 없어, 관리자는 여전히 스스로 기억해 로그인해야 한다.
     *
     * 발송 실패는 **전파하지 않는다** — 메일 서버 장애로 청구서 생성이 롤백되면, 알림이 없어서 생기는
     * 문제를 고치려다 청구 자체를 잃는다(로그인 알림이 로그인을 깨뜨리지 않는 것과 같은 판단).
     */
    private fun notifyOrgAdmins(
        orgId: UUID,
        orgName: String,
        period: String,
        subtotal: Long,
        txnCount: Int,
    ) {
        try {
            val adminUserIds =
                membershipRepository
                    .findByOrgId(orgId)
                    .filter { it.statusEnum() == MembershipStatus.ACTIVE && it.role == OrgRole.ORG_ADMIN.name }
                    .map { it.userId }
            if (adminUserIds.isEmpty()) {
                // 조직관리자가 없는 조직은 플랫폼이 대신 확정한다 — 메일 보낼 곳이 없다는 사실만 남긴다.
                log.warn("invoice draft has no org admin to notify orgId={} period={}", orgId, period)
                return
            }
            val consoleUrl = "${consoleBaseUrl.trimEnd('/')}/console/$orgId/invoices"
            userRepository
                .findAllById(adminUserIds)
                .filter { it.status == UserStatus.ACTIVE.name }
                .forEach { admin ->
                    // 한 사람에게 실패해도 나머지는 받는다.
                    runCatching {
                        mailService.sendInvoiceDraftReady(
                            admin.email,
                            orgName,
                            period,
                            subtotal,
                            txnCount,
                            consoleUrl,
                        )
                    }.onFailure { log.warn("invoice draft notice failed orgId={} to={}", orgId, admin.email, it) }
                }
        } catch (ex: Exception) {
            log.warn("invoice draft notification failed orgId={} period={}", orgId, period, ex)
        }
    }
}
