package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.config.i18n.MessageResolver
import com.taspa.server.domain.org.InvitationStatus
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import org.springframework.dao.DataAccessException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * 초대 수락(사용자 대면). `/orgs/invite/accept` 는 기본 체인의 anyRequest().authenticated() 로 보호되므로
 * 미인증 시 로그인/가입으로 유도되고(SavedRequest 로 복귀), 인증 세션만 이 핸들러에 도달한다.
 *
 * 보안: 서버가 **로그인 사용자 이메일 == 초대 이메일** 을 판정해 불일치면 수락 버튼을 노출하지 않고(안내만),
 * POST 소비는 OrgInvitationService.accept 가 다시 강제한다(하이재킹 이중 차단). 이메일 미검증 세션도 차단한다.
 */
@Controller
class OrgInvitationAcceptController(
    private val orgInvitationService: OrgInvitationService,
    private val userRepository: UserRepository,
    private val messages: MessageResolver,
) {
    private enum class AcceptState { VALID, MISMATCH, UNVERIFIED, EXPIRED, ACCEPTED, INVALID, ORG_INACTIVE }

    @GetMapping("/orgs/invite/accept")
    fun acceptPage(
        @RequestParam token: String,
        authentication: Authentication,
        model: Model,
    ): String {
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        populate(token, user, model, error = null)
        return "orgs/invite-accept"
    }

    @PostMapping("/orgs/invite/accept")
    fun submitAccept(
        @RequestParam token: String,
        authentication: Authentication,
        model: Model,
    ): String {
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        return try {
            val view = orgInvitationService.accept(token, user)
            val preview = orgInvitationService.preview(token)
            model.addAttribute("orgName", preview?.orgName ?: "")
            model.addAttribute("role", view.role)
            "orgs/invite-accepted"
        } catch (ex: AuthException) {
            // 실패 사유를 상태로 환원해 동일 페이지를 재렌더한다(만료/불일치/무효 등).
            populate(token, user, model, error = mapError(ex.errorCode))
            "orgs/invite-accept"
        } catch (ex: DataAccessException) {
            // 동시 수락(더블클릭) 등으로 드물게 무결성 경합이 서비스 밖으로 새어나오면, JSON(409)이 아니라
            // 동일 HTML 페이지를 '이미 처리됨/다시 시도' 안내로 재렌더한다(서비스의 비관적 잠금과 이중 방어).
            populate(token, user, model, error = messages.get("invite.accept.error.invalid"))
            "orgs/invite-accept"
        }
    }

    /** 미리보기 + 사용자 이메일 대조로 표시 상태를 계산해 모델에 담는다. */
    private fun populate(
        token: String,
        user: User,
        model: Model,
        error: String?,
    ) {
        val preview = orgInvitationService.preview(token)
        val userEmail = user.email.trim().lowercase()
        val state =
            when {
                preview == null -> AcceptState.INVALID
                preview.status == InvitationStatus.ACCEPTED -> AcceptState.ACCEPTED
                preview.status == InvitationStatus.REVOKED -> AcceptState.INVALID
                preview.status == InvitationStatus.EXPIRED || preview.expired -> AcceptState.EXPIRED
                // 초대 생성 후 조직이 정지되면 POST 수락이 실패하므로, 버튼 대신 안내를 노출한다(표시·처리 일치).
                !preview.orgActive -> AcceptState.ORG_INACTIVE
                userEmail != preview.email -> AcceptState.MISMATCH
                !user.emailVerified -> AcceptState.UNVERIFIED
                else -> AcceptState.VALID
            }
        model.addAttribute("token", token)
        model.addAttribute("state", state.name)
        model.addAttribute("canAccept", state == AcceptState.VALID)
        model.addAttribute("orgName", preview?.orgName ?: "")
        model.addAttribute("role", preview?.role ?: "")
        model.addAttribute("invitedEmail", preview?.email ?: "")
        model.addAttribute("userEmail", user.email)
        if (error != null) {
            model.addAttribute("error", error)
        }
    }

    private fun mapError(code: ErrorCode): String =
        when (code) {
            ErrorCode.INVITATION_EMAIL_MISMATCH -> messages.get("invite.accept.error.mismatch")
            ErrorCode.INVITATION_EXPIRED -> messages.get("invite.accept.error.expired")
            // accept() 경로의 VALIDATION_ERROR 는 정지 조직(findActiveOrg)에서만 발생한다 — 직접 POST 시 안내.
            ErrorCode.VALIDATION_ERROR -> messages.get("invite.accept.orgInactive")
            else -> messages.get("invite.accept.error.invalid")
        }
}
