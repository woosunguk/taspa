package com.taspa.server.org

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 조직 초대 설정. @ConfigurationPropertiesScan 으로 자동 등록된다(TaspaServerApplication).
 */
@ConfigurationProperties(prefix = "taspa.org-invitation")
data class OrgInvitationProperties(
    /** 초대 토큰 만료(일). 기본 7일. */
    val expiryDays: Long = 7,
    /** org·시간당 신규(distinct) 초대 상한(남용/행 무한 증식 방지). 기본 20건/시간. */
    val maxPerHour: Long = 20,
    /**
     * 동일 (org,email) 재발송 최소 간격(초) — 단일 주소 이메일 폭탄 방지. 기본 60초.
     * 재초대는 PENDING 1건을 재사용하므로 시간당 상한(행 수)만으로는 같은 주소 반복 발송을 막지 못한다.
     * 마지막 발송 시각(createdAt)이 이 간격 안이면 재발송을 거부한다(발송 빈도 상한).
     */
    val resendCooldownSeconds: Long = 60,
    /** 수락 링크 base URL. `{base-url}/orgs/invite/accept?token=...` 로 조립한다. */
    val baseUrl: String = "http://localhost:9100",
)
