package com.taspa.server.domain.org

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrgDomainRepository : JpaRepository<OrgDomain, UUID> {
    /** 자동 가입 판정용 전역 조회 — verified 행은 부분 유니크(uq_org_domain_verified)라 최대 1행. */
    fun findByDomainAndVerifiedTrue(domain: String): OrgDomain?

    fun existsByDomainAndVerifiedTrue(domain: String): Boolean

    fun existsByOrgIdAndDomain(
        orgId: UUID,
        domain: String,
    ): Boolean

    /**
     * 단일 행 전제 조회 — 미검증 클레임은 여러 org 에 공존할 수 있으므로(검증 선점 정책) 단일 행이
     * 보장되는 곳(테스트 픽스처 등)에서만 쓸 것. 판정 로직은 findByDomainAndVerifiedTrue 를 쓴다.
     */
    fun findByDomain(domain: String): OrgDomain?

    /** 검증 선점 확정(탈환) — 검증 성공 시 타 org 의 미검증 동일 도메인 클레임을 제거한다. */
    fun deleteByDomainAndVerifiedFalseAndOrgIdNot(
        domain: String,
        orgId: UUID,
    ): Long

    /** 주기 재검증 대상 — dns-txt 로 검증된 행만(manual 은 TXT 부재가 정상이라 제외). */
    fun findByVerifiedTrueAndVerifiedMethod(verifiedMethod: String): List<OrgDomain>

    fun findByOrgIdOrderByCreatedAtAsc(orgId: UUID): List<OrgDomain>

    /** org 격리 조회 — 타 org 의 도메인 행은 절대 잡히지 않는다(404 계약의 근거). */
    fun findByIdAndOrgId(
        id: UUID,
        orgId: UUID,
    ): OrgDomain?
}
