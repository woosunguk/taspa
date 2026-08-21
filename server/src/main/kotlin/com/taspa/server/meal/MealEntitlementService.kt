package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.meal.dto.MealEntitlementView
import com.taspa.server.meal.dto.MealWindowView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 내 식대 자격 조회(읽기 전용).
 *
 * ★이 서비스의 존재 이유는 **승인과 같은 답을 미리 말하는 것**이다. 그래서 정책 해석은
 * `MealPolicyCalculus`(redeem 과 공유), 소진분 집계는 `countApprovedInWindow`·`sumOrgPaidInWindow`
 * (redeem 이 한도 판정에 쓰는 바로 그 쿼리)를 그대로 부른다. 여기서 조금이라도 다르게 계산하면
 * 화면이 "가능"이라고 한 결제가 계산대에서 거절된다.
 *
 * 부수효과 없음 — 발급도, 상태 변경도 하지 않는다(readOnly).
 */
@Service
class MealEntitlementService(
    private val organizationRepository: OrganizationRepository,
    private val membershipRepository: OrgMembershipRepository,
    private val policyResolver: MealPolicyResolver,
    private val transactionRepository: MealTransactionRepository,
) {
    @Transactional(readOnly = true)
    fun entitlement(
        orgId: UUID,
        userId: UUID,
    ): MealEntitlementView {
        val org =
            organizationRepository.findById(orgId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다")

        val now = Instant.now()
        val zone = MealPolicyCalculus.zoneOf(org.timezone)
        val localNow = ZonedDateTime.ofInstant(now, zone)

        // 정책 해석은 redeem 과 **같은 입구**를 쓴다. 폴백(정책 행 없음 → 코드 기본값)도 그 안에 있어서
        // 두 경로가 다른 기본값을 쓰는 일이 생길 수 없다.
        val scope =
            membershipRepository
                .findEligibilityView(orgId, userId)
                ?.let { MemberPolicyScope(it.getDepartmentId(), it.getSiteId()) }
                ?: MemberPolicyScope.ORG_DEFAULT
        val resolved = policyResolver.resolve(orgId, scope, localNow.toLocalDate())
        val policy = resolved.policy

        val (dayStart, dayEnd) = MealPolicyCalculus.dayBounds(localNow)
        val (monthStart, monthEnd) = MealPolicyCalculus.monthBounds(localNow)

        val todayCount = transactionRepository.countApprovedInWindow(userId, orgId, dayStart, dayEnd)
        val monthOrgPaid = transactionRepository.sumOrgPaidInWindow(userId, orgId, monthStart, monthEnd)
        val monthSelfPaid = transactionRepository.sumSelfPaidInWindow(userId, orgId, monthStart, monthEnd)
        val monthCount = transactionRepository.countApprovedInWindow(userId, orgId, monthStart, monthEnd)

        val current = MealPolicyCalculus.currentOccurrence(policy, localNow)
        val next = MealPolicyCalculus.nextOccurrence(policy, localNow)
        // redeem 의 두 거절 사유(MEAL_WINDOW_CLOSED, DAILY_MEAL_LIMIT)와 정확히 같은 조건이다.
        val dailyRemaining = (policy.dailyMealCount - todayCount).coerceAtLeast(0)

        return MealEntitlementView(
            orgId = orgId,
            orgName = org.name,
            timezone = org.timezone,
            serverNow = now,
            currentWindow = current?.let(::toView),
            nextWindow = next?.let(::toView),
            perMealLimitMinor = policy.perMealLimitMinor,
            dailyMealCount = policy.dailyMealCount,
            todayApprovedCount = todayCount,
            dailyRemaining = dailyRemaining,
            monthlyCapMinor = policy.monthlyCapMinor,
            monthOrgPaidMinor = monthOrgPaid,
            monthSelfPaidMinor = monthSelfPaid,
            // redeem 의 `monthRemaining` 과 같은 식(음수 절단) — 정책이 낮아져 이미 초과한 달도 0 으로 본다.
            monthRemainingMinor = (policy.monthlyCapMinor - monthOrgPaid).coerceAtLeast(0),
            monthApprovedCount = monthCount,
            periodStart = monthStart,
            periodEnd = monthEnd,
            dayStart = dayStart,
            dayEnd = dayEnd,
            canIssueNow = current != null && dailyRemaining > 0,
            perMealLimitSource = resolved.sourceOf(MealPolicyField.PER_MEAL_LIMIT).scope.name,
            dailyMealCountSource = resolved.sourceOf(MealPolicyField.DAILY_MEAL_COUNT).scope.name,
            monthlyCapSource = resolved.sourceOf(MealPolicyField.MONTHLY_CAP).scope.name,
            windowSource = resolved.sourceOf(MealPolicyField.LUNCH_WINDOW).scope.name,
        )
    }

    private fun toView(occurrence: MealPolicyCalculus.WindowOccurrence): MealWindowView =
        MealWindowView(
            window = occurrence.window.name,
            start = occurrence.start.toString(),
            end = occurrence.end.toString(),
            startsAt = occurrence.startsAt,
            endsAt = occurrence.endsAt,
        )
}
