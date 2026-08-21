package com.taspa.server.device

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.http.RequestClientInfo
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.device.dto.TrustedDeviceResponse
import com.taspa.server.domain.device.TrustedDevice
import com.taspa.server.domain.device.TrustedDeviceRepository
import com.taspa.server.domain.user.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 신뢰 기기(MFA 30일 스킵 — OWASP 권장 설계).
 *
 * - 발급: 256-bit 토큰 → SHA-256 해시 저장, 원본은 쿠키(taspa_td)로만 전달.
 * - 검증: 해시 조회 + 만료 + 사용자 일치. 성공 시 토큰을 **회전**(새 토큰 재발급, 같은 행 갱신)하되
 *   만료 시각은 발급 시점 기준 고정(sliding 연장 금지).
 * - 같은 요청 안에서 재검증이 필요할 수 있으므로(회전 후에는 요청의 쿠키 값이 구토큰이라 실패)
 *   검증 성공을 요청 속성으로 캐시한다.
 */
@Service
class TrustedDeviceService(
    private val trustedDeviceRepository: TrustedDeviceRepository,
    private val properties: TrustedDeviceProperties,
    private val auditEventService: AuditEventService,
) {
    companion object {
        const val COOKIE_NAME = "taspa_td"

        /** 이 요청에서 신뢰 기기 검증이 이미 성공했음을 표시하는 요청 속성 (userId 저장). */
        private const val VALIDATED_ATTR = "TASPA_TRUSTED_DEVICE_VALIDATED"
    }

    /** MFA 통과 시 "이 기기에서 30일 동안 묻지 않음" 선택에 의한 발급. */
    @Transactional
    fun issue(
        userId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val rawToken = SecureTokenGenerator.generateToken()
        val expiresAt = Instant.now().plus(Duration.ofDays(properties.expiryDays))
        trustedDeviceRepository.save(
            TrustedDevice(
                userId = userId,
                tokenHash = SecureTokenGenerator.hashToken(rawToken),
                uaLabel = RequestClientInfo.uaLabel(request),
                expiresAt = expiresAt,
            ),
        )
        writeCookie(request, response, rawToken, Duration.ofDays(properties.expiryDays))
        auditEventService.record("TRUSTED_DEVICE_ISSUED", userId, mapOf("uaLabel" to RequestClientInfo.uaLabel(request)))
        // 주의: 검증 캐시(VALIDATED_ATTR)는 심지 않는다 — 발급 시점의 로그인은 여전히 "새 기기"이므로
        // 새 로그인 알림 판정에서 신뢰 기기로 간주되면 안 된다.
    }

    /**
     * 쿠키의 토큰이 이 사용자의 유효한 신뢰 기기인지 검증하고, 성공 시 토큰을 회전한다.
     * MFA 게이트 판정(LoginFlowSupport.requiredGate)에서 호출된다.
     */
    @Transactional
    fun validateAndRotate(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: User,
    ): Boolean {
        if (request.getAttribute(VALIDATED_ATTR) == user.id) {
            return true
        }
        val rawToken = cookieValue(request) ?: return false
        val device = trustedDeviceRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken)) ?: return false
        if (device.userId != user.id || device.isExpired()) {
            return false
        }

        val newRawToken = SecureTokenGenerator.generateToken()
        device.tokenHash = SecureTokenGenerator.hashToken(newRawToken)
        device.lastUsedAt = Instant.now()
        trustedDeviceRepository.save(device)

        val remaining = Duration.between(Instant.now(), device.expiresAt)
        writeCookie(request, response, newRawToken, if (remaining.isNegative) Duration.ZERO else remaining)
        request.setAttribute(VALIDATED_ATTR, user.id)
        return true
    }

    /** 이 요청에서 신뢰 기기 검증이 성공했는지(새 로그인 알림 억제 판정용). */
    fun wasValidatedInRequest(
        request: HttpServletRequest,
        userId: UUID,
    ): Boolean = request.getAttribute(VALIDATED_ATTR) == userId

    /** 계정 페이지 목록 — 만료된 기기는 더 이상 MFA 를 스킵하지 못하므로 노출하지 않는다(정리 잡이 행도 지운다). */
    @Transactional(readOnly = true)
    fun list(userId: UUID): List<TrustedDeviceResponse> =
        trustedDeviceRepository
            .findByUserId(userId)
            .filterNot { it.isExpired() }
            .sortedBy { it.createdAt }
            .map {
                TrustedDeviceResponse(
                    id = it.id!!,
                    uaLabel = it.uaLabel ?: RequestClientInfo.UNKNOWN_DEVICE,
                    createdAt = it.createdAt,
                    lastUsedAt = it.lastUsedAt,
                    expiresAt = it.expiresAt,
                )
            }

    /** 개별 해제. 다른 사용자의 기기는 존재 여부를 숨기기 위해 NOT_FOUND 로 응답한다. */
    @Transactional
    fun revoke(
        userId: UUID,
        deviceId: UUID,
    ) {
        val device =
            trustedDeviceRepository.findById(deviceId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND)
        if (device.userId != userId) {
            throw AuthException(ErrorCode.NOT_FOUND)
        }
        trustedDeviceRepository.delete(device)
        auditEventService.record("TRUSTED_DEVICE_REVOKED", userId, mapOf("deviceId" to deviceId.toString()))
    }

    /** 전체 해제 — 비밀번호 재설정·MFA 해제/재등록 시에도 호출된다(무효화 트리거). */
    @Transactional
    fun revokeAll(userId: UUID) {
        trustedDeviceRepository.deleteAllByUserId(userId)
        auditEventService.record("TRUSTED_DEVICE_REVOKED_ALL", userId, emptyMap())
    }

    private fun cookieValue(request: HttpServletRequest): String? =
        request.cookies
            ?.firstOrNull { it.name == COOKIE_NAME }
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun writeCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        value: String,
        maxAge: Duration,
    ) {
        val cookie =
            ResponseCookie
                .from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(request.isSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }
}
