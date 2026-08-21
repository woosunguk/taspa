package com.taspa.server.selfservice

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.login.LoginFlowSupport
import com.taspa.server.mail.MailService
import com.taspa.server.org.OrgAutoJoinService
import com.taspa.server.session.SessionManagementService
import com.taspa.server.verification.EmailVerificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * 자기서비스 이메일 변경(2단계 — step-up 은 컨트롤러의 @RequireRecentAuth 로 강제).
 *
 * 1) requestChange: 새 이메일 정규화·중복 검사 후, 새 주소로 확인 코드를 발송하고(EmailVerificationService
 *    재사용) 대상 주소를 세션(PendingEmailChange)에 묶는다. 이 시점엔 계정 이메일을 바꾸지 않는다.
 * 2) confirmChange: 세션의 대상 주소 + 코드 소진이 모두 성공해야 전환한다. 전환은 (a) users.email 갱신,
 *    (b) 옛 이메일로 키잉된 제3자 권한부여(oauth2_authorization)·동의(oauth2_authorization_consent) 폐기,
 *    (c) 현재 세션의 SecurityContext principal 을 새 이메일로 재수립, (d) 옛 이메일로 인덱싱된 나머지
 *    세션 폐기, (e) 옛 주소 통지, (f) EMAIL_CHANGED 감사.
 *
 * **제3자 권한부여 폐기 근거**: oauth2_authorization/oauth2_authorization_consent 는 users FK 없이
 * principal_name(=이메일)으로만 키잉된다. 이메일을 바꾸면 이 행들이 옛 이메일에 고아로 남아 (1) 연결앱
 * 목록/철회가 불가능해지고, (2) 이후 계정 탈퇴 시에도 옛 이메일 행이 삭제되지 않아 PII·활성 refresh_token
 * 이 잔존하며, (3) 옛 이메일 재가입 시 교차계정 토큰 발급 위험이 생긴다. principal_name 만 새 이메일로
 * 옮겨도 refresh 경로는 저장된 principal(attributes 직렬화)의 옛 이메일을 복원해 sub 회귀를 일으키므로,
 * 가장 안전하게 해당 사용자의 authorization·consent 를 **폐기**해 재동의를 요구한다.
 *
 * **stale principal 안전(스펙 편차 근거)**: 세션의 authentication.name 은 이메일이고, 자기서비스 전
 * 컨트롤러가 이 값으로 사용자를 조회한다. 이메일이 바뀌면 옛 이메일을 principal 로 들고 있는 다른 세션은
 * (1) 조회 실패로 사실상 죽고, (2) 훗날 옛 이메일이 재가입(탈퇴/이메일 변경으로 해방됨)되면 **다른 계정**으로
 * 결합될 수 있다. 그래서 계획서의 "전 세션 유지"를 문자 그대로 따르지 않고, **현재 세션만** 새 이메일로
 * 재수립해 유지하고 옛 이메일의 나머지 세션은 폐기한다. OIDC sub 은 UUID 라 제3자 토큰 매핑은 안전하다.
 */
@Service
class EmailChangeService(
    private val userRepository: UserRepository,
    private val emailVerificationService: EmailVerificationService,
    private val loginFlowSupport: LoginFlowSupport,
    private val sessionManagementService: SessionManagementService,
    private val mailService: MailService,
    private val orgAutoJoinService: OrgAutoJoinService,
    private val auditEventService: AuditEventService,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
) {
    fun requestChange(
        request: HttpServletRequest,
        userId: UUID,
        rawNewEmail: String,
    ) {
        val newEmail = rawNewEmail.trim().lowercase()
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        if (newEmail == user.email.lowercase()) {
            throw AuthException(ErrorCode.EMAIL_UNCHANGED)
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw AuthException(ErrorCode.EMAIL_ALREADY_EXISTS)
        }

        request.getSession(true).setAttribute(
            PendingEmailChange.SESSION_KEY,
            PendingEmailChange(newEmail, Instant.now().plus(PendingEmailChange.TTL)),
        )
        // 코드 발송·저장은 EmailVerificationService 를 재사용하되, 현재 이메일이 아닌 새 주소로 보낸다.
        emailVerificationService.issueToAddress(userId, newEmail)
        auditEventService.record("EMAIL_CHANGE_REQUESTED", userId, mapOf("newEmail" to newEmail))
    }

    fun confirmChange(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: UUID,
        code: String,
    ) {
        val session = request.getSession(false)
        val pending = session?.getAttribute(PendingEmailChange.SESSION_KEY) as? PendingEmailChange
        if (pending == null || pending.isExpired()) {
            session?.removeAttribute(PendingEmailChange.SESSION_KEY)
            throw AuthException(ErrorCode.EMAIL_CHANGE_NOT_PENDING)
        }
        // 코드 소진(시도 제한 포함)이 먼저 — 실패 시 전환하지 않는다. emailVerified 는 건드리지 않는다.
        if (!emailVerificationService.consumeCode(userId, code)) {
            throw AuthException(ErrorCode.VERIFICATION_CODE_INVALID)
        }

        val currentSessionId = session.id
        // 스왑 실패(선점 레이스/무결성 위반) 시 pending 마커를 제거한다 — 코드는 이미 소진됐으므로 마커를
        // 남기면 재시도가 정확한 원인(EMAIL_ALREADY_EXISTS) 대신 VERIFICATION_CODE_INVALID(미소진 코드
        // 없음)로 혼란스러워진다. 마커를 지우면 실패 원인이 그대로 전달되고 사용자는 1단계부터 다시 시작한다.
        val (oldEmail, revokedGrants) =
            try {
                transactionTemplate.execute {
                    val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
                    val previous = user.email
                    // 소진과 확인 사이 레이스: 다른 사용자가 새 이메일을 선점했는지 재검사.
                    if (userRepository.existsByEmail(pending.newEmail)) {
                        throw AuthException(ErrorCode.EMAIL_ALREADY_EXISTS)
                    }
                    user.email = pending.newEmail
                    try {
                        userRepository.saveAndFlush(user)
                    } catch (ex: DataIntegrityViolationException) {
                        // UNIQUE(email) 위반 — 선점 레이스를 선검사와 동일한 409 로 수렴.
                        throw AuthException(ErrorCode.EMAIL_ALREADY_EXISTS)
                    }
                    // 옛 이메일로 키잉된 제3자 권한부여·동의를 폐기(재동의 요구) — 같은 트랜잭션이라 이메일
                    // 스왑과 원자적으로 반영된다. 고아 authorization/consent 로 인한 연결앱 관리 불가·탈퇴 후
                    // PII/토큰 잔존·교차계정 토큰 발급을 원천 차단한다.
                    val revoked =
                        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", previous)
                    jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE principal_name = ?", previous)
                    previous to revoked
                }!!
            } catch (ex: Exception) {
                session.removeAttribute(PendingEmailChange.SESSION_KEY)
                throw ex
            }

        session.removeAttribute(PendingEmailChange.SESSION_KEY)
        // 현재 세션 principal 을 새 이메일로 재수립(세션 ID 는 유지) — 요청 종료 시 세션이 새 이메일로
        // 재인덱싱된다. 이 재인덱싱은 요청 커밋 시점에 반영되므로, 아래 revokeOthers 는 아직 옛 이메일로
        // 인덱싱된 "나머지" 세션만 지운다(현재 세션은 currentSessionId 로 제외).
        loginFlowSupport.establishSecurityContext(request, response, userId)
        sessionManagementService.revokeOthers(userId, oldEmail, currentSessionId)

        // 새 주소 소유 증명(코드 소진) + 이메일 스왑 확정 = '검증된 이메일의 도메인'이 바뀐 실질 전이 —
        // 신규 가입의 이메일 인증 성공과 동형이므로 자동 조직 가입을 재평가한다. 이 경로를 빼먹으면
        // 개인 메일 → 회사 메일로 변경한 사용자만 영구히 자동 가입에서 제외된다(같은 주소로 새로
        // 가입한 사용자와 결과 불일치). evaluate 는 멱등·실패 비전파(REQUIRES_NEW)라 여기 배선해도
        // 이메일 변경 플로우를 깨지 않는다. 스왑 트랜잭션은 위에서 이미 커밋됐다(순서 안전).
        userRepository.findById(userId).ifPresent { orgAutoJoinService.evaluate(it) }

        mailService.sendEmailChangedNotice(oldEmail, pending.newEmail)
        auditEventService.record(
            "EMAIL_CHANGED",
            userId,
            mapOf(
                "oldEmail" to oldEmail,
                "newEmail" to pending.newEmail,
                "revokedThirdPartyGrants" to revokedGrants,
            ),
        )
    }
}
