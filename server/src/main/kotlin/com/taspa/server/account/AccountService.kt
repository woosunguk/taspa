package com.taspa.server.account

import com.taspa.server.account.dto.SignupRequest
import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.credential.PasswordPolicyService
import com.taspa.server.domain.federation.FederatedIdentityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.passkey.PasskeyService
import com.taspa.server.verification.EmailVerificationService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val passwordPolicyService: PasswordPolicyService,
    private val federatedIdentityRepository: FederatedIdentityRepository,
    private val passkeyService: PasskeyService,
    private val auditEventService: AuditEventService,
    private val emailVerificationService: EmailVerificationService,
) {
    /**
     * 계정 생성 + **인증 코드 발송**.
     *
     * ★코드 발송은 컨트롤러가 아니라 여기 있어야 한다. 한동안 서버 렌더링 `/signup`(AccountPageController)만
     * 발송을 호출하고 **공개 JSON API `/api/accounts/signup` 은 호출하지 않았다** — 그 경로로 가입한
     * 사용자는 로그인하는 순간 "…으로 보낸 6자리 코드를 입력하세요" 화면에 도착하는데 **메일은 한 통도
     * 가지 않았다**(리허설에서 실제로 재현). 화면이 존재하지 않는 메일을 기다리라고 말하므로 사용자는
     * 받은편지함만 새로고침하고, "다시 보내기"를 눌러야 한다는 것을 추측해야 한다.
     *
     * `MfaAwareAuthenticationSuccessHandler` 의 주석("EMAIL_VERIFICATION 은 가입 시 이미 발급됨")이
     * 이미 이 불변식을 전제하고 있었다 — 전제를 코드가 아니라 호출자 규약에 맡긴 것이 결함의 형태다.
     * 가입 use case 안으로 들여오면 새 가입 경로가 생겨도 같은 구멍이 다시 열리지 않는다.
     *
     * ★**실제 SMTP 발송은 커밋 이후**다(`EmailVerificationService.sendCodeAfterCommit`). 여기서 하는 일은
     * 코드 행 저장 + 발송 예약이고, 트랜잭션이 SMTP 왕복을 기다리지 않는다 — 가입은 공개 엔드포인트라
     * 동시성이 우리 통제 밖이고, 열린 트랜잭션으로 SMTP 를 기다리면 커넥션 풀이 워커 풀보다 20배 먼저
     * 죽는다. 그 대가로 **발송 실패는 가입을 되돌리지 않는다**(계정은 남고 사용자는 "다시 보내기"로 복구).
     * 메일 장애 때 가입 자체를 막는 것보다 이쪽이 낫다 — 어차피 그 순간엔 재발송도 실패하므로 계정을
     * 지워 봐야 사용자가 얻는 것이 없고, 장애가 끝나면 그대로 이어서 진행할 수 있다.
     */
    @Transactional
    fun signup(request: SignupRequest): User {
        val email = request.email.trim().lowercase()
        val violations = passwordPolicyService.validate(request.password)
        if (violations.isNotEmpty()) {
            throw AuthException(ErrorCode.PASSWORD_POLICY_VIOLATION, violations.joinToString("; "))
        }
        val existing = userRepository.findByEmail(email)
        if (existing != null) {
            if (!isReclaimableOrphan(existing)) {
                throw AuthException(ErrorCode.EMAIL_ALREADY_EXISTS)
            }
            // 소유 증명 없이 선생성된 고아 계정(미검증 + 비밀번호·패스키·소셜 연결 전무 —
            // 소셜 가입 중이탈 또는 타인 이메일 선점 시도)이 실소유자의 가입을 영구히 막지 않도록
            // 회수한다. 로그인 수단이 하나도 없는 계정이므로 삭제해도 접근을 잃는 사람이 없다.
            userRepository.delete(existing)
            userRepository.flush()
            auditEventService.record(
                "ORPHAN_ACCOUNT_RECLAIMED",
                existing.id,
                mapOf("email" to email),
            )
        }
        val user =
            userRepository.save(
                User(
                    email = email,
                    passwordHash = passwordEncoder.encode(request.password),
                    displayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        // ★감사는 **코드 발급 뒤**에 남긴다. `AuditEventService.record` 는 REQUIRES_NEW 로 즉시 커밋되므로,
        // 먼저 기록하면 그 뒤 단계가 실패해 users 행이 롤백됐을 때 **존재하지 않는 계정의 SIGNUP 감사**만
        // 남는다(관리자는 감사 로그에서 본 계정을 사용자 목록에서 찾지 못한다). 순서를 뒤집지 말 것.
        emailVerificationService.issue(user.id!!)
        auditEventService.record("SIGNUP", user.id, mapOf("email" to user.email))
        return user
    }

    /** 이메일 소유 증명도, 로그인 수단도 없는 선생성 계정인지 — 가입 차단(선점) 대상에서 제외한다. */
    private fun isReclaimableOrphan(user: User): Boolean =
        !user.emailVerified &&
            user.passwordHash == null &&
            federatedIdentityRepository.countByUserId(user.id!!) == 0L &&
            !passkeyService.hasPasskeys(user.id)
}
