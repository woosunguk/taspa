package com.taspa.server.org.dto

import com.taspa.server.domain.org.OrgDomain
import com.taspa.server.org.OrgDomainService
import java.time.Instant
import java.util.UUID

data class OrgDomainRegisterRequest(
    val domain: String? = null,
)

data class AutoJoinToggleRequest(
    val enabled: Boolean? = null,
)

/**
 * 도메인 행 뷰 — TXT 안내(record name·value)를 함께 내려 콘솔이 "무엇을 어디에 게시해야 하는지"를
 * 그대로 보여줄 수 있게 한다. verificationToken 은 자기 org(또는 플랫폼 ADMIN) 조회에서만 노출된다
 * (컨트롤러 인가가 org 격리를 강제 — 타 org 토큰은 절대 내려가지 않는다).
 */
data class OrgDomainView(
    val id: UUID,
    val domain: String,
    val verified: Boolean,
    val verificationToken: String,
    val txtRecordName: String,
    val txtRecordValue: String,
    val createdAt: Instant,
    val verifiedAt: Instant?,
) {
    companion object {
        fun from(entity: OrgDomain): OrgDomainView =
            OrgDomainView(
                id = entity.id!!,
                domain = entity.domain,
                verified = entity.verified,
                verificationToken = entity.verificationToken,
                txtRecordName = OrgDomainService.TXT_RECORD_PREFIX + entity.domain,
                txtRecordValue = OrgDomainService.TXT_VALUE_PREFIX + entity.verificationToken,
                createdAt = entity.createdAt,
                verifiedAt = entity.verifiedAt,
            )
    }
}

/**
 * verify 결과 — verifiedNow 는 **이번 호출로 실제 전이가 일어났는지**. 이미 검증된 행의 재호출은
 * DNS 확인 없이 멱등 반환(verifiedNow=false)되므로, 컨트롤러는 verifiedNow=true 일 때만
 * ORG_DOMAIN_VERIFIED 감사를 기록한다(감사 로그의 검증 시각·횟수 오염 방지).
 */
data class OrgDomainVerifyResult(
    val view: OrgDomainView,
    val verifiedNow: Boolean,
)

/** 도메인 목록 + opt-in 상태를 한 번에 — 콘솔 설정 섹션이 단일 GET 으로 렌더할 수 있게 한다. */
data class OrgDomainSettingsView(
    val autoJoinEnabled: Boolean,
    val domains: List<OrgDomainView>,
)
