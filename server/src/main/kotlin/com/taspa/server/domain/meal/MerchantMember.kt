package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 가맹 직원 역할. 지금은 관리자 1종이며, 필요해지면 조회 전용 역할을 여기에 더한다. */
enum class MerchantRole { MERCHANT_ADMIN, }

enum class MerchantMemberStatus { ACTIVE, SUSPENDED }

/**
 * 가맹점의 **사람** 신원(V29). 결제 승인은 여전히 기계 전용이고(POS = M2M + merchant_id 클레임),
 * 이 멤버십은 자기 매장의 식수 로그·예측을 로그인해서 보는 조회 권한의 근거다.
 *
 * 조직 멤버십(org_memberships)과 독립이다 — 식당 사장이 어느 회사 직원일 필요는 없다.
 */
@Entity
@Table(name = "merchant_members")
class MerchantMember(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "merchant_id", nullable = false)
    val merchantId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "role", nullable = false, length = 24)
    var role: String = MerchantRole.MERCHANT_ADMIN.name,
    @Column(name = "status", nullable = false, length = 16)
    var status: String = MerchantMemberStatus.ACTIVE.name,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    /** 알 수 없는 값은 SUSPENDED 로 낙하 — 손상된 행이 500 이 아니라 **접근 거부**로 실패하게 한다. */
    fun statusEnum(): MerchantMemberStatus =
        MerchantMemberStatus.entries.firstOrNull { it.name == status } ?: MerchantMemberStatus.SUSPENDED

    /**
     * 알 수 없는 역할은 null — 즉 어떤 역할 검사도 통과하지 못한다. 지금은 역할이 하나뿐이라 사실상
     * 항상 MERCHANT_ADMIN 이지만, 조회 전용 역할이 추가되는 순간 "역할을 안 보던" 코드가 조용히
     * 권한을 넓히는 것을 막는 자리다.
     */
    fun roleEnum(): MerchantRole? = MerchantRole.entries.firstOrNull { it.name == role }
}
