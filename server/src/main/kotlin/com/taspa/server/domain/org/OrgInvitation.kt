package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 조직 초대. 관리자/조직관리자가 이메일로 발송하고, 수신자가 토큰 링크로 수락하면 org_memberships 가 생긴다.
 *
 * 보안 불변식:
 *  - token_hash 만 저장한다(원문은 메일 링크로만 전달, SecureTokenGenerator 256bit → SHA-256).
 *  - email 은 소문자 정규화(수락 시 currentUser.email 과 정확 일치 강제 — 하이재킹 차단).
 *  - PENDING 만 수락 가능. 수락 시 ACCEPTED 로 전이해 단일 사용을 강제한다.
 */
@Entity
@Table(name = "org_invitations")
class OrgInvitation(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "email", nullable = false, length = 100)
    var email: String,
    @Column(name = "role", nullable = false, length = 24)
    var role: String = OrgRole.MEMBER.name,
    @Column(name = "department", length = 120)
    var department: String? = null,
    /**
     * **구조적** 부서 배정(departments.id). 위 [department] 자유 텍스트 라벨과 별개다.
     *
     * 이 값이 있어야 초대로 입사한 사람이 그 부서의 식대 정책 재정의를 받는다. 라벨만으로는 화면에
     * 부서 이름이 보일 뿐, 정책 해석기가 보는 `org_memberships.department_id` 는 여전히 NULL 이다.
     */
    @Column(name = "department_id")
    var departmentId: UUID? = null,
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    var tokenHash: String,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = InvitationStatus.PENDING.name,
    @Column(name = "invited_by")
    var invitedBy: UUID? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,
    @Column(name = "accepted_by")
    var acceptedBy: UUID? = null,
) {
    fun statusEnum(): InvitationStatus = InvitationStatus.valueOf(status)

    fun roleEnum(): OrgRole = OrgRole.valueOf(role)

    fun isExpired(now: Instant = Instant.now()): Boolean = now.isAfter(expiresAt)
}
