package com.taspa.server.selfservice

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.org.OrganizationService
import com.taspa.server.selfservice.dto.AccountDeleteRequest
import com.taspa.server.selfservice.dto.AuthorizedClientView
import com.taspa.server.selfservice.dto.CurrentUserView
import com.taspa.server.selfservice.dto.EmailChangeConfirmRequest
import com.taspa.server.selfservice.dto.EmailChangeRequest
import com.taspa.server.selfservice.dto.LoginHistoryView
import com.taspa.server.selfservice.dto.PasswordChangeRequest
import com.taspa.server.selfservice.dto.ProfileUpdateRequest
import com.taspa.server.stepup.RequireRecentAuth
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 자기서비스 계정 관리(Stage 2) + 연결된 앱(Stage 3) API. 계정 페이지(/account) JS 가 호출한다.
 *
 * CSRF: 이 컨트롤러의 경로(api/account 이하)는 다른 api 경로 관례에 따라 CSRF 면제 대상이다(형제
 * API — api/mfa·api/federations·api/trusted-devices·api/passkeys 와 동일). 파괴/민감 작업은 전부
 * @RequireRecentAuth(step-up)로 보호되고, JSON 바디 + SameSite=Lax 세션 쿠키가 교차출처 위조를 막는다.
 * step-up 은 위조 공격자가 재현할 수 없는 최근 재인증을 요구하므로 CSRF 심층 방어와 겹친다.
 */
@RestController
@RequestMapping("/api/account")
class SelfServiceController(
    private val userRepository: UserRepository,
    private val profileService: ProfileService,
    private val emailChangeService: EmailChangeService,
    private val passwordChangeService: PasswordChangeService,
    private val accountDeletionService: AccountDeletionService,
    private val connectedAppService: ConnectedAppService,
    private val loginHistoryService: LoginHistoryService,
    private val organizationService: OrganizationService,
) {
    /**
     * 현재 로그인 사용자의 신원·상태. SPA 가 부팅 시 1회 호출해 화면 진입점(관리 콘솔 노출 여부 등)을 정한다.
     * 계정 페이지(/account)가 서버 렌더링으로 조립하던 모델과 같은 값을 노출한다.
     *
     * 조직 목록은 여기 담지 않는다 — `/api/orgs/memberships`(내 소속)와 `/api/orgs/mine`(내가 관리하는 org)이
     * 이미 그 역할을 하고 화면마다 필요 시점이 달라, 여기에 합치면 부팅 응답만 무거워진다.
     */
    @GetMapping("/me")
    fun me(authentication: Authentication): ResponseEntity<CurrentUserView> {
        val user = currentUser(authentication)
        return ResponseEntity.ok(
            CurrentUserView(
                userId = user.id!!,
                email = user.email,
                displayName = user.displayName,
                emailVerified = user.emailVerified,
                mfaEnabled = user.mfaEnabled,
                hasPassword = user.passwordHash != null,
                platformAdmin = user.role == UserRole.ADMIN.name,
                manageableOrgs = organizationService.listAdministeredOrgs(user.id!!).isNotEmpty(),
            ),
        )
    }

    /** 프로필 편집(표시 이름) — 비파괴 작업이라 step-up 대상이 아니다. */
    @PatchMapping("/profile")
    fun updateProfile(
        authentication: Authentication,
        @Valid @RequestBody request: ProfileUpdateRequest,
    ): ResponseEntity<Void> {
        profileService.updateDisplayName(currentUser(authentication).id!!, request.displayName)
        return ResponseEntity.noContent().build()
    }

    /** 이메일 변경 1단계: 새 주소로 확인 코드 발송 — step-up 대상. */
    @RequireRecentAuth
    @PostMapping("/email/change")
    fun requestEmailChange(
        authentication: Authentication,
        request: HttpServletRequest,
        @Valid @RequestBody body: EmailChangeRequest,
    ): ResponseEntity<Void> {
        emailChangeService.requestChange(request, currentUser(authentication).id!!, body.newEmail)
        return ResponseEntity.accepted().build()
    }

    /**
     * 이메일 변경 2단계: 코드 확인 시 전환. 초기화(1단계)가 이미 step-up 을 통과했고, 확인의 보안 통제는
     * 새 주소로 발송된 코드 소유이므로 여기엔 @RequireRecentAuth 를 걸지 않는다(코드 TTL 과 step-up
     * max-age 가 겹쳐 재인증 강제 시 데드락 위험). 성공 시 현재 세션 principal 이 새 이메일로 재수립된다.
     */
    @PostMapping("/email/change/confirm")
    fun confirmEmailChange(
        authentication: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @Valid @RequestBody body: EmailChangeConfirmRequest,
    ): ResponseEntity<Void> {
        emailChangeService.confirmChange(request, response, currentUser(authentication).id!!, body.code)
        return ResponseEntity.noContent().build()
    }

    /** 인세션 비밀번호 변경/설정 — step-up 대상. 성공 시 모든 세션 폐기 → 클라이언트는 /login 으로. */
    @RequireRecentAuth
    @PostMapping("/password")
    fun changePassword(
        authentication: Authentication,
        @Valid @RequestBody body: PasswordChangeRequest,
    ): ResponseEntity<Void> {
        passwordChangeService.changePassword(
            currentUser(authentication).id!!,
            body.currentPassword,
            body.newPassword,
        )
        return ResponseEntity.noContent().build()
    }

    /** 계정 탈퇴(하드 삭제) — step-up + 이메일 재입력 확인. 성공 시 세션·쿠키 정리 후 로그아웃. */
    @RequireRecentAuth
    @DeleteMapping
    fun deleteAccount(
        authentication: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @Valid @RequestBody body: AccountDeleteRequest,
    ): ResponseEntity<Void> {
        val user = currentUser(authentication)
        if (body.email.trim().lowercase() != user.email.lowercase()) {
            throw AuthException(ErrorCode.CONFIRMATION_MISMATCH)
        }
        accountDeletionService.deleteAccount(user.id!!, user.email)
        logoutCurrent(request, response)
        return ResponseEntity.noContent().build()
    }

    /** 연결된 앱 목록(Stage 3). */
    @GetMapping("/authorized-clients")
    fun listAuthorizedClients(authentication: Authentication): ResponseEntity<List<AuthorizedClientView>> =
        ResponseEntity.ok(connectedAppService.list(authentication.name))

    /** 최근 로그인 활동(Stage 7) — 읽기 전용. 세션 목록과 별개이며 step-up 대상이 아니다(파괴 작업 없음). */
    @GetMapping("/login-history")
    fun listLoginHistory(authentication: Authentication): ResponseEntity<List<LoginHistoryView>> =
        ResponseEntity.ok(loginHistoryService.recentHistory(currentUser(authentication).id!!))

    /** 연결된 앱 철회(Stage 3) — step-up 대상. */
    @RequireRecentAuth
    @DeleteMapping("/authorized-clients/{registeredClientId}")
    fun revokeAuthorizedClient(
        authentication: Authentication,
        @PathVariable registeredClientId: String,
    ): ResponseEntity<Void> {
        connectedAppService.revoke(
            currentUser(authentication).id!!,
            authentication.name,
            registeredClientId,
        )
        return ResponseEntity.noContent().build()
    }

    private fun currentUser(authentication: Authentication): User =
        userRepository.findByEmail(authentication.name)
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

    /** 탈퇴 직후 현재 요청의 인증 상태·세션·쿠키를 정리한다(세션 자체는 서비스가 이미 저장소에서 폐기). */
    private fun logoutCurrent(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        SecurityContextHolder.clearContext()
        request.getSession(false)?.invalidate()
        expireCookie(request, response, "SESSION")
        expireCookie(request, response, TrustedDeviceService.COOKIE_NAME)
    }

    private fun expireCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        name: String,
    ) {
        val cookie =
            ResponseCookie
                .from(name, "")
                .httpOnly(true)
                .secure(request.isSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }
}
