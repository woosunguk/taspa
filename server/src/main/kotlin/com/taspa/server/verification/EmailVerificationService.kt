package com.taspa.server.verification

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.verification.EmailVerificationCode
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
import com.taspa.server.mail.MailService
import com.taspa.server.org.OrgAutoJoinService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@Service
class EmailVerificationService(
    private val userRepository: UserRepository,
    private val emailVerificationCodeRepository: EmailVerificationCodeRepository,
    private val mailService: MailService,
    private val auditEventService: AuditEventService,
    private val orgAutoJoinService: OrgAutoJoinService,
    @Value("\${taspa.email-verification.code-ttl-minutes:10}")
    private val codeTtlMinutes: Long,
    @Value("\${taspa.email-verification.resend-interval-seconds:60}")
    private val resendIntervalSeconds: Long,
) {
    companion object {
        private val secureRandom = SecureRandom()

        /**
         * 코드당 실패 허용 횟수. 초과 시 코드를 소진(무효화)해 재발송을 강제한다 —
         * 6자리(10^6) 공간이라 시도 제한이 없으면, 특히 RISK_CHALLENGE(올바른 비밀번호를 가진
         * 공격자에 대한 2차 인증)에서 무차별 대입으로 게이트가 뚫린다. 재발송은 60초 스로틀이
         * 걸려 있으므로 상한 5회와 결합하면 시도율이 분당 ~5회로 묶인다.
         */
        const val MAX_ATTEMPTS_PER_CODE = 5
    }

    /** 6자리 인증 코드를 발급/저장하고 사용자의 현재 이메일로 발송한다. */
    @Transactional
    fun issue(userId: UUID) {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        issueToAddress(userId, user.email)
    }

    /**
     * 6자리 인증 코드를 발급/저장하고 지정한 이메일 주소로 발송한다. 코드 행은 userId 로 키잉되므로
     * 이메일 변경(selfservice/EmailChangeService)처럼 "현재 이메일이 아닌 새 주소"로 코드를 보내면서도
     * 검증은 동일한 consumeCode(userId, code) 로 수렴한다. targetEmail 은 이미 정규화(소문자·trim)된
     * 값을 넘길 것.
     */
    @Transactional
    fun issueToAddress(
        userId: UUID,
        targetEmail: String,
    ) {
        val code = generateCode()
        emailVerificationCodeRepository.save(
            EmailVerificationCode(
                userId = userId,
                codeHash = SecureTokenGenerator.hashToken(code),
                expiresAt = Instant.now().plusSeconds(codeTtlMinutes * 60),
            ),
        )
        sendCodeAfterCommit(targetEmail, code)
        auditEventService.record("EMAIL_VERIFICATION_SENT", userId, mapOf("email" to targetEmail))
    }

    /**
     * 인증 코드 메일은 **커밋 이후**에 보낸다 — `OrgInvitationService.sendInvitationAfterCommit` 과 같은 규약.
     *
     * 두 가지를 동시에 해결한다:
     * - **커넥션 점유**: SMTP 는 신규 TCP+TLS+AUTH+DATA 왕복이라 수백 ms~수 초가 걸린다. 그 시간 동안
     *   열린 트랜잭션과 풀 커넥션을 붙들면, 이 저장소가 이미 두 번 겪은 형태로 **커넥션 풀이 워커 풀보다
     *   20배 먼저 죽는다**(가입은 공개 엔드포인트라 동시성이 우리 통제 밖이다).
     * - **죽은 코드 메일**: 롤백된 트랜잭션의 코드 행은 DB 에 없다. 그 코드를 담은 메일이 나가면 사용자는
     *   "보낸 코드"를 입력하는데 영원히 실패한다 — 초대 메일에서 같은 이유로 afterCommit 을 쓴다.
     *
     * 트랜잭션 밖에서 호출되면(스케줄 잡·테스트) 그대로 즉시 발송한다.
     */
    private fun sendCodeAfterCommit(
        targetEmail: String,
        code: String,
    ) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        mailService.sendVerificationCode(targetEmail, code)
                    }
                },
            )
        } else {
            mailService.sendVerificationCode(targetEmail, code)
        }
    }

    /** 최근 미소진 코드와 일치하고 만료되지 않았으면 이메일을 인증 처리한다. */
    @Transactional
    fun verify(
        userId: UUID,
        code: String,
    ): Boolean {
        if (!consumeValidCode(userId, code)) {
            return false
        }
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        user.emailVerified = true
        userRepository.save(user)
        auditEventService.record("EMAIL_VERIFIED", user.id, mapOf("email" to user.email))
        // 이메일 인증 성공 = 도메인 소유 개인 증명 완료 — 자동 조직 가입 판정(실패 비전파, 멱등).
        orgAutoJoinService.evaluate(user)
        return true
    }

    /**
     * 리스크 챌린지(RISK_CHALLENGE) 통과 검증 — 코드 소진만 하고 emailVerified 는 건드리지 않는다
     * (이 게이트는 이미 인증된 사용자에게만 배정되므로 재기록은 불필요한 UPDATE 이고, 감사 로그의
     * EMAIL_VERIFIED 가 가입 인증과 챌린지 통과로 오염된다). 통과는 RISK_CHALLENGE_PASSED 로
     * 기록해 관리 콘솔에서 RISK_DETECTED(진입)와 짝지어 볼 수 있게 한다.
     */
    @Transactional
    fun verifyRiskChallenge(
        userId: UUID,
        code: String,
    ): Boolean {
        if (!consumeValidCode(userId, code)) {
            return false
        }
        val email = userRepository.findById(userId).map { it.email }.orElse(null)
        auditEventService.record("RISK_CHALLENGE_PASSED", userId, mapOf("email" to email))
        return true
    }

    /**
     * 코드 소진만 수행하고 emailVerified·감사에는 손대지 않는다 — 이메일 변경 확인처럼
     * "코드로 새 주소 소유를 증명"하는 흐름에서 호출부가 자체 후처리(이메일 전환·EMAIL_CHANGED 감사)를
     * 하도록 소진 결과만 돌려준다.
     */
    @Transactional
    fun consumeCode(
        userId: UUID,
        code: String,
    ): Boolean = consumeValidCode(userId, code)

    /**
     * 최근 미소진 코드 대조 + 시도 횟수 제한. 불일치 시 실패 카운트를 올리고,
     * MAX_ATTEMPTS_PER_CODE 도달 시 코드를 소진(무효화)한다 — 이후 제출은 재발송 전까지 전부
     * 실패하므로, 60초 재발송 스로틀과 함께 브루트포스 시도율의 상한이 된다.
     */
    private fun consumeValidCode(
        userId: UUID,
        code: String,
    ): Boolean {
        val latest =
            emailVerificationCodeRepository
                .findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(userId) ?: return false
        if (latest.isExpired()) {
            return false
        }
        if (latest.codeHash != SecureTokenGenerator.hashToken(code)) {
            latest.failedAttempts += 1
            if (latest.failedAttempts >= MAX_ATTEMPTS_PER_CODE) {
                latest.consumedAt = Instant.now()
                auditEventService.record(
                    "EMAIL_VERIFICATION_CODE_LOCKED",
                    userId,
                    mapOf("failedAttempts" to latest.failedAttempts),
                )
            }
            emailVerificationCodeRepository.save(latest)
            return false
        }
        latest.consumedAt = Instant.now()
        emailVerificationCodeRepository.save(latest)
        return true
    }

    /** 최소 재발송 간격(기본 60초)을 지켰으면 새 코드를 발송한다. 너무 이르면 false. */
    @Transactional
    fun resend(userId: UUID): Boolean {
        val latest = emailVerificationCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
        if (latest != null && latest.createdAt.isAfter(Instant.now().minusSeconds(resendIntervalSeconds))) {
            return false
        }
        issue(userId)
        return true
    }

    private fun generateCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))
}
