package com.taspa.server.maintenance

import com.taspa.server.token.JwkStorageService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 서명 키 수명주기 잡 — 일 1회.
 * rotation-period 가 지난 ACTIVE 를 회전(→RETIRED + 새 ACTIVE)하고,
 * retirement-grace 를 넘긴 RETIRED 를 삭제한다. 중복 실행은 rotateIfDue 의 FOR UPDATE 가 막는다.
 */
@Component
class JwkRotationJob(
    private val jwkStorageService: JwkStorageService,
) {
    private val log = LoggerFactory.getLogger(JwkRotationJob::class.java)

    @Scheduled(cron = "\${taspa.jwk.rotation-cron:0 30 4 * * *}")
    fun rotateAndPurge() {
        val rotated = jwkStorageService.rotateIfDue()
        val purged = jwkStorageService.purgeExpired()
        log.info("jwk rotation job: rotated={}, purgedRetiredKeys={}", rotated, purged)
    }
}
