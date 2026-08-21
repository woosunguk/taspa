package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 조직별 자동가입 이메일 도메인(V24). 도메인은 소문자 정규화 저장. 충돌 정책은 **검증 선점**:
 * 전역 유니크는 verified 행에만 적용되고(부분 유니크 uq_org_domain_verified), 미검증 클레임은
 * 선점 효력이 없다(org 내 중복만 uq_org_domain_org 로 차단) — 검증 성공 시 타 조직의 미검증 동일
 * 도메인 클레임은 제거된다. verified=false 인 행은 자동 가입 판정에 절대 쓰이지 않는다.
 * 검증 방법(verifiedMethod): DNS TXT(`_taspa-verify.<domain>` 에 `taspa-verify=<token>`) 자가검증
 * 또는 플랫폼 ADMIN 수동 승인(force-verify). dns-txt 검증은 주기 재검증 잡(OrgDomainReverifyJob)이
 * TXT 잔존을 재확인한다 — 도메인 소유권 이전(만료·재등록) 후 자동 가입이 계속 동작하는 것을 막는다.
 */
@Entity
@Table(name = "org_domains")
class OrgDomain(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "domain", nullable = false, length = 255)
    val domain: String,
    @Column(name = "verified", nullable = false)
    var verified: Boolean = false,
    @Column(name = "verification_token", nullable = false, length = 64)
    val verificationToken: String,
    @Column(name = "verified_method", length = 16)
    var verifiedMethod: String? = null,
    @Column(name = "reverify_failures", nullable = false)
    var reverifyFailures: Int = 0,
    /**
     * 마지막으로 재검증 실패를 집계한 날짜(UTC). 같은 날 재실행에서는 카운터를 올리지 않아
     * reverifyFailures 가 "연속 실패 **일수**"를 뜻하게 한다 — 다중 인스턴스·수동 재실행에도 임계가
     * 하루 만에 소진되지 않는다.
     */
    @Column(name = "last_reverify_failure_on")
    var lastReverifyFailureOn: LocalDate? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "verified_at")
    var verifiedAt: Instant? = null,
) {
    companion object {
        /** DNS TXT 자가검증 — 주기 재검증 대상. */
        const val METHOD_DNS_TXT = "dns-txt"

        /** 플랫폼 ADMIN 수동 승인(오프라인 소유 확인) — TXT 부재가 정상이므로 재검증 면제. */
        const val METHOD_MANUAL = "manual"
    }
}
