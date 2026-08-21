package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealPolicyRevision
import com.taspa.server.domain.meal.MealPolicyRevisionRepository
import com.taspa.server.domain.meal.PolicyChangeType
import com.taspa.server.domain.meal.PolicyScope
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.meal.dto.MealPolicyRevisionView
import com.taspa.server.meal.dto.MealPolicyUpdateRequest
import com.taspa.server.meal.dto.MealPolicyView
import com.taspa.server.org.OrganizationService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 조직 식대 정책 조회·편집·이력.
 *
 * 이 서비스가 생기기 전까지 12,000원/1일1회/월20만원은 `MealPolicy` 엔티티의 Kotlin 기본값으로 사실상
 * 하드코딩돼 있었고, 조직관리자는 자기 회사 한도조차 바꿀 수 없었다(정책 행을 `save` 하는 프로덕션
 * 코드가 **한 곳도 없었다**). 여기가 그 손잡이다.
 *
 * ★**잠금을 쓰지 않는다.** 사람이 설정 화면에서 누르는 버튼이고, 마지막 저장이 이기면 그만이다.
 * 오히려 FOR UPDATE 를 넣으면 CLAUDE.md 의 불변식 ②("잠금이 그 트랜잭션의 첫 적재가 아니면 재확인이
 * 옛 값")에 정확히 걸린다 — 여기서는 같은 트랜잭션에서 엔티티를 먼저 읽어야 이력에 남길 이전 값을
 * 알 수 있기 때문이다.
 */
@Service
class MealPolicyService(
    private val policyRepository: MealPolicyRepository,
    private val revisionRepository: MealPolicyRevisionRepository,
    private val policyResolver: MealPolicyResolver,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val properties: MealProperties,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun read(orgId: UUID): MealPolicyView {
        val org = organizationService.requireOrg(orgId)
        val stored = policyRepository.findById(orgId).orElse(null)
        val zone = MealPolicyCalculus.zoneOf(org.timezone)
        val resolved = policyResolver.resolveOrgDefault(orgId, ZonedDateTime.now(zone).toLocalDate())
        return toView(orgId, org.timezone, resolved.policy, usingDefaults = stored == null, updatedAt = stored?.updatedAt)
    }

    /**
     * 정책 저장. 검증을 통과한 값만 반영하고, 반영 후 전체 스냅샷을 이력에 append 한다.
     *
     * @param actorIsOrgMember 행위자가 이 조직 사람인지. 플랫폼 운영자의 변경이면 false 로 기록해
     *   조직이 "우리가 안 바꿨는데 한도가 달라졌다"를 사후에 가려낼 수 있게 한다.
     */
    @Transactional
    fun update(
        orgId: UUID,
        request: MealPolicyUpdateRequest,
        actorId: UUID?,
        actorIsOrgMember: Boolean,
    ): MealPolicyView {
        val org = organizationService.requireOrg(orgId)
        val values = validate(request)

        val existing = policyRepository.findById(orgId).orElse(null)
        val changeType = if (existing == null) PolicyChangeType.CREATED else PolicyChangeType.UPDATED
        val policy = existing ?: MealPolicy(orgId = orgId)
        policy.perMealLimitMinor = values.perMealLimitMinor
        policy.dailyMealCount = values.dailyMealCount
        policy.monthlyCapMinor = values.monthlyCapMinor
        policy.breakfastStart = values.breakfastStart
        policy.breakfastEnd = values.breakfastEnd
        policy.lunchStart = values.lunchStart
        policy.lunchEnd = values.lunchEnd
        policy.dinnerStart = values.dinnerStart
        policy.dinnerEnd = values.dinnerEnd
        // 신규 행은 @PreUpdate 가 돌지 않으므로 저장 시각을 명시한다.
        policy.updatedAt = java.time.Instant.now()
        val saved = policyRepository.save(policy)

        revisionRepository.save(
            MealPolicyRevision(
                orgId = orgId,
                scopeType = PolicyScope.ORG.name,
                changeType = changeType.name,
                document = objectMapper.writeValueAsString(snapshotOf(values)),
                actorIsOrgMember = actorIsOrgMember,
                recordedBy = actorId,
            ),
        )
        return toView(orgId, org.timezone, saved, usingDefaults = false, updatedAt = saved.updatedAt)
    }

    /** 정책 변경 이력 최근순. 행위자 이메일은 한 번에 묶어 조회한다(N+1 방지). */
    @Transactional(readOnly = true)
    fun history(
        orgId: UUID,
        limit: Int,
        offset: Int,
    ): List<MealPolicyRevisionView> {
        val size = limit.coerceIn(1, MAX_HISTORY_LIMIT)
        val page = (offset / size).coerceAtLeast(0)
        val revisions = revisionRepository.findByOrgIdOrderByRecordedAtDesc(orgId, PageRequest.of(page, size))
        val emails =
            userRepository
                .findAllById(revisions.mapNotNull { it.recordedBy }.distinct())
                .associateBy({ it.id!! }, { it.email })
        return revisions.map {
            MealPolicyRevisionView(
                id = it.id!!,
                scopeType = it.scopeType,
                scopeLabel = it.scopeLabel,
                changeType = it.changeType,
                document = it.document,
                actorIsOrgMember = it.actorIsOrgMember,
                // 플랫폼 운영자의 이메일은 조직에 노출하지 않는다(org 활동로그의 마스킹 규약과 같은 이유).
                actorEmail = if (it.actorIsOrgMember) it.recordedBy?.let(emails::get) else null,
                recordedAt = it.recordedAt,
            )
        }
    }

    /**
     * 입력 검증.
     *
     * ★자정을 넘는 창(start >= end)은 **명시적으로 거절**한다. 조용히 받아 두면
     * `MealPolicyCalculus.openWindows` 의 `start < end` 필터가 그 창을 후보에서 빼 버려, 사용자에게는
     * "저장은 됐는데 점심시간이 사라졌다"로 나타난다. 저장 실패가 조용한 소실보다 낫다.
     * (야간 근무 조직의 자정 넘는 끼니창은 별도 설계가 필요하다 — 지금 구조로는 표현할 수 없다.)
     */
    private fun validate(request: MealPolicyUpdateRequest): EffectiveMealPolicy {
        val ceiling = properties.policyCeiling
        if (request.perMealLimitMinor < 0 || request.perMealLimitMinor > ceiling.perMealLimitMinor) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "1식 한도는 0 이상 ${ceiling.perMealLimitMinor} 이하여야 합니다",
            )
        }
        if (request.dailyMealCount < 1 || request.dailyMealCount > ceiling.dailyMealCount) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "1일 횟수는 1 이상 ${ceiling.dailyMealCount} 이하여야 합니다",
            )
        }
        if (request.monthlyCapMinor < 0 || request.monthlyCapMinor > ceiling.monthlyCapMinor) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "월 한도는 0 이상 ${ceiling.monthlyCapMinor} 이하여야 합니다",
            )
        }
        val windows =
            listOf(
                Triple("아침", request.breakfastStart, request.breakfastEnd),
                Triple("점심", request.lunchStart, request.lunchEnd),
                Triple("저녁", request.dinnerStart, request.dinnerEnd),
            ).map { (label, rawStart, rawEnd) ->
                val start = parseTime(label, "시작", rawStart)
                val end = parseTime(label, "종료", rawEnd)
                if (start >= end) {
                    throw AuthException(
                        ErrorCode.VALIDATION_ERROR,
                        "$label 시간은 시작이 종료보다 빨라야 합니다(자정을 넘는 끼니창은 지원하지 않습니다)",
                    )
                }
                Triple(label, start, end)
            }
        // 창이 겹치면 resolveWindow 의 breakfast → lunch → dinner 순서 평가에서 앞선 창이 항상 이겨
        // 뒤 창이 사실상 존재하지 않게 된다 — 사용자가 의도한 설정이 아니므로 여기서 막는다.
        windows.sortedBy { it.second }.zipWithNext { earlier, later ->
            if (earlier.third > later.second) {
                throw AuthException(
                    ErrorCode.VALIDATION_ERROR,
                    "${earlier.first}과 ${later.first} 시간이 겹칩니다",
                )
            }
        }
        return EffectiveMealPolicy(
            perMealLimitMinor = request.perMealLimitMinor,
            dailyMealCount = request.dailyMealCount,
            monthlyCapMinor = request.monthlyCapMinor,
            breakfastStart = windows[0].second,
            breakfastEnd = windows[0].third,
            lunchStart = windows[1].second,
            lunchEnd = windows[1].third,
            dinnerStart = windows[2].second,
            dinnerEnd = windows[2].third,
        )
    }

    private fun parseTime(
        label: String,
        edge: String,
        raw: String,
    ): LocalTime =
        try {
            LocalTime.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$label $edge 시각 형식이 올바르지 않습니다 (HH:mm)")
        }

    /** 이력에 남길 전체 스냅샷 — 델타가 아니라 전체라 한 줄만 읽으면 그 시점 값을 안다. */
    private fun snapshotOf(values: EffectiveMealPolicy): Map<String, Any> =
        mapOf(
            "perMealLimitMinor" to values.perMealLimitMinor,
            "dailyMealCount" to values.dailyMealCount,
            "monthlyCapMinor" to values.monthlyCapMinor,
            "breakfastStart" to values.breakfastStart.toString(),
            "breakfastEnd" to values.breakfastEnd.toString(),
            "lunchStart" to values.lunchStart.toString(),
            "lunchEnd" to values.lunchEnd.toString(),
            "dinnerStart" to values.dinnerStart.toString(),
            "dinnerEnd" to values.dinnerEnd.toString(),
        )

    private fun toView(
        orgId: UUID,
        timezone: String,
        values: com.taspa.server.domain.meal.MealPolicyValues,
        usingDefaults: Boolean,
        updatedAt: java.time.Instant?,
    ): MealPolicyView {
        val ceiling = properties.policyCeiling
        return MealPolicyView(
            orgId = orgId,
            timezone = timezone,
            perMealLimitMinor = values.perMealLimitMinor,
            dailyMealCount = values.dailyMealCount,
            monthlyCapMinor = values.monthlyCapMinor,
            breakfastStart = values.breakfastStart.toString(),
            breakfastEnd = values.breakfastEnd.toString(),
            lunchStart = values.lunchStart.toString(),
            lunchEnd = values.lunchEnd.toString(),
            dinnerStart = values.dinnerStart.toString(),
            dinnerEnd = values.dinnerEnd.toString(),
            usingDefaults = usingDefaults,
            ceilingPerMealLimitMinor = ceiling.perMealLimitMinor,
            ceilingDailyMealCount = ceiling.dailyMealCount,
            ceilingMonthlyCapMinor = ceiling.monthlyCapMinor,
            updatedAt = updatedAt,
        )
    }

    companion object {
        /** 이력 조회 행 상한 — 자원고갈 방지(다른 목록 API 와 같은 규약). */
        const val MAX_HISTORY_LIMIT = 100
    }
}
