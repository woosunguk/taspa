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
 * 멤버십 변경 이력(SCD, append-only). 멤버십이 생성/역할변경/배정/속성갱신/제거될 때마다 그 시점의
 * 멤버십 상태 스냅샷을 한 행으로 남긴다 — 예측 모집단 분해·정산 자격·과거 귀속에 시점별 소속을 재구성한다.
 *
 * 불변식: 절대 UPDATE/DELETE 하지 않는다(조직 삭제 CASCADE 만 예외). department_id/site_id 는 FK 가 없어
 * 배정 부서/사업장이 나중에 삭제돼도 스냅샷의 id 가 보존된다(이력 불변성).
 */
@Entity
@Table(name = "org_membership_history")
class MembershipHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "role", nullable = false, length = 24)
    val role: String,
    /** 스냅샷 시점의 구조적 부서 배정(FK 없음 — 이력 불변). */
    @Column(name = "department_id")
    val departmentId: UUID? = null,
    /** 스냅샷 시점의 구조적 사업장 배정(FK 없음 — 이력 불변). */
    @Column(name = "site_id")
    val siteId: UUID? = null,
    @Column(name = "employment_type", length = 24)
    val employmentType: String? = null,
    @Column(name = "employment_status", nullable = false, length = 24)
    val employmentStatus: String,
    @Column(name = "job_title", length = 120)
    val jobTitle: String? = null,
    @Column(name = "change_type", nullable = false, length = 32)
    val changeType: String,
    @Column(name = "recorded_at", nullable = false, updatable = false)
    val recordedAt: Instant = Instant.now(),
    /** 변경을 유발한 행위자(관리자). 로그인/JIT 자가 합류는 대상 본인 또는 null. */
    @Column(name = "recorded_by")
    val recordedBy: UUID? = null,
)
