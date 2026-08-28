package com.taspa.server.meal

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.meal.dto.MealMenuBulkResponse
import com.taspa.server.meal.dto.MealMenuUpsertRequest
import com.taspa.server.meal.dto.MealMenuView
import com.taspa.server.org.OrgConsoleSurface
import com.taspa.server.org.OrgSessionAuthorizer
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
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 식단(메뉴) API — `/api/orgs` 세션 체인(조직 자율 콘솔).
 *
 * 식대 정책(금액)과 **action 을 나눠 둔다**(`meal:ReadMenu`/`ManageMenu`) — 메뉴를 짜는 사람과
 * 회사 지출 한도를 정하는 사람이 같아야 할 이유가 없다. 나중에 영양사 역할을 두려면 지금 갈라 놔야 한다.
 *
 * 변경은 step-up + CSRF + org 결속 감사(다른 org 표면과 동일).
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/meal-menus")
class MealMenuController(
    private val menuService: MealMenuService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam from: String,
        @RequestParam to: String,
    ): ResponseEntity<List<MealMenuView>> {
        authorize(authentication, orgId, IamActions.MEAL_READ_MENU, "list")
        return ResponseEntity.ok(menuService.list(orgId, parseDate(from, "from"), parseDate(to, "to")))
    }

    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: MealMenuUpsertRequest,
    ): ResponseEntity<MealMenuView> {
        val actor = authorize(authentication, orgId, IamActions.MEAL_MANAGE_MENU, "create")
        val view = menuService.create(orgId, request)
        audit(actor.id, orgId, "ORG_MEAL_MENU_CREATED", view)
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    /** 한 주 식단 일괄 등록. 행별 독립이라 한 줄이 틀려도 나머지는 들어간다. */
    @RequireRecentAuth
    @PostMapping("/bulk")
    fun bulk(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody items: List<MealMenuUpsertRequest>,
    ): ResponseEntity<MealMenuBulkResponse> {
        val actor = authorize(authentication, orgId, IamActions.MEAL_MANAGE_MENU, "bulk")
        val result = menuService.bulk(orgId, items)
        auditEventService.record(
            "ORG_MEAL_MENU_BULK",
            actor.id,
            orgId,
            mapOf("created" to result.created, "rejected" to result.rejected),
        )
        return ResponseEntity.ok(result)
    }

    @RequireRecentAuth
    @PutMapping("/{menuId}")
    fun update(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable menuId: UUID,
        @RequestBody request: MealMenuUpsertRequest,
    ): ResponseEntity<MealMenuView> {
        val actor = authorize(authentication, orgId, IamActions.MEAL_MANAGE_MENU, "update")
        val view = menuService.update(orgId, menuId, request)
        audit(actor.id, orgId, "ORG_MEAL_MENU_UPDATED", view)
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/{menuId}")
    fun delete(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable menuId: UUID,
    ): ResponseEntity<Void> {
        val actor = authorize(authentication, orgId, IamActions.MEAL_MANAGE_MENU, "delete")
        menuService.delete(orgId, menuId)
        auditEventService.record("ORG_MEAL_MENU_DELETED", actor.id, orgId, mapOf("menuId" to menuId.toString()))
        return ResponseEntity.noContent().build()
    }

    private fun authorize(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        label: String,
    ) = orgSessionAuthorizer.authorize(
        authentication,
        orgId,
        action,
        Trn.mealMenus(orgId),
        "MealMenuController.$label",
        OrgConsoleSurface.MEAL_MENU,
    )

    private fun audit(
        actorId: UUID?,
        orgId: UUID,
        type: String,
        view: MealMenuView,
    ) = auditEventService.record(
        type,
        actorId,
        orgId,
        mapOf(
            "menuId" to view.id.toString(),
            "date" to view.menuDate.toString(),
            "mealWindow" to view.mealWindow,
            "name" to view.name,
            "category" to view.category.name,
        ),
    )

    private fun parseDate(
        raw: String,
        field: String,
    ): LocalDate =
        try {
            LocalDate.parse(raw.trim())
        } catch (e: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$field 는 YYYY-MM-DD 형식이어야 합니다")
        }
}
