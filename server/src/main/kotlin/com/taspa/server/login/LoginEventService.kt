package com.taspa.server.login

import com.taspa.server.common.http.RequestClientInfo
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.login.LoginEvent
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.user.User
import com.taspa.server.mail.MailService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 로그인 이벤트 기록 + 새 로그인(신규 기기) 알림(B-2).
 *
 * 신규 기기 판정: 최근 30일 login_events 에 같은 (ip, uaLabel) 이 없고, 이 요청이 유효한
 * 신뢰 기기 쿠키로 검증된 것도 아니면 알림 메일을 보낸다. 이벤트가 항상 기록되므로
 * 같은 (ip, uaLabel) 재로그인은 자연히 24시간(그리고 30일) 내 재발송이 억제된다.
 */
@Service
class LoginEventService(
    private val loginEventRepository: LoginEventRepository,
    private val trustedDeviceService: TrustedDeviceService,
    private val mailService: MailService,
) {
    companion object {
        private val NEW_DEVICE_WINDOW: Duration = Duration.ofDays(30)
    }

    private val log = LoggerFactory.getLogger(LoginEventService::class.java)

    @Transactional
    fun recordLogin(
        request: HttpServletRequest,
        user: User,
        method: String,
    ) {
        val ip = RequestClientInfo.ip(request)
        val uaLabel = RequestClientInfo.uaLabel(request)
        val occurredAt = Instant.now()

        val knownDevice =
            loginEventRepository.existsByUserIdAndIpAndUaLabelAndCreatedAtAfter(
                user.id!!,
                ip,
                uaLabel,
                occurredAt.minus(NEW_DEVICE_WINDOW),
            )
        loginEventRepository.save(
            LoginEvent(userId = user.id, ip = ip, uaLabel = uaLabel, method = method, createdAt = occurredAt),
        )

        if (knownDevice || trustedDeviceService.wasValidatedInRequest(request, user.id)) {
            return
        }
        // 알림 실패가 로그인 자체를 실패시키지 않도록 방어한다.
        try {
            mailService.sendNewLoginNotice(user.email, uaLabel, ip, occurredAt)
        } catch (ex: Exception) {
            log.warn("new-login notice mail failed for userId={}", user.id, ex)
        }
    }
}
