package com.taspa.server.meal

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.meal.dto.MealPolicyOverrideRequest
import com.taspa.server.meal.dto.MealPolicyOverrideView
import com.taspa.server.meal.dto.MealPolicyPreview
import com.taspa.server.meal.dto.MealPolicyRevisionView
import com.taspa.server.meal.dto.MealPolicyUpdateRequest
import com.taspa.server.meal.dto.MealPolicyView
import com.taspa.server.org.OrgConsoleSurface
import com.taspa.server.org.OrgSessionAuthorizer
import com.taspa.server.org.OrganizationService
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * 조직 식대 정책 관리 API — `/api/orgs` 세션 체인(ORG_ADMIN 자율 콘솔).
 *
 * 관리자 전용 경로(/api/admin 이하, hasRole ADMIN)가 아니라 여기 있는 이유는 **조직관리자가 접근할 수 있어야 하기**
 * 때문이다. 인가는 다른 org 콘솔 표면과 같은 단일 진입점(`OrgSessionAuthorizer`)을 쓴다 —
 * 위임 베어러 하드 게이트 + 정책 엔진 판정.
 *
 * 변경은 step-up(@RequireRecentAuth) + CSRF + org 결속 감사. 한도는 곧 회사 지출이므로 조회와 같은
 * 무게로 다루지 않는다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/meal-policy")
class MealPolicyController(
    private val mealPolicyService: MealPolicyService,
    private val overrideService: MealPolicyOverrideService,
    private val organizationService: OrganizationService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun read(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<MealPolicyView> {
        orgSessionAuthorizer.authorize(
            authentication,
            orgId,
            IamActions.MEAL_READ_POLICY,
            Trn.mealPolicy(orgId),
            "MealPolicyController.read",
            OrgConsoleSurface.MEAL_POLICY,
        )
        return ResponseEntity.ok(mealPolicyService.read(orgId))
    }

    @RequireRecentAuth
    @PutMapping
    fun update(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: MealPolicyUpdateRequest,
    ): ResponseEntity<MealPolicyView> {
        val actor =
            orgSessionAuthorizer.authorize(
                authentication,
                orgId,
                IamActions.MEAL_UPDATE_POLICY,
                Trn.mealPolicy(orgId),
                "MealPolicyController.update",
                OrgConsoleSurface.MEAL_POLICY,
            )
        // 행위자가 이 조직 사람인지 — 플랫폼 운영자의 변경을 조직이 사후에 가려낼 수 있어야 한다.
        // 플랫폼 ADMIN 이어도 그 조직 활성 멤버라면 org 사람으로 본다(겸직 관리자).
        val actorIsOrgMember =
            actor.id
                ?.let { organizationService.isActiveMember(orgId, it) }
                ?: false

        val before = mealPolicyService.read(orgId)
        val after = mealPolicyService.update(orgId, request, actor.id, actorIsOrgMember)
        auditEventService.record(
            "ORG_MEAL_POLICY_UPDATED",
            actor.id,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "actorPlatformAdmin" to (actor.role == UserRole.ADMIN.name),
                "actorIsOrgMember" to actorIsOrgMember,
                "before" to summaryOf(before),
                "after" to summaryOf(after),
            ),
        )
        return ResponseEntity.ok(after)
    }

    @GetMapping("/history")
    fun history(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<List<MealPolicyRevisionView>> {
        orgSessionAuthorizer.authorize(
            authentication,
            orgId,
            IamActions.MEAL_READ_POLICY_HISTORY,
            Trn.mealPolicyRevisions(orgId),
            "MealPolicyController.history",
            OrgConsoleSurface.MEAL_POLICY,
        )
        return ResponseEntity.ok(mealPolicyService.history(orgId, limit, offset))
    }

    // ── 부서·사업장 재정의 ────────────────────────────────────────────

    @GetMapping("/overrides")
    fun listOverrides(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<MealPolicyOverrideView>> {
        orgSessionAuthorizer.authorize(
            authentication,
            orgId,
            IamActions.MEAL_READ_POLICY_OVERRIDES,
            Trn.mealPolicyOverrides(orgId),
            "MealPolicyController.listOverrides",
            OrgConsoleSurface.MEAL_POLICY,
        )
        return ResponseEntity.ok(overrideService.list(orgId))
    }

    /**
     * "이 대상에게 실제로 얼마가 적용되는가" 미리보기. 해석기를 그대로 통과시키므로 화면이 보여주는
     * 값과 계산대가 승인하는 값이 갈라질 수 없다.
     */
    @GetMapping("/preview")
    fun preview(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) scopeType: String?,
        @RequestParam(required = false) scopeId: UUID?,
        @RequestParam(required = false) onDate: String?,
    ): ResponseEntity<MealPolicyPreview> {
        orgSessionAuthorizer.authorize(
            authentication,
            orgId,
            IamActions.MEAL_READ_POLICY_OVERRIDES,
            Trn.mealPolicyOverrides(orgId),
            "MealPolicyController.preview",
            OrgConsoleSurface.MEAL_POLICY,
        )
        val date =
            onDate?.takeIf { it.isNotBlank() }?.let {
                runCatching { LocalDate.parse(it) }.getOrElse {
                    throw AuthException(ErrorCode.VALIDATION_ERROR, "onDate 형식이 올바르지 않습니다 (YYYY-MM-DD)")
                }
            }
        return ResponseEntity.ok(overrideService.preview(orgId, scopeType, scopeId, date))
    }

    @RequireRecentAuth
    @PostMapping("/overrides")
    fun createOverride(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: MealPolicyOverrideRequest,
    ): ResponseEntity<MealPolicyOverrideView> {
        val actor = authorizeManage(authentication, orgId, "MealPolicyController.createOverride")
        val view = overrideService.create(orgId, request, actor.id, isOrgMember(orgId, actor.id))
        auditEventService.record(
            "ORG_MEAL_POLICY_OVERRIDE_CREATED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "overrideId" to view.id.toString(), "scope" to "${view.scopeType}:${view.scopeId}"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @RequireRecentAuth
    @PutMapping("/overrides/{overrideId}")
    fun updateOverride(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable overrideId: UUID,
        @RequestBody request: MealPolicyOverrideRequest,
    ): ResponseEntity<MealPolicyOverrideView> {
        val actor = authorizeManage(authentication, orgId, "MealPolicyController.updateOverride")
        val view = overrideService.update(orgId, overrideId, request, actor.id, isOrgMember(orgId, actor.id))
        auditEventService.record(
            "ORG_MEAL_POLICY_OVERRIDE_UPDATED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "overrideId" to overrideId.toString(), "scope" to "${view.scopeType}:${view.scopeId}"),
        )
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/overrides/{overrideId}")
    fun deleteOverride(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable overrideId: UUID,
    ): ResponseEntity<Void> {
        val actor = authorizeManage(authentication, orgId, "MealPolicyController.deleteOverride")
        overrideService.delete(orgId, overrideId, actor.id, isOrgMember(orgId, actor.id))
        auditEventService.record(
            "ORG_MEAL_POLICY_OVERRIDE_REMOVED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "overrideId" to overrideId.toString()),
        )
        return ResponseEntity.noContent().build()
    }

    private fun authorizeManage(
        authentication: Authentication,
        orgId: UUID,
        label: String,
    ) = orgSessionAuthorizer.authorize(
        authentication,
        orgId,
        IamActions.MEAL_MANAGE_POLICY_OVERRIDES,
        Trn.mealPolicyOverrides(orgId),
        label,
        OrgConsoleSurface.MEAL_POLICY,
    )

    /** 행위자가 이 조직 사람인지 — 플랫폼 운영자의 변경을 조직이 사후에 가려낼 수 있어야 한다. */
    private fun isOrgMember(
        orgId: UUID,
        actorId: UUID?,
    ): Boolean = actorId?.let { organizationService.isActiveMember(orgId, it) } ?: false

    /** 감사 detail 용 요약 — 상한·타임존 같은 파생값은 뺀다(무엇이 바뀌었는지만 남긴다). */
    private fun summaryOf(view: MealPolicyView): Map<String, Any> =
        mapOf(
            "perMealLimitMinor" to view.perMealLimitMinor,
            "dailyMealCount" to view.dailyMealCount,
            "monthlyCapMinor" to view.monthlyCapMinor,
            "breakfast" to "${view.breakfastStart}~${view.breakfastEnd}",
            "lunch" to "${view.lunchStart}~${view.lunchEnd}",
            "dinner" to "${view.dinnerStart}~${view.dinnerEnd}",
        )
}
