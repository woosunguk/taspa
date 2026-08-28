package com.taspa.server.domain.org

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 부재 유형. 반차는 모수에서 절반만 빼므로 [MemberAbsence.weight] 와 함께 쓴다. */
enum class AbsenceType {
    ANNUAL_LEAVE,
    HALF_DAY,
    BUSINESS_TRIP,
    SICK,
    OTHER,
    ;

    companion object {
        fun parse(raw: String?): AbsenceType? = entries.firstOrNull { it.name == raw?.trim()?.uppercase() }

        /** 유형이 함의하는 기본 가중치. 반차만 0.5 다(한 끼는 먹는다). */
        fun defaultWeight(type: AbsenceType): BigDecimal = if (type == HALF_DAY) BigDecimal("0.50") else BigDecimal("1.00")
    }
}

/**
 * 하루 단위 부재(V39). 식수 예측의 **재실 모수**를 하루 단위로 정확하게 만드는 유일한 신호다.
 *
 * 장기 휴직과 다르다: 휴직은 `org_memberships.employment_status = ON_LEAVE` 이고 재실 집계가 이미
 * 제외한다. 이 테이블은 **재직 중인 사람이 그 날 없는 것**을 다룬다(연차·출장·병가).
 *
 * 행은 날짜별 한 건이고 `(org, user, date)` 가 유일하다 — HR 연동·CSV 재업로드가 모수를 두 번 깎지 않는다.
 */
@Entity
@Table(name = "org_member_absences")
class MemberAbsence(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "absence_date", nullable = false)
    val absenceDate: LocalDate,
    @Column(name = "type", nullable = false, length = 32)
    var type: String,
    @Column(name = "source", nullable = false, length = 32)
    var source: String = "MANUAL",
    @Column(name = "weight", nullable = false)
    var weight: BigDecimal = BigDecimal("1.00"),
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

interface MemberAbsenceRepository : JpaRepository<MemberAbsence, UUID> {
    fun findByOrgIdAndUserIdAndAbsenceDate(
        orgId: UUID,
        userId: UUID,
        absenceDate: LocalDate,
    ): MemberAbsence?

    fun findByOrgIdAndAbsenceDateBetweenOrderByAbsenceDateAscUserIdAsc(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<MemberAbsence>

    fun deleteByOrgIdAndUserIdAndAbsenceDate(
        orgId: UUID,
        userId: UUID,
        absenceDate: LocalDate,
    ): Long

    /**
     * 날짜별 부재 인원(가중 합). **예측이 쓰는 유일한 질의**다 — 구간을 한 번에 읽어 인메모리로 접는다
     * (날짜마다 물으면 백테스트 92일 창에서 왕복이 92번이 된다).
     *
     * ★재직 중인 사람만 센다. 퇴사자·정지된 멤버십의 부재 행이 남아 있어도 모수를 깎지 않는다 —
     *   그 사람은 이미 재실 집계에서 빠져 있으므로 두 번 빼면 모수가 음수 쪽으로 무너진다.
     */
    @Query(
        """
        SELECT a.absence_date AS d, SUM(a.weight) AS w
        FROM org_member_absences a
        JOIN org_memberships m ON m.org_id = a.org_id AND m.user_id = a.user_id
        WHERE a.org_id = :orgId
          AND a.absence_date BETWEEN :from AND :to
          AND m.status = 'ACTIVE'
          AND m.employment_status = 'EMPLOYED'
        GROUP BY a.absence_date
        """,
        nativeQuery = true,
    )
    fun sumWeightByDate(
        @Param("orgId") orgId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<Array<Any>>
}
