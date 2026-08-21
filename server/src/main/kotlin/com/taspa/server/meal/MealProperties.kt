package com.taspa.server.meal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 식권 QR 설정. @ConfigurationPropertiesScan 으로 자동 등록된다(TaspaServerApplication).
 */
@ConfigurationProperties(prefix = "taspa.meal")
data class MealProperties(
    /** QR 불투명 핸들 TTL. 짧을수록 재사용 창이 좁다(설계 기본 60초 — 화면 갱신 주기와 일치). */
    val qrTtl: Duration = Duration.ofSeconds(60),
    /**
     * QR 발급 쿨다운 — 직전 발급 후 이 시간 내 재요청은 429(QR_RATE_LIMITED)로 거절한다(남용 방지).
     * 원문 토큰은 해시만 저장돼 재반환이 불가능하므로 "재사용 반환" 대신 쿨다운 거절을 채택했다.
     */
    val qrIssueCooldown: Duration = Duration.ofSeconds(10),
    /** 조직이 스스로 설정할 수 있는 식대 정책의 절대 상한. */
    val policyCeiling: PolicyCeiling = PolicyCeiling(),
) {
    /**
     * 정책 편집의 **배포 단위 상한**. 조직관리자가 자기 조직 한도를 정하는 것은 정상 권한이지만,
     * 오타 한 번(12000 → 1200000)이 곧 회사 지출이 되므로 자릿수 사고를 여기서 끊는다.
     *
     * 이 값은 "합리적 상한"이 아니라 **명백한 오류의 벽**이다 — 실제 정책은 그보다 훨씬 낮은 곳에서
     * 조직이 정한다. 특별한 조직이 있으면 배포 설정(`taspa.meal.policy-ceiling.*`)으로 올린다.
     */
    data class PolicyCeiling(
        val perMealLimitMinor: Long = 1_000_000,
        val dailyMealCount: Int = 10,
        val monthlyCapMinor: Long = 50_000_000,
    )
}
