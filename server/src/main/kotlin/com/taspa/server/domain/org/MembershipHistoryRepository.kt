package com.taspa.server.domain.org

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MembershipHistoryRepository : JpaRepository<MembershipHistory, UUID> {
    /** 한 org 의 한 사용자 멤버십 이력을 최신순으로 반환한다(콘솔 이력 뷰). org 격리는 orgId 조건이 강제한다. */
    fun findByOrgIdAndUserIdOrderByRecordedAtDesc(
        orgId: UUID,
        userId: UUID,
    ): List<MembershipHistory>

    /** 대시보드: 특정 변경 유형(JOINED 등)의 최근 스냅샷 수(카운트 쿼리 — 행 로드 금지). org 격리는 orgId 조건. */
    fun countByOrgIdAndChangeTypeAndRecordedAtAfter(
        orgId: UUID,
        changeType: String,
        recordedAt: Instant,
    ): Long

    /**
     * 과거 시점 재실 인원 재구성(식수예측 P0 재실모수) — :asOf 이전 마지막 스냅샷(DISTINCT ON user_id,
     * recorded_at DESC)이 "제거 아님(change_type≠REMOVED) ∧ 재직(EMPLOYED)"인 사용자 수를 센다.
     * 근사 한계(의도된 P0 단순화): 멤버십 SUSPENDED 상태는 이력 스냅샷에 없어(컬럼 부재) 구분하지 못하고,
     * SCD 훅 도입 이전에 생긴 멤버십은 이력이 없어 누락된다 — 호출부는 0 을 "복원 불가"로 보고 비율 보정을
     * 생략한다(method=SEASONAL_NAIVE 로 구분). append-only 라 과거 시점 판정은 안정적이다.
     */
    @Query(
        value = """
            SELECT COUNT(*) FROM (
                SELECT DISTINCT ON (user_id) change_type, employment_status
                FROM org_membership_history
                WHERE org_id = :orgId AND recorded_at <= :asOf
                ORDER BY user_id, recorded_at DESC
            ) latest
            WHERE latest.change_type <> 'REMOVED' AND latest.employment_status = 'EMPLOYED'
        """,
        nativeQuery = true,
    )
    fun countActiveEmployedAsOf(
        @Param("orgId") orgId: UUID,
        @Param("asOf") asOf: Instant,
    ): Long

    /**
     * 배치 복원 입력(식수예측 HeadcountLookup.preload) — :recordedAt 이전 org 이력 스냅샷 전체를 시간순으로
     * 반환한다. 날짜별 countActiveEmployedAsOf 반복(백테스트 92일 창 = 최대 99회 순차 정렬 스캔) 대신
     * 호출부가 1회 로드 후 인메모리 스위프로 날짜별 카운트를 전개한다. 동시각 스냅샷은 id 로 안정 정렬.
     * org 격리는 orgId 조건이 강제한다.
     */
    fun findByOrgIdAndRecordedAtLessThanEqualOrderByRecordedAtAscIdAsc(
        orgId: UUID,
        recordedAt: Instant,
    ): List<MembershipHistory>
}
