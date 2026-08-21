package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.meal.MealPolicyOverride
import com.taspa.server.domain.meal.MealPolicyOverrideRepository
import com.taspa.server.domain.meal.MealPolicyRevision
import com.taspa.server.domain.meal.MealPolicyRevisionRepository
import com.taspa.server.domain.meal.PolicyChangeType
import com.taspa.server.domain.meal.PolicyScope
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.meal.dto.MealPolicyOverrideRequest
import com.taspa.server.meal.dto.MealPolicyOverrideView
import com.taspa.server.meal.dto.MealPolicyPreview
import com.taspa.server.org.OrganizationService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 부서·사업장 식대 정책 재정의 관리.
 *
 * 재정의는 **필드 단위**다 — null 로 둔 필드는 상위값(조직 기본)을 그대로 물려받는다. 그래서 조직이
 * 한도를 올리면 그 필드를 재정의하지 않은 부서는 자동으로 따라 오른다. 전체 복제였다면 부서마다 손으로
 * 따라 고쳐야 하고, 빠뜨린 부서는 옛 한도로 남는다(그리고 아무도 모른다).
 *
 * 잠금을 쓰지 않는 이유는 [MealPolicyService] 와 같다 — 사람이 누르는 버튼이고, 마지막 저장이 이기면
 * 된다. 노드당 상시 재정의 1행은 DB 부분 유니크가 강제하고, 위반은 409 로 번역한다.
 */
@Service
class MealPolicyOverrideService(
    private val overrideRepository: MealPolicyOverrideRepository,
    private val revisionRepository: MealPolicyRevisionRepository,
    private val departmentRepository: DepartmentRepository,
    private val siteRepository: SiteRepository,
    private val policyResolver: MealPolicyResolver,
    private val organizationService: OrganizationService,
    private val properties: MealProperties,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun list(orgId: UUID): List<MealPolicyOverrideView> {
        val departments = departmentRepository.findByOrgId(orgId).associateBy({ it.id!! }, { it.name })
        val sites = siteRepository.findByOrgId(orgId).associateBy({ it.id!! }, { it.name })
        return overrideRepository
            .findByOrgId(orgId)
            .sortedWith(compareBy({ it.siteId != null }, { it.createdAt }))
            .map { toView(it, departments, sites) }
    }

    /**
     * "이 부서 사람에게 실제로 얼마가 적용되는가" — 해석기를 그대로 통과시킨다.
     *
     * 별도 계산을 두지 않는 것이 핵심이다. 미리보기가 자체 로직을 가지면 화면이 보여준 값과 계산대가
     * 승인하는 값이 갈라질 수 있는데, 그건 이 기능이 막으려던 바로 그 문제다.
     */
    @Transactional(readOnly = true)
    fun preview(
        orgId: UUID,
        scopeType: String?,
        scopeId: UUID?,
        onDate: LocalDate?,
    ): MealPolicyPreview {
        val org = organizationService.requireOrg(orgId)
        val date = onDate ?: ZonedDateTime.now(MealPolicyCalculus.zoneOf(org.timezone)).toLocalDate()
        val scope =
            when {
                scopeId == null -> MemberPolicyScope.ORG_DEFAULT
                scopeType.equals(PolicyScope.DEPARTMENT.name, ignoreCase = true) ->
                    MemberPolicyScope(departmentId = requireDepartment(orgId, scopeId))
                scopeType.equals(PolicyScope.SITE.name, ignoreCase = true) ->
                    MemberPolicyScope(siteId = requireSite(orgId, scopeId))
                else -> throw AuthException(ErrorCode.VALIDATION_ERROR, "scopeType 은 DEPARTMENT 또는 SITE 여야 합니다")
            }
        val resolved = policyResolver.resolve(orgId, scope, date)
        val label =
            when {
                scope.departmentId != null -> departmentRepository.findByIdAndOrgId(scope.departmentId, orgId)?.name
                scope.siteId != null -> siteRepository.findByIdAndOrgId(scope.siteId, orgId)?.name
                else -> null
            }
        return MealPolicyPreview(
            scopeType = if (scopeId == null) PolicyScope.ORG.name else scopeType!!.uppercase(),
            scopeId = scopeId,
            scopeLabel = label,
            perMealLimitMinor = resolved.policy.perMealLimitMinor,
            dailyMealCount = resolved.policy.dailyMealCount,
            monthlyCapMinor = resolved.policy.monthlyCapMinor,
            breakfastStart = resolved.policy.breakfastStart.toString(),
            breakfastEnd = resolved.policy.breakfastEnd.toString(),
            lunchStart = resolved.policy.lunchStart.toString(),
            lunchEnd = resolved.policy.lunchEnd.toString(),
            dinnerStart = resolved.policy.dinnerStart.toString(),
            dinnerEnd = resolved.policy.dinnerEnd.toString(),
            sources = MealPolicyField.entries.associate { it.name to resolved.sourceOf(it).scope.name },
            sourceLabels = MealPolicyField.entries.associate { it.name to resolved.sourceOf(it).label },
        )
    }

    @Transactional
    fun create(
        orgId: UUID,
        request: MealPolicyOverrideRequest,
        actorId: UUID?,
        actorIsOrgMember: Boolean,
    ): MealPolicyOverrideView {
        val scopeId = parseUuid(request.scopeId, "scopeId")
        val isDepartment = request.scopeType.equals(PolicyScope.DEPARTMENT.name, ignoreCase = true)
        val isSite = request.scopeType.equals(PolicyScope.SITE.name, ignoreCase = true)
        if (!isDepartment && !isSite) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "scopeType 은 DEPARTMENT 또는 SITE 여야 합니다")
        }
        // 타 조직 부서·사업장에 재정의를 붙이지 못하게 소속을 확인한다(테넌시).
        val label =
            if (isDepartment) {
                requireDepartment(orgId, scopeId)
                departmentRepository.findByIdAndOrgId(scopeId, orgId)!!.name
            } else {
                requireSite(orgId, scopeId)
                siteRepository.findByIdAndOrgId(scopeId, orgId)!!.name
            }

        val entity =
            MealPolicyOverride(
                orgId = orgId,
                departmentId = if (isDepartment) scopeId else null,
                siteId = if (isSite) scopeId else null,
            )
        applyAndValidate(entity, request)
        entity.updatedBy = actorId

        val saved = save(entity)
        recordRevision(
            orgId,
            saved,
            scopeType = if (isDepartment) PolicyScope.DEPARTMENT else PolicyScope.SITE,
            scopeId = scopeId,
            scopeLabel = label,
            changeType = PolicyChangeType.CREATED,
            actorId = actorId,
            actorIsOrgMember = actorIsOrgMember,
        )
        return toViewSingle(orgId, saved)
    }

    @Transactional
    fun update(
        orgId: UUID,
        overrideId: UUID,
        request: MealPolicyOverrideRequest,
        actorId: UUID?,
        actorIsOrgMember: Boolean,
    ): MealPolicyOverrideView {
        val entity =
            overrideRepository.findByIdAndOrgId(overrideId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "재정의를 찾을 수 없습니다")
        applyAndValidate(entity, request)
        entity.updatedBy = actorId
        val saved = save(entity)
        recordRevision(
            orgId,
            saved,
            scopeType = if (saved.departmentId != null) PolicyScope.DEPARTMENT else PolicyScope.SITE,
            scopeId = saved.departmentId ?: saved.siteId!!,
            scopeLabel = labelOf(orgId, saved),
            changeType = PolicyChangeType.UPDATED,
            actorId = actorId,
            actorIsOrgMember = actorIsOrgMember,
        )
        return toViewSingle(orgId, saved)
    }

    @Transactional
    fun delete(
        orgId: UUID,
        overrideId: UUID,
        actorId: UUID?,
        actorIsOrgMember: Boolean,
    ) {
        val entity =
            overrideRepository.findByIdAndOrgId(overrideId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "재정의를 찾을 수 없습니다")
        // 이력을 **삭제 전에** 남긴다 — REMOVED 행의 document 는 "직전에 무엇이었나"라서,
        // 지운 뒤에 만들면 그 값을 알 수 없다.
        recordRevision(
            orgId,
            entity,
            scopeType = if (entity.departmentId != null) PolicyScope.DEPARTMENT else PolicyScope.SITE,
            scopeId = entity.departmentId ?: entity.siteId!!,
            scopeLabel = labelOf(orgId, entity),
            changeType = PolicyChangeType.REMOVED,
            actorId = actorId,
            actorIsOrgMember = actorIsOrgMember,
        )
        overrideRepository.delete(entity)
    }

    // ---- 내부 ----

    private fun save(entity: MealPolicyOverride): MealPolicyOverride =
        try {
            overrideRepository.saveAndFlush(entity)
        } catch (_: DataIntegrityViolationException) {
            // 노드당 상시 재정의 1행 부분 유니크(uq_mpo_*_standing) 위반. 사용자에게는 "이미 있다"가
            // 정확한 사실이다 — 새로 만들 게 아니라 기존 것을 고쳐야 한다.
            throw AuthException(ErrorCode.MEAL_POLICY_OVERRIDE_EXISTS)
        }

    /**
     * 요청을 엔티티에 반영하고 검증한다.
     *
     * ★검증은 DB CHECK 와 **의도적으로 중복**된다. CHECK 는 최후 방어선이라 위반하면 SQL 예외 문구가
     * 나가는데, 그건 사용자가 읽을 문장이 아니다. 같은 규칙을 한국어로 먼저 거절한다.
     */
    private fun applyAndValidate(
        entity: MealPolicyOverride,
        request: MealPolicyOverrideRequest,
    ) {
        val ceiling = properties.policyCeiling
        request.perMealLimitMinor?.let {
            if (it < 0 || it > ceiling.perMealLimitMinor) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "1식 한도는 0 이상 ${ceiling.perMealLimitMinor} 이하여야 합니다")
            }
        }
        request.dailyMealCount?.let {
            if (it < 1 || it > ceiling.dailyMealCount) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "1일 횟수는 1 이상 ${ceiling.dailyMealCount} 이하여야 합니다")
            }
        }
        request.monthlyCapMinor?.let {
            if (it < 0 || it > ceiling.monthlyCapMinor) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "월 한도는 0 이상 ${ceiling.monthlyCapMinor} 이하여야 합니다")
            }
        }

        val breakfast = parseWindow("아침", request.breakfastStart, request.breakfastEnd)
        val lunch = parseWindow("점심", request.lunchStart, request.lunchEnd)
        val dinner = parseWindow("저녁", request.dinnerStart, request.dinnerEnd)

        val from = parseDate("적용 시작일", request.effectiveFrom)
        val to = parseDate("적용 종료일", request.effectiveTo)
        if (from != null && to != null && from.isAfter(to)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "적용 시작일이 종료일보다 늦습니다")
        }

        val hasAny =
            request.perMealLimitMinor != null ||
                request.dailyMealCount != null ||
                request.monthlyCapMinor != null ||
                breakfast != null ||
                lunch != null ||
                dinner != null
        if (!hasAny) {
            // 아무것도 재정의하지 않는 행은 해석에 영향이 없어 "설정했는데 안 바뀐다"만 만든다.
            throw AuthException(ErrorCode.VALIDATION_ERROR, "재정의할 항목을 하나 이상 지정해 주세요")
        }

        entity.perMealLimitMinor = request.perMealLimitMinor
        entity.dailyMealCount = request.dailyMealCount
        entity.monthlyCapMinor = request.monthlyCapMinor
        entity.breakfastStart = breakfast?.first
        entity.breakfastEnd = breakfast?.second
        entity.lunchStart = lunch?.first
        entity.lunchEnd = lunch?.second
        entity.dinnerStart = dinner?.first
        entity.dinnerEnd = dinner?.second
        entity.effectiveFrom = from
        entity.effectiveTo = to
        entity.reason =
            request.reason
                ?.trim()
                ?.take(200)
                ?.ifBlank { null }
        entity.updatedAt = java.time.Instant.now()
    }

    /**
     * 끼니창은 **쌍이 원자 단위**다. 한쪽만 오면 거절한다 — 받아 두면 상위값과 짝이 맞지 않아
     * `start >= end` 인 창이 만들어지고, 그 끼니는 화면에서 조용히 사라진다.
     */
    private fun parseWindow(
        label: String,
        rawStart: String?,
        rawEnd: String?,
    ): Pair<LocalTime, LocalTime>? {
        if (rawStart.isNullOrBlank() && rawEnd.isNullOrBlank()) return null
        if (rawStart.isNullOrBlank() || rawEnd.isNullOrBlank()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$label 시간은 시작과 종료를 함께 지정해야 합니다")
        }
        val start = parseTime(label, "시작", rawStart)
        val end = parseTime(label, "종료", rawEnd)
        if (start >= end) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "$label 시간은 시작이 종료보다 빨라야 합니다(자정을 넘는 끼니창은 지원하지 않습니다)",
            )
        }
        return start to end
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

    private fun parseDate(
        label: String,
        raw: String?,
    ): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$label 형식이 올바르지 않습니다 (YYYY-MM-DD)")
        }
    }

    private fun parseUuid(
        raw: String,
        field: String,
    ): UUID =
        try {
            UUID.fromString(raw.trim())
        } catch (_: IllegalArgumentException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$field 형식이 올바르지 않습니다")
        }

    private fun requireDepartment(
        orgId: UUID,
        id: UUID,
    ): UUID {
        departmentRepository.findByIdAndOrgId(id, orgId)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다")
        return id
    }

    private fun requireSite(
        orgId: UUID,
        id: UUID,
    ): UUID {
        siteRepository.findByIdAndOrgId(id, orgId)
            ?: throw AuthException(ErrorCode.NOT_FOUND, "사업장을 찾을 수 없습니다")
        return id
    }

    private fun labelOf(
        orgId: UUID,
        entity: MealPolicyOverride,
    ): String? =
        entity.departmentId?.let { departmentRepository.findByIdAndOrgId(it, orgId)?.name }
            ?: entity.siteId?.let { siteRepository.findByIdAndOrgId(it, orgId)?.name }

    private fun recordRevision(
        orgId: UUID,
        entity: MealPolicyOverride,
        scopeType: PolicyScope,
        scopeId: UUID,
        scopeLabel: String?,
        changeType: PolicyChangeType,
        actorId: UUID?,
        actorIsOrgMember: Boolean,
    ) {
        revisionRepository.save(
            MealPolicyRevision(
                orgId = orgId,
                scopeType = scopeType.name,
                scopeId = scopeId,
                // 이름 스냅샷 — 그 부서가 삭제된 뒤에도 이력이 무엇을 가리켰는지 읽힌다.
                scopeLabel = scopeLabel,
                changeType = changeType.name,
                document = objectMapper.writeValueAsString(snapshotOf(entity)),
                actorIsOrgMember = actorIsOrgMember,
                recordedBy = actorId,
            ),
        )
    }

    /** 재정의 스냅샷 — null 필드도 그대로 남긴다("물려받음"이라는 사실 자체가 정보다). */
    private fun snapshotOf(entity: MealPolicyOverride): Map<String, Any?> =
        mapOf(
            "perMealLimitMinor" to entity.perMealLimitMinor,
            "dailyMealCount" to entity.dailyMealCount,
            "monthlyCapMinor" to entity.monthlyCapMinor,
            "breakfastStart" to entity.breakfastStart?.toString(),
            "breakfastEnd" to entity.breakfastEnd?.toString(),
            "lunchStart" to entity.lunchStart?.toString(),
            "lunchEnd" to entity.lunchEnd?.toString(),
            "dinnerStart" to entity.dinnerStart?.toString(),
            "dinnerEnd" to entity.dinnerEnd?.toString(),
            "effectiveFrom" to entity.effectiveFrom?.toString(),
            "effectiveTo" to entity.effectiveTo?.toString(),
            "reason" to entity.reason,
        )

    private fun toViewSingle(
        orgId: UUID,
        entity: MealPolicyOverride,
    ): MealPolicyOverrideView =
        toView(
            entity,
            departmentRepository.findByOrgId(orgId).associateBy({ it.id!! }, { it.name }),
            siteRepository.findByOrgId(orgId).associateBy({ it.id!! }, { it.name }),
        )

    private fun toView(
        entity: MealPolicyOverride,
        departments: Map<UUID, String>,
        sites: Map<UUID, String>,
    ): MealPolicyOverrideView {
        val isDepartment = entity.departmentId != null
        val scopeId = entity.departmentId ?: entity.siteId!!
        return MealPolicyOverrideView(
            id = entity.id!!,
            scopeType = if (isDepartment) PolicyScope.DEPARTMENT.name else PolicyScope.SITE.name,
            scopeId = scopeId,
            scopeLabel = if (isDepartment) departments[scopeId] else sites[scopeId],
            perMealLimitMinor = entity.perMealLimitMinor,
            dailyMealCount = entity.dailyMealCount,
            monthlyCapMinor = entity.monthlyCapMinor,
            breakfastStart = entity.breakfastStart?.toString(),
            breakfastEnd = entity.breakfastEnd?.toString(),
            lunchStart = entity.lunchStart?.toString(),
            lunchEnd = entity.lunchEnd?.toString(),
            dinnerStart = entity.dinnerStart?.toString(),
            dinnerEnd = entity.dinnerEnd?.toString(),
            effectiveFrom = entity.effectiveFrom?.toString(),
            effectiveTo = entity.effectiveTo?.toString(),
            reason = entity.reason,
            updatedAt = entity.updatedAt,
        )
    }
}
