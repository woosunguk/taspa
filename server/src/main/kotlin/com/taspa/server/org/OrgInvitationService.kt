package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.org.InvitationStatus
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgInvitation
import com.taspa.server.domain.org.OrgInvitationRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mail.MailService
import com.taspa.server.org.dto.InvitationView
import com.taspa.server.org.dto.MembershipRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 조직 초대 서비스 — 개인이 조직에 합류하는 표준 온보딩 경로.
 *
 * 보안 불변식(스펙 §보안):
 *  1) 토큰: 256bit 랜덤·**해시만 저장**·단일 사용·만료(기본 7d). 원문은 메일/URL 로만 노출된다.
 *  2) 수락 시 **currentUser.email(소문자) == invitation.email 강제**(하이재킹 차단) + 이메일 검증 세션 필수.
 *  3) 인가 격리(생성/목록/취소가 플랫폼 ADMIN 또는 그 org 의 활성 ORG_ADMIN 만)는 컨트롤러가 담당한다.
 *  4) 남용 방지: org·시간당 발송 상한, 미존재 이메일에도 동일 응답(계정 열거 금지), (org,email) PENDING 1건.
 *  5) 만료/취소/사용됨 토큰 거부, 이미 멤버 멱등.
 */
@Service
class OrgInvitationService(
    private val invitationRepository: OrgInvitationRepository,
    private val organizationRepository: OrganizationRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val departmentBinder: DepartmentBinder,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val mailService: MailService,
    private val auditEventService: AuditEventService,
    private val properties: OrgInvitationProperties,
    transactionManager: PlatformTransactionManager,
) {
    /**
     * REQUIRES_NEW: 만료 초대의 EXPIRED 전이를 accept()의 거부(예외 롤백)와 무관하게 독립 커밋한다.
     * accept()가 만료를 감지하면 예외로 롤백되는데, 그 안에서 상태를 바꾸면 전이도 함께 롤백된다 —
     * 이 별도 트랜잭션으로 전이만 먼저 확정하고 나서 거부를 던진다(만료 초대가 PENDING 으로 남는 것 방지).
     */
    private val newTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private companion object {
        /** org_invitations.department / org_memberships.department 컬럼 상한(VARCHAR(120)). */
        const val MAX_DEPARTMENT_LENGTH = 120
    }

    /** 수락 페이지 렌더용 미리보기(토큰 소비 없음). 토큰/해시는 절대 노출하지 않는다. */
    data class InvitationPreview(
        val orgName: String,
        val email: String,
        val role: String,
        val status: InvitationStatus,
        val expired: Boolean,
        /** 조직이 ACTIVE 인가 — false 면 수락 페이지가 버튼 대신 안내를 노출한다(정지 조직 합류 차단). */
        val orgActive: Boolean,
    )

    /**
     * 초대 생성. 이메일 정규화 → org ACTIVE 확인 → 이미 활성 멤버면 거부 → 남용 상한 확인 →
     * (org,email) PENDING 재사용/갱신 → rawToken 생성·해시 저장·만료 → 초대 메일 발송 → audit.
     *
     * ★계정 열거 금지: 초대 이메일이 taspa 계정을 가졌는지와 **무관하게** 동일하게 초대를 만들고 메일을
     *   보낸다(수신자가 이후 계정을 만들고 수락). "이미 이 조직의 멤버" 판정만 org-로컬 정보로 거부한다.
     */
    @Transactional
    fun invite(
        orgId: UUID,
        email: String,
        role: String?,
        department: String?,
        inviterId: UUID?,
        /**
         * 구조적 부서 배정. **마지막 파라미터**인 데는 이유가 있다 — `inviterId` 앞에 두면 기존
         * 위치 인자 호출이 둘 다 `UUID?` 라 조용히 잘못 바인딩된다(초대자 id 가 부서 id 로 들어간다).
         * 컴파일러가 잡아 주지 않는 종류의 실수라 순서로 막는다.
         */
        departmentId: UUID? = null,
        /**
         * 호출자가 **ORG_ADMIN 승격 권한**(`org:ChangeMemberRole`)을 갖고 있는가.
         *
         * ★기본값이 `false` 인 것이 이 파라미터의 요점이다. 초대는 수락 시 그대로 멤버십 역할이 되므로
         * `role=ORG_ADMIN` 초대는 **역할 변경과 같은 능력**이다. 그런데 인가는 `org:CreateInvitation`
         * 하나만 보고 있었다 — 커스텀 역할에 '초대 보내기'를 준 순간(HR 담당에게 지극히 자연스럽다)
         * 그 사람이 자기가 통제하는 두 번째 주소를 ORG_ADMIN 으로 초대해 **조직 전체 권한을 스스로
         * 획득**할 수 있었다(자기 주소는 '이미 멤버'로 막히지만 주소 하나면 충분하다).
         * 부서 위임(V34)이 같은 이유로 초대 action 을 명시 Deny 한 것과 같은 함정이다.
         */
        mayGrantOrgAdmin: Boolean = false,
    ): InvitationView {
        val org = findActiveOrg(orgId)
        if (parseRole(role) == OrgRole.ORG_ADMIN && !mayGrantOrgAdmin) {
            throw AuthException(
                ErrorCode.FORBIDDEN,
                "조직관리자로 초대하려면 구성원 역할 변경 권한이 필요합니다",
            )
        }
        val normalizedEmail = normalizeEmail(email)
        val parsedRole = parseRole(role)
        val dept = normalizeDepartment(department)
        // 구조적 부서 배정 — 이게 있어야 초대로 입사한 사람이 그 부서의 식대 정책을 받는다.
        // 라벨만 있고 이름이 모호하면(같은 이름이 여럿) 잇지 않는다(DepartmentBinder 주석 참고).
        val deptId = departmentBinder.resolve(orgId, departmentId, dept)

        // 이미 이 org 의 활성 멤버면 중복 초대 거부(org-로컬 정보 — 전역 계정 존재는 노출하지 않는다).
        val existingUser = userRepository.findByEmail(normalizedEmail)
        if (existingUser?.id != null &&
            membershipRepository.findByOrgIdAndUserId(orgId, existingUser.id!!)?.statusEnum() == MembershipStatus.ACTIVE
        ) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이미 이 조직의 멤버입니다")
        }

        val now = Instant.now()

        // 남용 방지 ① — org·시간당 신규(distinct) 초대 상한(행 무한 증식 차단).
        val hourAgo = now.minus(Duration.ofHours(1))
        if (invitationRepository.countByOrgIdAndCreatedAtAfter(orgId, hourAgo) >= properties.maxPerHour) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "시간당 초대 발송 한도를 초과했습니다. 잠시 후 다시 시도하세요")
        }

        // (org,email) PENDING 1건 제약 준수 — 기존 PENDING 이 있으면 재사용/갱신(새 토큰·새 만료).
        val existing = invitationRepository.findByOrgIdAndEmailAndStatus(orgId, normalizedEmail, InvitationStatus.PENDING.name)

        // 남용 방지 ② — 동일 (org,email) 재발송 쿨다운. 재초대는 PENDING 1건을 재사용하므로 ①의 행 수
        // 카운트만으로는 같은 주소로의 반복 발송(이메일 폭탄)을 막지 못한다. createdAt 은 발송마다 now 로
        // 갱신돼 '마지막 발송 시각'과 같으므로, 이 값이 쿨다운 안이면 재발송을 거부해 발송 빈도를 상한한다.
        if (existing != null) {
            val cooldownAgo = now.minus(Duration.ofSeconds(properties.resendCooldownSeconds))
            if (existing.createdAt.isAfter(cooldownAgo)) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "방금 이 주소로 초대를 보냈습니다. 잠시 후 다시 시도하세요")
            }
        }

        val invitation =
            if (existing != null) {
                existing.apply {
                    this.role = parsedRole.name
                    this.department = dept
                    this.departmentId = deptId
                    this.invitedBy = inviterId
                }
            } else {
                OrgInvitation(
                    orgId = orgId,
                    email = normalizedEmail,
                    role = parsedRole.name,
                    department = dept,
                    departmentId = deptId,
                    tokenHash = "", // reissueToken() 이 아래에서 즉시 채운다(save 전).
                    invitedBy = inviterId,
                    createdAt = now,
                    expiresAt = now,
                )
            }
        // 토큰 회전 + 만료 리셋 — 신규/기존 PENDING 재사용 공통. resend() 와 공유한다.
        val rawToken = reissueToken(invitation, now)
        val saved = invitationRepository.save(invitation)

        val acceptUrl = "${properties.baseUrl.trimEnd('/')}/orgs/invite/accept?token=$rawToken"
        // ★메일은 트랜잭션 커밋 이후에 보낸다(리뷰 집중 항목 5). 이렇게 하면 (a) SMTP I/O 동안 DB 커넥션을
        //   점유하지 않고(커넥션 풀 고갈 방지), (b) 롤백된 초대(재초대 경합·커밋 오류)에 대해 DB 에 없는
        //   토큰의 '죽은 링크' 메일이 나가지 않는다 — 영속 커밋된 초대에 대해서만 발송한다.
        sendInvitationAfterCommit(normalizedEmail, org.name, acceptUrl, saved.expiresAt)
        auditEventService.record(
            "ORG_INVITE_CREATED",
            inviterId,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "email" to normalizedEmail,
                "role" to parsedRole.name,
                "invitationId" to saved.id.toString(),
            ),
        )
        return InvitationView.from(saved)
    }

    /**
     * 초대 수락(사용자 대면). 토큰 해시 비관적 잠금 조회 → PENDING·미만료 검증 → **이메일 일치 강제**(하이재킹
     * 차단) → 이메일 검증 세션 확인 → org ACTIVE → org_memberships upsert(role·department) → ACCEPTED(단일 사용)
     * → audit. 이미 멤버면 초대 역할이 상위일 때만 승격을 반영하고(강등 금지), 그 외에는 멤버십을 그대로 둔다
     * (멱등 성공). 초대는 항상 ACCEPTED 로 소비된다.
     */
    @Transactional
    fun accept(
        rawToken: String,
        currentUser: User,
    ): InvitationView {
        val tokenHash = SecureTokenGenerator.hashToken(rawToken)

        // ★만료 판정을 **잠금 획득보다 먼저** 한다 — 순서가 뒤바뀌면 자기 교착이 난다.
        //
        //   예전 구현은 FOR UPDATE 로 잠근 뒤 만료를 발견하면 REQUIRES_NEW 안에서 그 행을 UPDATE 했다.
        //   그 UPDATE 는 **다른 커넥션**이라 바깥 트랜잭션이 쥔 행 잠금을 기다리는데, 바깥은 안쪽 JDBC
        //   호출이 돌아오기를 기다린다 — 서로를 기다리며 영원히 멈춘다. PostgreSQL 의 교착 탐지기는
        //   발동하지 않는다: DB 가 보기에 대기자는 안쪽 하나뿐이고 바깥은 DB 잠금이 아니라 애플리케이션
        //   스레드를 기다리므로 잠금 그래프에 순환이 없다. lock_timeout 도 없어 무한 대기가 된다.
        //   운영에서는 만료된 초대 링크를 클릭할 때마다 톰캣 워커 스레드가 하나씩 영구히 묶인다.
        //
        //   그래서 잠그지 않은 조회로 먼저 걸러 낸다. 이 조회는 READ COMMITTED 에서 행 잠금을 잡지 않으므로
        //   아래 REQUIRES_NEW 전이가 막히지 않는다. 단일 사용 보장은 그대로다 — 아래에서 다시 FOR UPDATE
        //   로 읽고 PENDING 을 **재확인**하는 쪽이 권위이고, 여기 조회는 빠른 사전 판정일 뿐이다.
        //
        // ★그 재확인이 권위이려면 이 사전 조회가 **엔티티여서는 안 된다**(프로젝션인 이유 —
        //   findAcceptGateByTokenHash KDoc). 엔티티로 읽으면 1차 캐시 때문에 아래 FOR UPDATE 가
        //   옛 스냅샷을 돌려주고, 재확인이 그 사이 커밋된 ACCEPTED/REVOKED 를 통과시킨다.
        val gate =
            invitationRepository.findAcceptGateByTokenHash(tokenHash)
                ?: throw AuthException(ErrorCode.INVITATION_INVALID)
        if (gate.status != InvitationStatus.PENDING.name) {
            throw AuthException(ErrorCode.INVITATION_INVALID)
        }
        if (Instant.now().isAfter(gate.expiresAt)) {
            // 전이는 별도 트랜잭션(REQUIRES_NEW)에서 확정한다 — 이 메서드가 예외로 롤백돼도 전이는 남는다.
            // 이 시점에 바깥 트랜잭션은 그 행에 아무 잠금도 갖고 있지 않다(위 조회가 무잠금).
            val invitationId = gate.id
            newTransaction.executeWithoutResult {
                invitationRepository.findById(invitationId).ifPresent {
                    if (it.statusEnum() == InvitationStatus.PENDING) {
                        it.status = InvitationStatus.EXPIRED.name
                        invitationRepository.save(it)
                    }
                }
            }
            throw AuthException(ErrorCode.INVITATION_EXPIRED)
        }

        // 비관적 쓰기 잠금으로 재조회 — 같은 토큰의 동시 수락(더블클릭)을 직렬화한다. 뒤늦은 요청은 잠금 해제
        // 후 이미 ACCEPTED 로 소비된 초대를 보고 아래 PENDING 검사에서 INVITATION_INVALID 로 거절돼,
        // org_memberships UNIQUE 충돌(→500)이나 중복 멤버십 없이 정확히 한 번만 소비된다(단일 사용 경합 방지).
        // ★상태 재확인이 여기 있어야 하는 이유: 위 무잠금 조회 이후 다른 요청이 소비했을 수 있다.
        val invitation =
            invitationRepository.findByTokenHashForUpdate(tokenHash)
                ?: throw AuthException(ErrorCode.INVITATION_INVALID)
        if (invitation.statusEnum() != InvitationStatus.PENDING) {
            throw AuthException(ErrorCode.INVITATION_INVALID)
        }
        // 잠금 대기 중에 만료 경계를 넘겼을 수 있다 — 그때는 전이 없이 거부만 한다(전이는 스케줄 잡이 맡는다).
        // 여기서 다시 REQUIRES_NEW 를 열면 정확히 위에서 설명한 교착이 재현된다.
        if (invitation.isExpired()) {
            throw AuthException(ErrorCode.INVITATION_EXPIRED)
        }

        // ★이메일 일치 강제 — a@x 초대를 b@y 가 수락 불가(하이재킹 차단). invitation.email 은 정규화(소문자)돼 있다.
        val userEmail = currentUser.email.trim().lowercase()
        if (userEmail != invitation.email) {
            throw AuthException(ErrorCode.INVITATION_EMAIL_MISMATCH)
        }
        // 인증+이메일검증 세션 필수(방어적 재확인 — 컨트롤러 게이트와 이중화).
        if (!currentUser.emailVerified) {
            throw AuthException(ErrorCode.FORBIDDEN, "이메일 인증을 먼저 완료하세요")
        }

        findActiveOrg(invitation.orgId) // org 존재·ACTIVE 확인(정지 조직 합류 차단).
        val userId = currentUser.id!!

        // 멤버십 upsert(스펙 role·department) — 강등만 차단하는 '승격 반영' 멱등.
        //  - 신규: 초대 역할로 멤버십 생성.
        //  - 이미 멤버 + 초대 역할이 상위(예: MEMBER→ORG_ADMIN): 역할·부서를 반영(승격). 스펙 upsert(role) 준수.
        //  - 이미 멤버 + 초대 역할이 동일/하위: no-op(강등하지 않음 — 마지막 관리자 보호와 정합, 멱등).
        val existingMembership = membershipRepository.findByOrgIdAndUserId(invitation.orgId, userId)
        if (existingMembership == null || isPromotion(existingMembership.roleEnum(), invitation.roleEnum())) {
            organizationService.upsertMember(
                invitation.orgId,
                MembershipRequest(
                    userId = userId,
                    role = invitation.role,
                    department = invitation.department,
                    departmentId = invitation.departmentId,
                ),
                actorId = userId,
            )
        }

        invitation.status = InvitationStatus.ACCEPTED.name
        invitation.acceptedAt = Instant.now()
        invitation.acceptedBy = userId
        val saved = invitationRepository.save(invitation)
        auditEventService.record(
            "ORG_INVITE_ACCEPTED",
            userId,
            invitation.orgId,
            mapOf("orgId" to invitation.orgId.toString(), "invitationId" to saved.id.toString()),
        )
        return InvitationView.from(saved)
    }

    /** 초대 취소(PENDING → REVOKED). 타 org id 로는 조회되지 않아 org 격리가 강제된다(404). */
    @Transactional
    fun revoke(
        orgId: UUID,
        invitationId: UUID,
        actorId: UUID?,
    ) {
        val invitation =
            invitationRepository.findByIdAndOrgId(invitationId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "초대를 찾을 수 없습니다")
        if (invitation.statusEnum() != InvitationStatus.PENDING) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이미 처리된 초대입니다")
        }
        invitation.status = InvitationStatus.REVOKED.name
        invitationRepository.save(invitation)
        auditEventService.record(
            "ORG_INVITE_REVOKED",
            actorId,
            orgId,
            mapOf("orgId" to orgId.toString(), "invitationId" to invitationId.toString()),
        )
    }

    /**
     * 초대 재발송(PENDING 전용). 원문 토큰은 저장하지 않으므로(해시만) 재발송에는 **새 토큰이 필수**다 —
     * 토큰을 회전하고 createdAt/expiresAt 를 리셋한 뒤 커밋 후 초대 메일을 다시 보낸다. 격리/거부 규칙은
     * invite()·revoke() 와 정합: 타 org id 는 404(org 격리), 비PENDING 은 400, 정지 조직 차단, 재발송 쿨다운.
     */
    @Transactional
    fun resend(
        orgId: UUID,
        invitationId: UUID,
        actorId: UUID?,
    ): InvitationView {
        // 비관적 쓰기 잠금으로 조회 — 동시 accept() 와의 lost-update 를 직렬화한다. 잠금 없이 읽으면
        // resend 가 관측한 PENDING 스냅샷으로 blind save 하여 그 사이 소비된(ACCEPTED) 초대를 되살릴 수
        // 있다(단일 사용 위반). 잠금 후 재검사로 이미 처리된 초대는 아래에서 400 으로 거절된다.
        val invitation =
            invitationRepository.findByIdAndOrgIdForUpdate(invitationId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "초대를 찾을 수 없습니다")
        if (invitation.statusEnum() != InvitationStatus.PENDING) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이미 처리된 초대입니다")
        }
        val org = findActiveOrg(orgId) // 정지 조직 재발송 차단.

        val now = Instant.now()
        // 재발송 쿨다운 — invite() 와 동일 메시지/키(단일 주소 이메일 폭탄 방지). createdAt 은 마지막 발송 시각.
        val cooldownAgo = now.minus(Duration.ofSeconds(properties.resendCooldownSeconds))
        if (invitation.createdAt.isAfter(cooldownAgo)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "방금 이 주소로 초대를 보냈습니다. 잠시 후 다시 시도하세요")
        }

        val rawToken = reissueToken(invitation, now)
        val saved = invitationRepository.save(invitation)

        val acceptUrl = "${properties.baseUrl.trimEnd('/')}/orgs/invite/accept?token=$rawToken"
        sendInvitationAfterCommit(saved.email, org.name, acceptUrl, saved.expiresAt)
        auditEventService.record(
            "ORG_INVITE_RESENT",
            actorId,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "email" to saved.email,
                "invitationId" to saved.id.toString(),
            ),
        )
        return InvitationView.from(saved)
    }

    /** 조직의 PENDING 초대 목록. 조회 시 만료분을 lazy 전이해 만료 초대가 목록에 남지 않게 한다. */
    @Transactional
    fun listPending(orgId: UUID): List<InvitationView> {
        findOrg(orgId) // 존재 검증(없으면 404).
        invitationRepository.expireOverdue(Instant.now())
        return invitationRepository
            .findByOrgIdAndStatusOrderByCreatedAtDesc(orgId, InvitationStatus.PENDING.name)
            .map { InvitationView.from(it) }
    }

    /** RetentionCleanupJob 훅 — 만료 시각이 지난 PENDING 을 EXPIRED 로 일괄 전이한다. 반환은 전이 행 수. */
    @Transactional
    fun expireOverdue(): Int = invitationRepository.expireOverdue(Instant.now())

    /** 수락 페이지 렌더용 미리보기(토큰 소비 없음). 존재하지 않으면 null. */
    @Transactional(readOnly = true)
    fun preview(rawToken: String): InvitationPreview? {
        val invitation = invitationRepository.findByTokenHash(SecureTokenGenerator.hashToken(rawToken)) ?: return null
        val org = organizationRepository.findById(invitation.orgId).orElse(null)
        return InvitationPreview(
            orgName = org?.name ?: "",
            email = invitation.email,
            role = invitation.role,
            status = invitation.statusEnum(),
            expired = invitation.isExpired(),
            orgActive = org?.statusEnum() == OrgStatus.ACTIVE,
        )
    }

    // ---- 내부 ----

    private fun findOrg(orgId: UUID): Organization =
        organizationRepository.findById(orgId).orElse(null)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")

    private fun findActiveOrg(orgId: UUID): Organization {
        val org = findOrg(orgId)
        if (org.statusEnum() != OrgStatus.ACTIVE) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "정지된 조직에는 초대를 처리할 수 없습니다")
        }
        return org
    }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이메일을 입력하세요")
        }
        if (normalized.length > User.MAX_EMAIL_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이메일이 너무 깁니다")
        }
        if (!normalized.contains("@") || normalized.startsWith("@") || normalized.endsWith("@")) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "유효한 이메일 주소가 아닙니다")
        }
        return normalized
    }

    private fun parseRole(value: String?): OrgRole {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return OrgRole.MEMBER
        return OrgRole.entries.firstOrNull { it.name == raw.uppercase() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "role 은 MEMBER 또는 ORG_ADMIN 이어야 합니다")
    }

    /**
     * 부서명 정규화·검증. 공백 제거 후 비면 null. 컬럼 상한(VARCHAR(120))을 넘으면 flush 시점의
     * DataIntegrityViolation(→모호한 409) 대신 초대 생성 시점에 명시적 VALIDATION_ERROR(400)로 조기 거부한다.
     */
    private fun normalizeDepartment(department: String?): String? {
        val dept = department?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (dept.length > MAX_DEPARTMENT_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "부서명은 ${MAX_DEPARTMENT_LENGTH}자를 넘을 수 없습니다")
        }
        return dept
    }

    /** 초대 역할이 현재 역할보다 상위(승격)인가. MEMBER(0) < ORG_ADMIN(1). 강등은 반영하지 않는다. */
    private fun isPromotion(
        current: OrgRole,
        invited: OrgRole,
    ): Boolean = roleRank(invited) > roleRank(current)

    private fun roleRank(role: OrgRole): Int =
        when (role) {
            OrgRole.MEMBER -> 0
            OrgRole.ORG_ADMIN -> 1
        }

    /**
     * rawToken 을 새로 발급해 해시로 교체하고 createdAt(마지막 발송 시각)·expiresAt(만료)를 리셋한다.
     * 원문 토큰은 저장하지 않으므로(해시만) 발송마다 새 토큰이 필요하다 — invite()(신규·기존 PENDING 재사용)와
     * resend() 가 공유한다. 새 원문 토큰을 반환하며 저장(save)·메일·audit 은 호출부 책임이다.
     */
    private fun reissueToken(
        invitation: OrgInvitation,
        now: Instant,
    ): String {
        val rawToken = SecureTokenGenerator.generateToken()
        invitation.tokenHash = SecureTokenGenerator.hashToken(rawToken)
        invitation.createdAt = now
        invitation.expiresAt = now.plus(Duration.ofDays(properties.expiryDays))
        return rawToken
    }

    /**
     * 초대 메일을 트랜잭션 커밋 이후에 발송한다(트랜잭션/커넥션 점유 회피 + 커밋된 초대만 발송).
     * 활성 트랜잭션이 없으면(방어적) 즉시 발송한다.
     */
    private fun sendInvitationAfterCommit(
        email: String,
        orgName: String,
        acceptUrl: String,
        expiresAt: Instant,
    ) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        mailService.sendOrgInvitation(email, orgName, acceptUrl, expiresAt)
                    }
                },
            )
        } else {
            mailService.sendOrgInvitation(email, orgName, acceptUrl, expiresAt)
        }
    }
}
