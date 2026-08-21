package com.taspa.server.maintenance

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.org.OrgDomain
import com.taspa.server.domain.org.OrgDomainRepository
import com.taspa.server.org.DnsTxtResolver
import com.taspa.server.org.OrgDomainService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 검증 도메인 주기 재검증 잡 — DNS TXT 검증은 검증 시점 1회의 스냅샷이므로, 도메인이 만료되어
 * 제3자에게 재등록(소유권 이전)된 뒤에도 verified 가 영구 유지되면 새 소유자 측 가입자들이 옛 조직에
 * 계속 자동 가입되는 문제가 생긴다. 이 잡이 TXT 잔존을 주기 재확인해 그 창을 닫는다(Google Workspace
 * 등의 주기 재검증과 같은 근거).
 *
 * - 대상은 **dns-txt 로 검증된 행만** — manual(플랫폼 ADMIN force-verify)은 오프라인 소유 확인이
 *   근거라 TXT 부재가 정상이므로 재검증하지 않는다(잘못된 철회 방지, fail-safe).
 * - 일시적 DNS 장애 1회로 철회되지 않게 **연속 실패 카운터**(reverify_failures)를 쓴다: 실패마다 +1,
 *   성공 시 0 리셋, threshold(기본 3 — 기본 크론으론 3일 연속 실패) 도달 시 verified 철회.
 * - 철회는 unverify 와 동일 효과(자동 가입 판정 즉시 제외, 기존 멤버십 불변) + ORG_DOMAIN_UNVERIFIED
 *   감사(method=dns-reverify-failed). verification_token 은 유지되므로 조직은 TXT 재게시 후 verify 로
 *   즉시 복구할 수 있다.
 * - DNS 왕복은 트랜잭션 밖에서 수행한다 — 행별 저장만 짧은 트랜잭션(커넥션 풀 비점유,
 *   OrgDomainService.verify 와 동일 원칙).
 */
@Component
class OrgDomainReverifyJob(
    private val orgDomainRepository: OrgDomainRepository,
    private val dnsTxtResolver: DnsTxtResolver,
    private val auditEventService: AuditEventService,
    @Value("\${taspa.org-domain.reverify-failure-threshold:3}")
    private val failureThreshold: Int,
) {
    private val log = LoggerFactory.getLogger(OrgDomainReverifyJob::class.java)

    @Scheduled(cron = "\${taspa.org-domain.reverify-cron:0 30 4 * * *}")
    fun reverify() {
        val targets = orgDomainRepository.findByVerifiedTrueAndVerifiedMethod(OrgDomain.METHOD_DNS_TXT)
        if (targets.isEmpty()) return
        val today = LocalDate.now(ZoneOffset.UTC)
        var revoked = 0
        for (orgDomain in targets) {
            if (txtStillPublished(orgDomain)) {
                if (orgDomain.reverifyFailures != 0 || orgDomain.lastReverifyFailureOn != null) {
                    orgDomain.reverifyFailures = 0
                    orgDomain.lastReverifyFailureOn = null
                    orgDomainRepository.save(orgDomain)
                }
                continue
            }
            // 같은 날 두 번째 이후 실행은 카운터를 올리지 않는다 — 다중 인스턴스/수동 재실행에서
            // "연속 실패 일수" 임계가 하루 만에 소진되어 정상 도메인이 조기 철회되는 것을 막는다.
            if (orgDomain.lastReverifyFailureOn == today) continue
            orgDomain.lastReverifyFailureOn = today
            orgDomain.reverifyFailures += 1
            if (orgDomain.reverifyFailures >= failureThreshold) {
                orgDomain.verified = false
                orgDomain.verifiedAt = null
                orgDomain.verifiedMethod = null
                orgDomain.reverifyFailures = 0
                orgDomain.lastReverifyFailureOn = null
                orgDomainRepository.save(orgDomain)
                revoked += 1
                auditEventService.record(
                    "ORG_DOMAIN_UNVERIFIED",
                    null,
                    orgDomain.orgId,
                    mapOf(
                        "orgId" to orgDomain.orgId.toString(),
                        "domain" to orgDomain.domain,
                        "method" to "dns-reverify-failed",
                        "consecutiveFailures" to failureThreshold,
                    ),
                )
                log.warn(
                    "org domain reverify revoked: domain={}, orgId={} (TXT missing {} consecutive checks)",
                    orgDomain.domain,
                    orgDomain.orgId,
                    failureThreshold,
                )
            } else {
                orgDomainRepository.save(orgDomain)
            }
        }
        log.info("org domain reverify: checked={}, revoked={}", targets.size, revoked)
    }

    private fun txtStillPublished(orgDomain: OrgDomain): Boolean {
        val expected = OrgDomainService.TXT_VALUE_PREFIX + orgDomain.verificationToken
        return try {
            dnsTxtResolver
                .lookupTxt(OrgDomainService.TXT_RECORD_PREFIX + orgDomain.domain)
                .any { it.trim() == expected }
        } catch (ex: Exception) {
            // NXDOMAIN·타임아웃 모두 실패 1회로 집계 — 철회는 연속 threshold 회에서만 일어난다.
            false
        }
    }
}
