package com.taspa.server.verification

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.transaction.support.TransactionTemplate

/**
 * 인증 코드 메일의 **발송 시점** 불변식 — "커밋된 코드에 대해서만, 커밋 이후에" 보낸다.
 *
 * 두 가지를 동시에 지킨다:
 * - **커넥션 점유**: SMTP 는 신규 TCP+TLS+AUTH+DATA 왕복이라 수백 ms~수 초다. 열린 트랜잭션으로 그것을
 *   기다리면 이 저장소가 이미 두 번 겪은 형태로 커넥션 풀이 워커 풀보다 20배 먼저 죽는다. 가입은
 *   공개 엔드포인트라 동시성이 우리 통제 밖이다.
 * - **죽은 코드 메일**: 롤백된 트랜잭션의 코드 행은 DB 에 없다. 그 코드를 담은 메일이 나가면 사용자는
 *   "보낸 코드"를 입력하는데 영원히 실패하고, 화면은 그 이유를 설명하지 못한다.
 *
 * ★이 테스트가 없으면 `sendCodeAfterCommit` 을 인라인 호출로 되돌려도 다른 테스트는 전부 통과한다
 *   (정상 경로에서는 결국 메일이 나가므로 구별되지 않는다). 구별하려면 **롤백**을 봐야 한다.
 */
class EmailVerificationMailTimingIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var emailVerificationService: EmailVerificationService

    @Autowired lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val sentMessages = mutableListOf<SimpleMailMessage>()

    @BeforeEach
    fun setUp() {
        emailVerificationCodeRepository.deleteAll()
        userRepository.deleteAll()
        sentMessages.clear()
        every { mailSender.send(capture(sentMessages)) } just Runs
    }

    private fun createUser(): User =
        userRepository.save(
            User(email = "timing@example.com", passwordHash = "{noop}irrelevant"),
        )

    @Test
    fun `코드 메일은 커밋 이후에 나간다 - 롤백되면 발송되지 않는다`() {
        val user = createUser()

        runCatching {
            transactionTemplate.execute {
                emailVerificationService.issue(user.id!!)
                // 발송 예약만 된 상태에서 트랜잭션을 되돌린다.
                throw IllegalStateException("의도적 롤백")
            }
        }

        assertThat(sentMessages).isEmpty()
        // 코드 행도 함께 사라져야 한다 — 둘이 어긋나면 사용자는 오지 않는 메일이나 안 맞는 코드를 만난다.
        assertThat(emailVerificationCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(user.id!!)).isNull()
    }

    /** 대조군 — 커밋되면 실제로 나간다(위 단언이 "항상 무발송"으로 통과하지 않게). */
    @Test
    fun `커밋되면 코드 메일이 나간다`() {
        val user = createUser()

        transactionTemplate.execute { emailVerificationService.issue(user.id!!) }

        assertThat(sentMessages).hasSize(1)
        assertThat(sentMessages.single().to).containsExactly(user.email)
        assertThat(emailVerificationCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(user.id!!)).isNotNull
    }
}
