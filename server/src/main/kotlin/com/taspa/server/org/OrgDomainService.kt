package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.org.OrgDomain
import com.taspa.server.domain.org.OrgDomainRepository
import com.taspa.server.org.dto.OrgDomainVerifyResult
import com.taspa.server.org.dto.OrgDomainView
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * 조직 자동가입 도메인 관리 — 등록(공용 도메인 하드 차단)·DNS TXT 자가검증·삭제 +
 * 플랫폼 ADMIN 오버라이드(force-verify/unverify) + 조직별 opt-in 토글.
 *
 * 정책(사용자 승인, docs/design/email-domain-auto-membership.md 권고안 + DNS 자가검증):
 *  1. 검증된 도메인만 자동 가입 — (a) DNS TXT 자가검증, (b) 플랫폼 ADMIN 수동 승인.
 *  2. 공용 이메일 도메인은 등록 자체를 거부(조직 하이재킹 원천 차단).
 *  3. opt-in 기본 OFF — 전용 엔드포인트로만 토글(updateProfile 의 status/slug 불변 불변식과 동형).
 *  4. 자동 가입 역할 MEMBER 고정·이메일 인증 성공 트리거·멱등 — OrgAutoJoinService.
 *  5. **검증 선점**: 전역 유니크는 verified 행에만 적용된다(부분 유니크 uq_org_domain_verified).
 *     미검증 클레임은 선점 효력이 없어(org 내 중복만 차단) 소유 증명 없는 등록(스쿼팅)이 정당한
 *     소유 조직의 등록·검증을 막을 수 없고, 검증 성공 시 타 조직의 미검증 동일 도메인 클레임은
 *     제거된다(검증이 곧 탈환 — 소유를 증명한 조직이 이긴다).
 */
@Service
class OrgDomainService(
    private val orgDomainRepository: OrgDomainRepository,
    private val organizationService: OrganizationService,
    private val dnsTxtResolver: DnsTxtResolver,
    private val transactionTemplate: TransactionTemplate,
) {
    companion object {
        const val TXT_RECORD_PREFIX = "_taspa-verify."
        const val TXT_VALUE_PREFIX = "taspa-verify="

        /**
         * 공용 이메일 도메인 하드 차단 리스트 — 이 도메인을 조직이 선점하면 그 공급자의 모든 가입자가
         * 자동 소속되는 하이재킹이 되므로 등록 자체를 거부한다(verified/opt-in 과 무관한 1차 방어).
         */
        val PUBLIC_EMAIL_DOMAINS: Set<String> =
            setOf(
                "gmail.com",
                "googlemail.com",
                "naver.com",
                "daum.net",
                "hanmail.net",
                "kakao.com",
                "nate.com",
                "outlook.com",
                "hotmail.com",
                "live.com",
                "msn.com",
                "yahoo.com",
                "yahoo.co.kr",
                "icloud.com",
                "me.com",
                "mac.com",
                "proton.me",
                "protonmail.com",
                "aol.com",
                "gmx.com",
                "zoho.com",
            )

        /** 호스트네임(RFC 952/1123) 형식 — 소문자 라벨 1~63자, 최소 2라벨, TLD 는 영문 2~63자. */
        private val DOMAIN_PATTERN =
            Regex("^(?=.{4,253}\$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\$")
    }

    @Transactional(readOnly = true)
    fun list(orgId: UUID): List<OrgDomainView> {
        organizationService.requireOrg(orgId)
        return orgDomainRepository
            .findByOrgIdOrderByCreatedAtAsc(orgId)
            .map { OrgDomainView.from(it) }
    }

    @Transactional
    fun register(
        orgId: UUID,
        rawDomain: String?,
    ): OrgDomainView {
        organizationService.requireOrg(orgId)
        val domain = normalizeDomain(rawDomain)
        if (domain in PUBLIC_EMAIL_DOMAINS) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "공용 이메일 도메인은 등록할 수 없습니다: $domain")
        }
        // 검증 선점 정책: 타 조직의 **검증된** 클레임만 등록을 막는다 — 미검증 클레임은 선점 효력이
        // 없으므로(스쿼팅 무력화) 실소유 조직은 언제든 자기 클레임을 등록하고 DNS 로 소유를 증명해
        // 탈환할 수 있다. 같은 org 의 중복 클레임은 uq_org_domain_org 로 차단한다.
        if (orgDomainRepository.existsByDomainAndVerifiedTrue(domain)) {
            throw AuthException(ErrorCode.DOMAIN_ALREADY_CLAIMED)
        }
        if (orgDomainRepository.existsByOrgIdAndDomain(orgId, domain)) {
            throw AuthException(ErrorCode.DOMAIN_ALREADY_CLAIMED)
        }
        val saved =
            try {
                // saveAndFlush — org 내 UNIQUE(uq_org_domain_org) 경합(동시 등록)을 이 호출 안에서
                // 확정적으로 잡아 409 로 수렴시킨다(위 exists 검사와의 TOCTOU 창 방어).
                orgDomainRepository.saveAndFlush(
                    OrgDomain(
                        orgId = orgId,
                        domain = domain,
                        verificationToken = SecureTokenGenerator.generateToken(),
                    ),
                )
            } catch (ex: DataIntegrityViolationException) {
                throw AuthException(ErrorCode.DOMAIN_ALREADY_CLAIMED)
            }
        return OrgDomainView.from(saved)
    }

    /**
     * DNS TXT 자가검증 — `_taspa-verify.<domain>` 의 TXT 레코드 중 `taspa-verify=<token>` 정확 일치가
     * 있어야 verified 로 전이한다. 레코드 미발견·불일치·DNS 예외는 전부 400(전파 안내) — 5xx 금지.
     *
     * DNS 왕복은 **트랜잭션 밖**에서 수행한다(블로킹 조회가 커넥션 풀을 점유하지 않게): 스냅샷 조회 →
     * 무트랜잭션 DNS 조회 → 짧은 쓰기 트랜잭션에서 재조회 후 전이. 이미 검증된 행은 DNS 없이 멱등
     * 반환하며 verifiedNow=false — 컨트롤러는 실제 전이(verifiedNow=true)에만 감사를 기록한다.
     */
    fun verify(
        orgId: UUID,
        domainId: UUID,
    ): OrgDomainVerifyResult {
        val snapshot = findOrgDomain(orgId, domainId)
        if (snapshot.verified) {
            return OrgDomainVerifyResult(OrgDomainView.from(snapshot), verifiedNow = false)
        }
        val recordName = TXT_RECORD_PREFIX + snapshot.domain
        val expected = TXT_VALUE_PREFIX + snapshot.verificationToken
        val records =
            try {
                dnsTxtResolver.lookupTxt(recordName)
            } catch (ex: Exception) {
                throw AuthException(
                    ErrorCode.VALIDATION_ERROR,
                    "DNS TXT 레코드를 찾을 수 없습니다($recordName). 레코드 게시 후 전파까지 수 분 걸릴 수 있습니다 — 잠시 후 다시 시도하세요.",
                )
            }
        if (records.none { it.trim() == expected }) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "DNS TXT 레코드가 일치하지 않습니다($recordName). 값이 정확히 \"$expected\" 인지 확인하고, 전파 후 다시 시도하세요.",
            )
        }
        return transactionTemplate.execute {
            // DNS 대기 중 삭제·변경에 대비해 쓰기 트랜잭션 안에서 재조회한다(스냅샷은 detached).
            val orgDomain = findOrgDomain(orgId, domainId)
            if (orgDomain.verified) {
                OrgDomainVerifyResult(OrgDomainView.from(orgDomain), verifiedNow = false)
            } else {
                OrgDomainVerifyResult(markVerified(orgDomain, OrgDomain.METHOD_DNS_TXT), verifiedNow = true)
            }
        }!!
    }

    /** 플랫폼 ADMIN 수동 승인 오버라이드 — DNS 없이 verified 로 전이한다(오프라인 소유 확인 전제). */
    @Transactional
    fun forceVerify(
        orgId: UUID,
        domainId: UUID,
    ): OrgDomainView = markVerified(findOrgDomain(orgId, domainId), OrgDomain.METHOD_MANUAL)

    /** 플랫폼 ADMIN 검증 철회 — 이후 자동 가입 판정에서 즉시 제외된다(기존 멤버십은 불변). */
    @Transactional
    fun unverify(
        orgId: UUID,
        domainId: UUID,
    ): OrgDomainView {
        val orgDomain = findOrgDomain(orgId, domainId)
        orgDomain.verified = false
        orgDomain.verifiedAt = null
        orgDomain.verifiedMethod = null
        orgDomain.reverifyFailures = 0
        return OrgDomainView.from(orgDomainRepository.save(orgDomain))
    }

    /** 삭제한 도메인 문자열을 반환한다(감사 detail 용). */
    @Transactional
    fun delete(
        orgId: UUID,
        domainId: UUID,
    ): String {
        val orgDomain = findOrgDomain(orgId, domainId)
        orgDomainRepository.delete(orgDomain)
        return orgDomain.domain
    }

    /**
     * 조직별 opt-in 토글. updateProfile 로 넣지 않는 이유: 프로필 저장(name·timezone)에 자동가입
     * 정책 변경이 묻어가면 감사·step-up 의미가 흐려진다 — 전용 엔드포인트로만 바꾼다.
     */
    @Transactional
    fun setAutoJoinEnabled(
        orgId: UUID,
        enabled: Boolean,
    ): Boolean {
        val org = organizationService.requireOrg(orgId)
        org.autoJoinEnabled = enabled
        return enabled
    }

    /**
     * 검증 전이 확정 + 검증 선점(탈환): 타 org 의 동일 도메인 **미검증** 클레임을 제거한 뒤 verified 로
     * 저장한다. 동시 검증 경합은 부분 유니크(uq_org_domain_verified)가 확정한다 — 패자는 409 수렴.
     */
    private fun markVerified(
        orgDomain: OrgDomain,
        method: String,
    ): OrgDomainView {
        orgDomainRepository.deleteByDomainAndVerifiedFalseAndOrgIdNot(orgDomain.domain, orgDomain.orgId)
        orgDomain.verified = true
        orgDomain.verifiedMethod = method
        orgDomain.verifiedAt = Instant.now()
        orgDomain.reverifyFailures = 0
        return try {
            OrgDomainView.from(orgDomainRepository.saveAndFlush(orgDomain))
        } catch (ex: DataIntegrityViolationException) {
            // 다른 조직이 먼저 검증을 확정(부분 유니크 위반) — 검증 선점 정책의 결정적 패배.
            throw AuthException(ErrorCode.DOMAIN_ALREADY_CLAIMED)
        }
    }

    private fun findOrgDomain(
        orgId: UUID,
        domainId: UUID,
    ): OrgDomain =
        orgDomainRepository.findByIdAndOrgId(domainId, orgId)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "도메인을 찾을 수 없습니다")

    private fun normalizeDomain(raw: String?): String {
        val domain =
            raw?.trim()?.trimEnd('.')?.lowercase()
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "도메인을 입력하세요")
        if (domain.isEmpty()) throw AuthException(ErrorCode.VALIDATION_ERROR, "도메인을 입력하세요")
        if (!DOMAIN_PATTERN.matches(domain)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "유효한 도메인 형식이 아닙니다: $domain")
        }
        return domain
    }
}
