package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Site
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.org.dto.SiteView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 사업장(구내식당) — org 스코프. org 내 이름 유일(앱 선검증 + DB 유니크), timezone 은
 * OrganizationService.requireValidTimezone 재사용(IANA/Postgres 존 검증 — 집계 쿼리 500 방지).
 * rename/update/delete 는 findByIdAndOrgId 로 org 소속일 때만 동작(타 org 는 404 — 격리).
 * delete 시 배정 멤버는 site_id SET NULL(DB FK)로 자동 해제된다.
 */
@Service
class SiteService(
    private val siteRepository: SiteRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val organizationService: OrganizationService,
) {
    @Transactional(readOnly = true)
    fun list(orgId: UUID): List<SiteView> {
        organizationService.requireOrg(orgId) // 조회는 존재만 검증(정지 조직도 조회 가능)
        val counts =
            membershipRepository
                .countBySiteGrouped(orgId)
                .associate { (it[0] as UUID) to (it[1] as Long) }
        return siteRepository
            .findByOrgId(orgId)
            .map { SiteView.from(it, counts[it.id!!] ?: 0L) }
            .sortedBy { it.name.lowercase() }
    }

    @Transactional
    fun create(
        orgId: UUID,
        name: String,
        address: String?,
        timezone: String?,
    ): SiteView {
        organizationService.requireActiveOrg(orgId)
        val normalized = organizationService.normalizeStructureName(name)
        if (siteRepository.existsByOrgIdAndName(orgId, normalized)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "동일한 이름의 사업장이 이미 있습니다")
        }
        val tz = organizationService.requireValidTimezone(timezone) ?: "UTC"
        val saved =
            siteRepository.save(
                Site(orgId = orgId, name = normalized, address = normalizeAddress(address), timezone = tz),
            )
        return SiteView.from(saved, 0)
    }

    @Transactional
    fun update(
        orgId: UUID,
        siteId: UUID,
        name: String?,
        address: String?,
        timezone: String?,
    ): SiteView {
        val site =
            siteRepository.findByIdAndOrgId(siteId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "사업장을 찾을 수 없습니다")
        name?.let {
            val normalized = organizationService.normalizeStructureName(it)
            if (normalized != site.name && siteRepository.existsByOrgIdAndName(orgId, normalized)) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "동일한 이름의 사업장이 이미 있습니다")
            }
            site.name = normalized
        }
        // address 는 빈 문자열로 명시적 해제 가능(null 은 미변경).
        address?.let { site.address = normalizeAddress(it) }
        organizationService.requireValidTimezone(timezone)?.let { site.timezone = it }
        val saved = siteRepository.save(site)
        return SiteView.from(saved, membershipRepository.countBySiteId(saved.id!!))
    }

    /**
     * 주소 정규화·검증 — trim 후 빈 문자열은 null(해제), VARCHAR(255) 상한 선검증. 구조 이름(normalizeStructureName,
     * 120자)·조직명(200자)과 동일하게 앱 계층에서 컬럼 상한을 확인해 과길이 입력에 명확한 400(VALIDATION_ERROR)을
     * 준다(선검증 없으면 Postgres 'value too long' → 오해성 409/500 으로 샌다).
     */
    private fun normalizeAddress(value: String?): String? {
        val address = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (address.length > 255) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "주소는 255자 이하여야 합니다")
        }
        return address
    }

    @Transactional
    fun delete(
        orgId: UUID,
        siteId: UUID,
    ) {
        val site =
            siteRepository.findByIdAndOrgId(siteId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "사업장을 찾을 수 없습니다")
        // 배정 멤버는 site_id SET NULL(DB FK)로 자동 해제된다(자유 삭제).
        siteRepository.delete(site)
    }
}
