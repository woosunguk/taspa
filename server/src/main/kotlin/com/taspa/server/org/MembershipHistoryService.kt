package com.taspa.server.org

import com.taspa.server.domain.org.MembershipChangeType
import com.taspa.server.domain.org.MembershipHistory
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.OrgMembership
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 멤버십 변경 이력(SCD) 기록기. OrganizationService 의 생성/역할변경/배정/속성갱신/제거 훅에서 호출된다.
 *
 * record 는 멤버십의 **현재 전체 상태 스냅샷**을 append 한다(부분 diff 가 아님 — 재구성 없이 각 시점의 완전한
 * 소속/역할/재직 상태를 그대로 복원할 수 있게). 예외를 삼키지 않는다: 같은 트랜잭션 안에서 정상 기록돼야 하며
 * 실패는 본 변경과 함께 롤백되는 편이 이력 정합성에 옳다(감사 로그와 달리 이력은 정답데이터라 유실 불가).
 */
@Service
class MembershipHistoryService(
    private val historyRepository: MembershipHistoryRepository,
) {
    @Transactional
    fun record(
        membership: OrgMembership,
        changeType: MembershipChangeType,
        actorId: UUID?,
    ) {
        historyRepository.save(
            MembershipHistory(
                orgId = membership.orgId,
                userId = membership.userId,
                role = membership.role,
                departmentId = membership.departmentId,
                siteId = membership.siteId,
                employmentType = membership.employmentType,
                employmentStatus = membership.employmentStatus,
                jobTitle = membership.jobTitle,
                changeType = changeType.name,
                recordedBy = actorId,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun listHistory(
        orgId: UUID,
        userId: UUID,
    ): List<MembershipHistory> = historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgId, userId)
}
