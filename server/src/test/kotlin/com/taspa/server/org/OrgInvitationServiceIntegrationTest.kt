package com.taspa.server.org

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.org.InvitationStatus
import com.taspa.server.domain.org.OrgInvitation
import com.taspa.server.domain.org.OrgInvitationRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 조직 초대 서비스 통합 테스트 — 스펙 §보안·§테스트 커버:
 *  - 토큰 해시 저장·단일 사용·만료, 이메일 일치 강제(하이재킹 차단), (org,email) PENDING 1건,
 *    계정 열거 금지(미존재 이메일 동일 응답), 이미 멤버 멱등, org 격리(취소).
 */
class OrgInvitationServiceIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var service: OrgInvitationService

    @Autowired lateinit var invitationRepository: OrgInvitationRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private lateinit var orgA: UUID
    private lateinit var orgB: UUID

    @BeforeEach
    fun setUp() {
        auditEventRepository.deleteAll()
        invitationRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "inv-a", name = "Invite A")).id!!
        orgB = organizationRepository.save(Organization(slug = "inv-b", name = "Invite B")).id!!
    }

    // ---- 발송 / 토큰 ----

    @Test
    fun `invite 는 수락 URL 과 토큰을 메일로 보내고 해시만 저장한다`() {
        val messages = mutableListOf<SimpleMailMessage>()
        every { mailSender.send(capture(messages)) } just Runs

        service.invite(orgA, "Invitee@Example.com", "MEMBER", "Sales", inviterId = null)

        val stored = invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)
        assertThat(stored).hasSize(1)
        val inv = stored.first()
        assertThat(inv.email).isEqualTo("invitee@example.com") // 소문자 정규화
        assertThat(inv.tokenHash).hasSize(64) // SHA-256 hex

        val text = messages.single().text!!
        assertThat(messages.single().to!!.toList()).containsExactly("invitee@example.com")
        val token = Regex("token=([^\\s]+)").find(text)!!.groupValues[1]
        // 원문 토큰은 저장돼 있지 않고, 해시만 저장된다.
        assertThat(inv.tokenHash).isEqualTo(SecureTokenGenerator.hashToken(token))
        assertThat(text).contains("/orgs/invite/accept?token=")
    }

    @Test
    fun `미존재 이메일에도 동일하게 초대가 생성·발송된다(계정 열거 금지)`() {
        val messages = mutableListOf<SimpleMailMessage>()
        every { mailSender.send(capture(messages)) } just Runs

        // taspa 계정이 없는 이메일 — 그래도 초대는 만들어지고 메일은 나간다(존재 여부 노출 금지).
        service.invite(orgA, "nobody@example.com", null, null, inviterId = null)

        assertThat(invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)).hasSize(1)
        assertThat(messages).hasSize(1)
    }

    @Test
    fun `동일 org email 재초대는 PENDING 1건을 유지하고 토큰을 갱신한다`() {
        val firstToken = captureToken { service.invite(orgA, "dup@example.com", "MEMBER", null, null) }
        // 재발송 쿨다운을 우회하기 위해 마지막 발송 시각(createdAt)을 과거로 돌린다(정상적 시간 경과 모사).
        ageInvitation(orgA, "dup@example.com")
        // ORG_ADMIN 초대는 승격 권한을 요구한다(`mayGrantOrgAdmin` 기본값은 **false** — 새 호출 경로가
        // 생겨도 닫히는 쪽으로 실패한다). 이 테스트는 인가가 아니라 재초대 **동작**을 보는 것이므로
        // 컨트롤러가 조직관리자에게 하는 것과 같이 권한 있음을 명시한다.
        val secondToken =
            captureToken {
                service.invite(orgA, "dup@example.com", "ORG_ADMIN", "Ops", null, mayGrantOrgAdmin = true)
            }

        val pending = invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)
        assertThat(pending).hasSize(1) // (org,email) PENDING 1건
        assertThat(firstToken).isNotEqualTo(secondToken)
        // 갱신된 초대는 새 역할/부서를 반영한다.
        assertThat(pending.first().role).isEqualTo("ORG_ADMIN")
        assertThat(pending.first().department).isEqualTo("Ops")

        // 옛 토큰은 더 이상 유효하지 않다(해시가 교체됨).
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(firstToken))).isNull()
    }

    @Test
    fun `동일 email 즉시 재초대는 쿨다운으로 거부된다(단일 주소 이메일 폭탄 차단)`() {
        captureToken { service.invite(orgA, "flood@example.com", "MEMBER", null, null) }

        // 쿨다운 안의 즉시 재발송은 거부된다 — 재초대가 PENDING 1건을 재사용해도 발송 빈도가 상한된다.
        assertThatThrownBy { service.invite(orgA, "flood@example.com", "MEMBER", null, null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.VALIDATION_ERROR)
        // 발송은 최초 1건뿐(재발송 안 됨).
        assertThat(invitationRepository.findByOrgIdAndStatusOrderByCreatedAtDesc(orgA, InvitationStatus.PENDING.name)).hasSize(1)
    }

    @Test
    fun `department 가 120자를 넘으면 검증 오류로 거부된다`() {
        assertThatThrownBy { service.invite(orgA, "long-dept@example.com", "MEMBER", "x".repeat(121), null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.VALIDATION_ERROR)
        assertThat(invitationRepository.count()).isZero()
    }

    @Test
    fun `이미 활성 멤버는 초대할 수 없다`() {
        val user = saveUser("member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.MEMBER.name))

        assertThatThrownBy { service.invite(orgA, "member@example.com", "MEMBER", null, null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.VALIDATION_ERROR)
    }

    // ---- 수락 ----

    @Test
    fun `accept 는 멤버십을 만들고 초대를 ACCEPTED 로 단일 사용 처리한다`() {
        val user = saveUser("join@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "join@example.com", role = "ORG_ADMIN", department = "Eng")

        service.accept(rawToken, user)

        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo("ORG_ADMIN")
        assertThat(membership.department).isEqualTo("Eng")
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.ACCEPTED.name)

        // 재사용 거부 — 단일 사용.
        assertThatThrownBy { service.accept(rawToken, user) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.INVITATION_INVALID)
    }

    @Test
    fun `이메일 불일치 수락은 거부된다(하이재킹 차단)`() {
        saveUser("victim@example.com", verified = true)
        val attacker = saveUser("attacker@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "victim@example.com")

        assertThatThrownBy { service.accept(rawToken, attacker) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.INVITATION_EMAIL_MISMATCH)

        // 공격자 멤버십은 생기지 않고, 초대는 여전히 PENDING(소비되지 않음).
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, attacker.id!!)).isNull()
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.PENDING.name)
    }

    @Test
    fun `이메일 미검증 사용자는 수락할 수 없다`() {
        val user = saveUser("unverified@example.com", verified = false)
        val rawToken = seedInvitation(orgA, "unverified@example.com")

        assertThatThrownBy { service.accept(rawToken, user) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.FORBIDDEN)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)).isNull()
    }

    @Test
    fun `만료된 초대 수락은 거부되고 EXPIRED 로 전이된다`() {
        val user = saveUser("late@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "late@example.com", expiresAt = Instant.now().minus(Duration.ofDays(1)))

        assertThatThrownBy { service.accept(rawToken, user) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.INVITATION_EXPIRED)
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.EXPIRED.name)
    }

    @Test
    fun `취소된 초대는 수락할 수 없다`() {
        val user = saveUser("revoked@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "revoked@example.com", status = InvitationStatus.REVOKED)

        assertThatThrownBy { service.accept(rawToken, user) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.INVITATION_INVALID)
    }

    @Test
    fun `이미 멤버인 사용자가 상위 역할 초대를 수락하면 승격된다`() {
        val user = saveUser("already@example.com", verified = true)
        // 다른 경로로 이미 MEMBER 인 상태 + 상위 역할(ORG_ADMIN) PENDING 초대가 남아 있음.
        membershipRepository.save(
            OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.MEMBER.name, department = "Existing"),
        )
        val rawToken = seedInvitation(orgA, "already@example.com", role = "ORG_ADMIN", department = "New")

        service.accept(rawToken, user) // 예외 없이 성공.

        // 멤버십은 1건 그대로지만 승격이 반영된다(스펙 upsert(role·department) — 사일런트 no-op 제거).
        assertThat(membershipRepository.findByOrgId(orgA)).hasSize(1)
        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!
        assertThat(membership.role).isEqualTo(OrgRole.ORG_ADMIN.name)
        assertThat(membership.department).isEqualTo("New")
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.ACCEPTED.name)
    }

    @Test
    fun `이미 ORG_ADMIN 인 사용자가 하위 역할 초대를 수락해도 강등되지 않는다`() {
        val user = saveUser("keepadmin@example.com", verified = true)
        membershipRepository.save(
            OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.ORG_ADMIN.name, department = "Ops"),
        )
        val rawToken = seedInvitation(orgA, "keepadmin@example.com", role = "MEMBER", department = "Sales")

        service.accept(rawToken, user) // 멱등 성공(강등 no-op).

        // 하위 역할 초대는 강등하지 않는다(마지막 관리자 보호와 정합) — 역할·부서 불변, 초대는 소비.
        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!
        assertThat(membership.role).isEqualTo(OrgRole.ORG_ADMIN.name)
        assertThat(membership.department).isEqualTo("Ops")
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.ACCEPTED.name)
    }

    // ---- 잠금 회귀(자기 교착 · 잠금 후 재확인) ----

    /**
     * ★자기 교착 회귀 — 이 결함이 돌아오면 **여기서 실패해야 한다**.
     *
     * 기전: 예전 `accept()` 는 `FOR UPDATE` 로 행을 잠근 뒤 만료를 발견하면 REQUIRES_NEW(=다른 커넥션)로
     * **같은 행을 UPDATE** 했다. 안쪽은 바깥이 쥔 행 잠금을, 바깥은 안쪽 JDBC 호출의 반환을 기다린다.
     * PostgreSQL 교착 탐지기는 발동하지 않는다 — DB 가 보기에 대기자는 안쪽 하나뿐이라 잠금 그래프에
     * 순환이 없다. 운영에서는 만료 링크 클릭마다 톰캣 워커와 커넥션 2개가 **영구히** 묶였다.
     *
     * ★시간 제한이 이 테스트의 본질이다. 교착은 예외가 아니라 **무한 대기**라서, 그냥 호출하면 테스트가
     * 영원히 멈춘다 — 그러면 CI 가 죽지 결함이 드러나지 않는다(실제로 이 클래스는 그렇게 3시간을 태웠다).
     * `assertTimeoutPreemptively` 는 호출을 별도 스레드에서 돌리고 기한이 지나면 **선점적으로 중단**하므로,
     * 교착이 "영원한 정지"가 아니라 **유한한 실패**가 된다. 일반 `assertTimeout` 은 호출이 끝난 뒤에야
     * 시간을 재므로 여기서는 쓸모가 없다.
     *
     * 20초는 정상 경로(수 ms)의 수천 배 여유이면서, 방어선이 다 뚫려도 CI 가 멈추지 않게 하는 상한이다.
     * 참고로 application.yml 의 lock_timeout=3s 가 살아 있으면 교착이 재발해도 3초 뒤 잠금 획득 실패
     * 예외로 끝난다(INVITATION_EXPIRED 가 아니므로 역시 여기서 실패한다) — 2중 방어.
     */
    @Test
    fun `만료된 초대 수락은 시간 제한 안에 INVITATION_EXPIRED 로 끝난다(자기 교착 회귀)`() {
        val user = saveUser("deadlock@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "deadlock@example.com", expiresAt = Instant.now().minus(Duration.ofDays(1)))

        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThatThrownBy { service.accept(rawToken, user) }
                .isInstanceOf(AuthException::class.java)
                .extracting { (it as AuthException).errorCode }
                .isEqualTo(ErrorCode.INVITATION_EXPIRED)
        }
        // 거부는 롤백되지만 EXPIRED 전이는 REQUIRES_NEW 로 독립 커밋돼 남는다.
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.EXPIRED.name)
    }

    /**
     * 교착을 없애려고 **무잠금 사전 조회**를 FOR UPDATE 앞에 두었다. 그 대가로 사전 조회와 잠금 조회
     * **사이**에 다른 트랜잭션이 커밋할 수 있는 TOCTOU 창이 생겼다 — 단일 사용/취소 강제의 권위는
     * "잠금 후 재확인"이므로, 그 재확인이 **DB 의 현재 값**을 봐야 한다.
     *
     * 이 창을 결정적으로 재현한다: 다른 트랜잭션이 행을 FOR UPDATE 로 잡고 ACCEPTED 로 바꾼 뒤 커밋을
     * 지연시키면, accept() 는 무잠금 조회에서 PENDING(커밋 전 스냅샷)을 보고 지나간 다음 FOR UPDATE 에서
     * 블록된다. 잠금이 풀린 뒤 재확인이 stale 하면 **이미 소비된 초대가 다시 수락된다**(단일 사용 위반).
     */
    @Test
    fun `사전 조회 이후 소비된 초대는 잠금 후 재확인에서 거부된다(단일 사용 경합)`() {
        val user = saveUser("race-consumed@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "race-consumed@example.com")

        val thrown = acceptWhileStatusFlipsUnderLock(rawToken, user, InvitationStatus.ACCEPTED)

        assertThat(thrown)
            .describedAs("잠금 후 재확인이 stale 하면 소비된 초대가 다시 수락된다(단일 사용 위반)")
            .isInstanceOf(AuthException::class.java)
        assertThat((thrown as AuthException).errorCode).isEqualTo(ErrorCode.INVITATION_INVALID)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)).isNull()
    }

    /**
     * 같은 창의 보안 통제 버전 — 취소(REVOKED)는 조직관리자가 초대를 무효화하는 수단이다.
     * 재확인이 stale 하면 **취소된 초대로 조직에 합류**한다(초대 역할이 ORG_ADMIN 이면 관리자 권한까지).
     */
    @Test
    fun `사전 조회 이후 취소된 초대는 잠금 후 재확인에서 거부된다(취소 우회 차단)`() {
        val user = saveUser("race-revoked@example.com", verified = true)
        val rawToken = seedInvitation(orgA, "race-revoked@example.com", role = "ORG_ADMIN")

        val thrown = acceptWhileStatusFlipsUnderLock(rawToken, user, InvitationStatus.REVOKED)

        assertThat(thrown)
            .describedAs("잠금 후 재확인이 stale 하면 취소된 초대가 수락된다(보안 통제 우회)")
            .isInstanceOf(AuthException::class.java)
        assertThat((thrown as AuthException).errorCode).isEqualTo(ErrorCode.INVITATION_INVALID)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)).isNull()
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!.status)
            .isEqualTo(InvitationStatus.REVOKED.name)
    }

    /**
     * 운영 방어선 회귀 — PostgreSQL 세션 타임아웃이 **실제 애플리케이션 커넥션에** 걸려 있는지 못 박는다.
     *
     * 이 클래스에 두는 이유: 위 자기 교착이 "무한 정지"였던 근본 원인이 lock_timeout=0(무제한)이었다.
     * 설정은 `spring.datasource.hikari.data-source-properties.options` 로 주는데, 이 경로는 **조용히
     * 안 걸릴 수 있다**(JDBC URL 에 넣으면 IntegrationTestBase 의 @DynamicPropertySource 가 url 을 통째로
     * 갈아끼워 사라진다). 조용한 미적용이 최악이므로 값 자체를 단언한다.
     *
     * pg_settings.setting 은 해당 파라미터의 단위(ms)로 정규화된 값이라 표기 흔들림('3s'/'1min')이 없다.
     */
    @Test
    fun `PostgreSQL 세션 타임아웃 방어선이 실제 커넥션에 적용돼 있다`() {
        assertThat(settingMillis("lock_timeout"))
            .describedAs("lock_timeout — 잠금 대기가 무한이면 자기 교착이 다시 워커를 영구히 묶는다")
            .isEqualTo(3_000L)
        assertThat(settingMillis("idle_in_transaction_session_timeout"))
            .describedAs("idle_in_transaction_session_timeout — statement_timeout 이 잡지 못하는 '열린 채 멈춘' 트랜잭션의 유일한 상한")
            .isEqualTo(60_000L)
    }

    // ---- 취소 / org 격리 ----

    @Test
    fun `revoke 는 PENDING 을 REVOKED 로 바꾼다`() {
        val rawToken = seedInvitation(orgA, "cancel@example.com")
        val inv = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!

        service.revoke(orgA, inv.id!!, actorId = null)

        assertThat(invitationRepository.findById(inv.id!!).get().status).isEqualTo(InvitationStatus.REVOKED.name)
    }

    @Test
    fun `타 org 의 초대는 취소할 수 없다(org 격리)`() {
        val rawToken = seedInvitation(orgA, "iso@example.com")
        val inv = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken))!!

        // orgB 경로로 orgA 의 초대를 취소 시도 → 404(격리).
        assertThatThrownBy { service.revoke(orgB, inv.id!!, actorId = null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.NOT_FOUND)
        assertThat(invitationRepository.findById(inv.id!!).get().status).isEqualTo(InvitationStatus.PENDING.name)
    }

    @Test
    fun `expireOverdue 는 만료된 PENDING 만 EXPIRED 로 전이한다`() {
        val fresh = seedInvitation(orgA, "fresh@example.com")
        val overdue = seedInvitation(orgA, "overdue@example.com", expiresAt = Instant.now().minus(Duration.ofDays(1)))

        val transitioned = service.expireOverdue()

        assertThat(transitioned).isEqualTo(1)
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(fresh))!!.status)
            .isEqualTo(InvitationStatus.PENDING.name)
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(overdue))!!.status)
            .isEqualTo(InvitationStatus.EXPIRED.name)
    }

    // ---- 재발송(resend) ----

    @Test
    fun `resend 는 토큰을 회전한다(구 토큰 무효·신 토큰 수락)`() {
        val user = saveUser("rotate@example.com", verified = true)
        val firstToken = seedInvitation(orgA, "rotate@example.com")
        val invId = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(firstToken))!!.id!!
        ageInvitation(orgA, "rotate@example.com") // 쿨다운 통과(마지막 발송 시각을 과거로).

        val secondToken = captureToken { service.resend(orgA, invId, actorId = null) }

        assertThat(secondToken).isNotEqualTo(firstToken)
        // 구 토큰은 해시가 교체돼 조회·수락 불가.
        assertThat(invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(firstToken))).isNull()
        assertThatThrownBy { service.accept(firstToken, user) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.INVITATION_INVALID)
        // 신 토큰으로는 정상 수락 → 멤버십 생성.
        service.accept(secondToken, user)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)).isNotNull
    }

    @Test
    fun `resend 는 만료 시각을 미래로 리셋한다`() {
        // 아직 PENDING 이지만 만료 시각이 지난 초대(resend 는 만료를 검사하지 않고 새 만료로 리셋한다).
        val token = seedInvitation(orgA, "exp@example.com", expiresAt = Instant.now().minus(Duration.ofDays(1)))
        val invId = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(token))!!.id!!
        ageInvitation(orgA, "exp@example.com")

        captureToken { service.resend(orgA, invId, actorId = null) }

        assertThat(invitationRepository.findById(invId).get().expiresAt).isAfter(Instant.now())
    }

    @Test
    fun `쿨다운 안의 즉시 재발송은 거부된다`() {
        // 방금 심은 초대(createdAt=now)를 곧바로 재발송 → 쿨다운 거부.
        val token = seedInvitation(orgA, "cool@example.com")
        val invId = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(token))!!.id!!

        assertThatThrownBy { service.resend(orgA, invId, actorId = null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.VALIDATION_ERROR)
    }

    @Test
    fun `비PENDING 초대는 재발송할 수 없다`() {
        val token = seedInvitation(orgA, "done@example.com", status = InvitationStatus.ACCEPTED)
        val invId = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(token))!!.id!!

        assertThatThrownBy { service.resend(orgA, invId, actorId = null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.VALIDATION_ERROR)
    }

    @Test
    fun `타 org 의 초대는 재발송할 수 없다(org 격리)`() {
        val token = seedInvitation(orgA, "iso-resend@example.com")
        val invId = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(token))!!.id!!

        // orgB 경로로 orgA 의 초대 재발송 시도 → 404(격리).
        assertThatThrownBy { service.resend(orgB, invId, actorId = null) }
            .isInstanceOf(AuthException::class.java)
            .extracting { (it as AuthException).errorCode }
            .isEqualTo(ErrorCode.NOT_FOUND)
        assertThat(invitationRepository.findById(invId).get().status).isEqualTo(InvitationStatus.PENDING.name)
    }

    @Test
    fun `resend 는 ORG_INVITE_RESENT 감사를 남긴다`() {
        val token = seedInvitation(orgA, "audit-resend@example.com")
        val invId = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(token))!!.id!!
        ageInvitation(orgA, "audit-resend@example.com")

        captureToken { service.resend(orgA, invId, actorId = null) }

        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ORG_INVITE_RESENT", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().detail).contains(invId.toString())
    }

    // ---- helpers ----

    private fun saveUser(
        email: String,
        verified: Boolean = true,
    ): User = userRepository.save(User(email = email, emailVerified = verified))

    /** 직접 PENDING(또는 지정 상태) 초대 행을 심고 원문 토큰을 반환한다(만료/상태 제어). */
    private fun seedInvitation(
        orgId: UUID,
        email: String,
        role: String = "MEMBER",
        department: String? = null,
        status: InvitationStatus = InvitationStatus.PENDING,
        expiresAt: Instant = Instant.now().plus(Duration.ofDays(7)),
    ): String {
        val rawToken = SecureTokenGenerator.generateToken()
        invitationRepository.save(
            OrgInvitation(
                orgId = orgId,
                email = email,
                role = role,
                department = department,
                tokenHash = SecureTokenGenerator.hashToken(rawToken),
                status = status.name,
                expiresAt = expiresAt,
            ),
        )
        return rawToken
    }

    private fun captureToken(action: () -> Unit): String {
        val slot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(slot)) } just Runs
        action()
        return Regex("token=([^\\s]+)").find(slot.captured.text!!)!!.groupValues[1]
    }

    /** PostgreSQL 파라미터의 현재 값을 ms 로 읽는다(pg_settings.setting 은 단위가 ms 로 정규화돼 있다). */
    private fun settingMillis(name: String): Long =
        jdbcTemplate.queryForObject("select setting::bigint from pg_settings where name = ?", Long::class.java, name)!!

    /**
     * accept() 의 **무잠금 사전 조회와 FOR UPDATE 재조회 사이**에 다른 트랜잭션이 상태를 바꾸고 커밋하는
     * 경합을 결정적으로 만든다. 반환은 accept() 가 던진 예외(성공했으면 null).
     *
     * 순서:
     *  1. 변경 스레드가 행을 FOR UPDATE 로 잡고 새 상태로 UPDATE 한 뒤 **커밋하지 않고 대기**한다.
     *  2. 수락 스레드가 accept() 를 시작한다 — 무잠금 사전 조회는 READ COMMITTED 라 커밋 전 스냅샷
     *     (PENDING)을 보고 통과하고, FOR UPDATE 에서 잠금 대기에 들어간다.
     *  3. 짧은 유예 뒤 변경 스레드가 커밋한다 → 수락 스레드가 잠금을 얻고 **재확인**을 수행한다.
     *
     * 잠금 보유는 lock_timeout(3s) 안에 끝나야 하므로 유예를 짧게 둔다. 유예가 부족해 수락 스레드가
     * 사전 조회 전에 커밋을 만나면 사전 조회 단계에서 거절되므로, 이 헬퍼는 **거짓 실패를 만들지 않는다**
     * (탐지력만 떨어진다).
     */
    private fun acceptWhileStatusFlipsUnderLock(
        rawToken: String,
        user: User,
        newStatus: InvitationStatus,
    ): Throwable? {
        val tokenHash = SecureTokenGenerator.hashToken(rawToken)
        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutatorFailure = arrayOfNulls<Throwable>(1)

        val mutator =
            Thread {
                runCatching {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        val row = invitationRepository.findByTokenHashForUpdate(tokenHash)!!
                        row.status = newStatus.name
                        invitationRepository.saveAndFlush(row) // UPDATE 발행 — 행 잠금 확정.
                        lockHeld.countDown()
                        release.await(15, TimeUnit.SECONDS) // 커밋 지연.
                    }
                }.onFailure { mutatorFailure[0] = it }
            }
        mutator.isDaemon = true
        mutator.start()
        assertThat(lockHeld.await(15, TimeUnit.SECONDS)).isTrue()

        val acceptFailure = arrayOfNulls<Throwable>(1)
        val acceptor = Thread { acceptFailure[0] = runCatching { service.accept(rawToken, user) }.exceptionOrNull() }
        acceptor.isDaemon = true
        acceptor.start()

        Thread.sleep(800) // 수락 스레드가 사전 조회를 지나 FOR UPDATE 에서 블록되도록.
        release.countDown()
        mutator.join(20_000)
        acceptor.join(20_000)
        assertThat(acceptor.isAlive)
            .describedAs("accept() 가 20초 안에 끝나지 않았다 — 잠금 대기가 무한이거나 교착이다")
            .isFalse()
        mutatorFailure[0]?.let { throw AssertionError("상태 변경 스레드가 실패했다", it) }
        return acceptFailure[0]
    }

    /** 재발송 쿨다운을 지나게 하려고 PENDING 초대의 마지막 발송 시각(createdAt)을 과거로 돌린다. */
    private fun ageInvitation(
        orgId: UUID,
        email: String,
    ) {
        val inv = invitationRepository.findByOrgIdAndEmailAndStatus(orgId, email, InvitationStatus.PENDING.name)!!
        inv.createdAt = Instant.now().minus(Duration.ofHours(2))
        invitationRepository.save(inv)
    }
}
