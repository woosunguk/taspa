package com.taspa.server.selfservice.dto

import com.taspa.server.domain.user.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** 프로필 편집: 표시 이름 변경. null/공백은 표시 이름 제거로 취급한다. */
data class ProfileUpdateRequest(
    @field:Size(max = 100)
    val displayName: String?,
)

/** 이메일 변경 1단계: 새 이메일로 확인 코드 발송을 요청한다. */
data class EmailChangeRequest(
    // 상한 근거는 User.MAX_EMAIL_LENGTH — 세션 PRINCIPAL_NAME(VARCHAR 100) 인덱싱 제약.
    @field:Email
    @field:NotBlank
    @field:Size(max = User.MAX_EMAIL_LENGTH)
    val newEmail: String,
)

/** 이메일 변경 2단계: 새 이메일로 받은 코드로 전환을 확정한다. */
data class EmailChangeConfirmRequest(
    @field:NotBlank
    val code: String,
)

/**
 * 인세션 비밀번호 변경/설정.
 * - 비밀번호 보유 계정: currentPassword 필수(현재 비밀번호 확인).
 * - 소셜 전용 계정(password_hash NULL): currentPassword 없이 step-up 후 최초 설정.
 */
data class PasswordChangeRequest(
    val currentPassword: String?,
    @field:NotBlank
    val newPassword: String,
)

/** 계정 탈퇴: 확인용으로 자신의 이메일을 재입력받는다(오작동 방지 게이트). */
data class AccountDeleteRequest(
    @field:NotBlank
    val email: String,
)

/**
 * 연결된(제3자) 앱 항목 — 사용자가 권한을 부여한 OAuth2 클라이언트.
 * registeredClientId 는 철회 대상 식별자(oauth2_registered_client.id 내부값)이고, 사람이 읽는 이름은
 * clientName 이다.
 */
data class AuthorizedClientView(
    val registeredClientId: String,
    val clientName: String,
    val scopes: List<String>,
    val lastUsedAt: Instant?,
)

/**
 * 최근 로그인 활동 항목(계정 페이지 읽기 전용). 세션 목록과 별개이며 폐기/철회 대상이 아니다.
 * method 는 login_events.method(password / mfa / passkey / social:{provider} / magic)를 그대로 노출한다.
 */
data class LoginHistoryView(
    val occurredAt: Instant,
    val method: String,
    val ip: String?,
    val device: String?,
)
