package com.taspa.server.meal

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.consumption.MealWindow
import com.taspa.server.domain.meal.MealMenu
import com.taspa.server.domain.meal.MealMenuRepository
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.meal.dto.MealMenuBulkResponse
import com.taspa.server.meal.dto.MealMenuBulkRow
import com.taspa.server.meal.dto.MealMenuUpsertRequest
import com.taspa.server.meal.dto.MealMenuView
import com.taspa.server.org.OrganizationService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * 식단(메뉴) 마스터 — 조직이 (날짜 × 끼니 × 사업장) 에 등록하는 배식 계획.
 *
 * 두 가지에 쓰인다:
 *  1. **예측 화면의 발주 근거** — "8/28 중식 130인분"만으로는 무엇을 얼마나 준비할지 알 수 없다.
 *  2. **실적의 메뉴 귀속**([forSlot]) — 승인 시 `consumption_events.menu_ref` 를 채운다. 그 축은
 *     V17 부터 있었지만 채우는 코드가 없어 집계 API 가 항상 null 을 냈다.
 *
 * ★[forSlot] 은 승인 경로(멤버십 FOR UPDATE 구간)에서 호출되므로 **잠금도 REQUIRES_NEW 도 열지 않는다**
 * (`MealPolicyResolver` 와 같은 규약).
 */
@Service
class MealMenuService(
    private val menuRepository: MealMenuRepository,
    private val siteRepository: SiteRepository,
    private val organizationService: OrganizationService,
    /**
     * 자기 자신 — 행별 `REQUIRES_NEW` 를 실제로 열려면 **프록시를 거쳐야** 한다(자기 호출은 AOP 를
     * 타지 않아 전파 설정이 조용히 무시된다). `ObjectProvider` 로 받는 이유는 순환 의존을 지연 해소하기
     * 위해서다 — 전역 순회 3종(`reconcileAll` 등)이 쓰는 것과 같은 규약.
     */
    private val self: org.springframework.beans.factory.ObjectProvider<MealMenuService>,
) {
    @Transactional(readOnly = true)
    fun list(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<MealMenuView> {
        organizationService.requireOrg(orgId)
        if (to.isBefore(from)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "종료일이 시작일보다 앞설 수 없습니다")
        }
        if (java.time.temporal.ChronoUnit.DAYS
                .between(from, to) > MAX_WINDOW_DAYS
        ) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "조회 구간은 ${MAX_WINDOW_DAYS}일까지입니다")
        }
        return menuRepository
            .findByOrgIdAndMenuDateBetweenOrderByMenuDateAscMealWindowAscSortOrderAscNameAsc(orgId, from, to)
            .map(::toView)
    }

    @Transactional
    fun create(
        orgId: UUID,
        request: MealMenuUpsertRequest,
    ): MealMenuView {
        organizationService.requireOrg(orgId)
        val normalized = validate(orgId, request)
        return try {
            toView(
                // ★`save` 가 아니라 `saveAndFlush` 다. Hibernate 는 INSERT 를 **커밋 시점까지 미루므로**
                // save 만 하면 UNIQUE 위반이 이 try 블록 **밖에서** 터진다 — 실측 결과: 아래 catch 가 한 번도
                // 실행되지 않아 응답이 우리 문구 대신 일반 CONFLICT("잠시 후 다시 시도해 주세요" — 재시도해도
                // 영원히 실패한다)가 됐고, 일괄 등록에서는 한 줄의 중복이 **요청 전체를 409** 로 만들어
                // 행별 부분 성공 모델이 무력화됐다. flush 를 강제해 DB 가 말하는 시점을 catch 안으로 끌어온다.
                menuRepository.saveAndFlush(
                    MealMenu(
                        orgId = orgId,
                        siteId = normalized.siteId,
                        menuDate = normalized.menuDate,
                        mealWindow = normalized.mealWindow,
                        name = normalized.name,
                        category = normalized.category.name,
                        corner = normalized.corner,
                        plannedPortions = normalized.plannedPortions,
                        sortOrder = normalized.sortOrder,
                    ),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // uq_meal_menu_slot — 같은 끼니에 같은 이름. 중복 등록은 발주량을 두 배로 만드므로 거절이 맞다.
            throw AuthException(ErrorCode.MEAL_MENU_EXISTS, "그 끼니에 같은 이름의 메뉴가 이미 있습니다")
        }
    }

    @Transactional
    fun update(
        orgId: UUID,
        menuId: UUID,
        request: MealMenuUpsertRequest,
    ): MealMenuView {
        organizationService.requireOrg(orgId)
        val menu =
            menuRepository.findByIdAndOrgId(menuId, orgId)
                ?: throw AuthException(ErrorCode.NOT_FOUND, "식단을 찾을 수 없습니다")
        val normalized = validate(orgId, request)
        // 날짜·끼니는 슬롯 자체라 옮기지 않는다 — 옮기려면 지우고 다시 만드는 것이 의도가 분명하다.
        if (normalized.menuDate != menu.menuDate || normalized.mealWindow != menu.mealWindow) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "날짜·끼니는 수정할 수 없습니다. 삭제 후 다시 등록하세요")
        }
        menu.siteId = normalized.siteId
        menu.name = normalized.name
        menu.category = normalized.category.name
        menu.corner = normalized.corner
        menu.plannedPortions = normalized.plannedPortions
        menu.sortOrder = normalized.sortOrder
        return try {
            toView(menuRepository.saveAndFlush(menu))
        } catch (e: DataIntegrityViolationException) {
            throw AuthException(ErrorCode.MEAL_MENU_EXISTS, "그 끼니에 같은 이름의 메뉴가 이미 있습니다")
        }
    }

    @Transactional
    fun delete(
        orgId: UUID,
        menuId: UUID,
    ) {
        organizationService.requireOrg(orgId)
        val menu = menuRepository.findByIdAndOrgId(menuId, orgId) ?: return // 멱등 — 재시도가 404 를 내면 화면이 오류를 띄운다
        menuRepository.delete(menu)
    }

    /**
     * 일괄 등록(한 주 식단 입력). **행별 독립 트랜잭션**이라 한 줄이 틀려도 나머지가 들어간다 —
     * 전체 롤백이면 27줄이 맞는데 1줄 오타로 처음부터 다시 입력해야 한다(CSV 초대와 같은 사상).
     */
    fun bulk(
        orgId: UUID,
        items: List<MealMenuUpsertRequest>,
    ): MealMenuBulkResponse {
        organizationService.requireOrg(orgId)
        if (items.size > MAX_BULK_ROWS) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "한 번에 등록할 수 있는 식단은 ${MAX_BULK_ROWS}줄까지입니다")
        }
        var created = 0
        var rejected = 0
        val rows =
            items.mapIndexed { index, item ->
                try {
                    val view = self.getObject().createRow(orgId, item)
                    created++
                    MealMenuBulkRow(index, "CREATED", view.id, null)
                } catch (e: AuthException) {
                    rejected++
                    MealMenuBulkRow(index, "REJECTED", null, e.message)
                } catch (e: DataIntegrityViolationException) {
                    // 벨트 — 위 flush 로 대부분 여기 오지 않지만, 다른 제약이 커밋 시점에 터져도
                    // **그 줄만** 실패해야 한다(한 줄 오타로 27줄을 다시 입력하게 만들지 않는다).
                    rejected++
                    MealMenuBulkRow(index, "REJECTED", null, "그 끼니에 같은 이름의 메뉴가 이미 있습니다")
                }
            }
        return MealMenuBulkResponse(created, 0, rejected, rows)
    }

    /** 행 하나를 독립 트랜잭션으로 — 실패한 줄이 앞선 성공을 되돌리지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createRow(
        orgId: UUID,
        request: MealMenuUpsertRequest,
    ): MealMenuView = create(orgId, request)

    /**
     * 그 날 그 끼니의 식단. **사업장 식단이 있으면 그것만**, 없으면 조직 공통.
     *
     * 둘을 합치지 않는 이유: 합치면 사업장이 자기 식단을 등록한 날에도 조직 공통 메뉴가 함께 떠서
     * 발주 목록이 실제 배식과 달라진다. 우선순위 판정을 **여기 한 곳**에만 두어 화면과 승인이 갈리지 않게 한다.
     */
    @Transactional(readOnly = true)
    fun forSlot(
        orgId: UUID,
        date: LocalDate,
        mealWindow: String,
        siteId: UUID?,
    ): List<MealMenu> {
        val all = menuRepository.findForSlot(orgId, date, mealWindow, siteId)
        if (all.isEmpty()) return emptyList()
        val siteSpecific = all.filter { it.siteId != null }
        return if (siteId != null && siteSpecific.isNotEmpty()) siteSpecific else all.filter { it.siteId == null }
    }

    private fun validate(
        orgId: UUID,
        request: MealMenuUpsertRequest,
    ): MealMenuUpsertRequest {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "메뉴 이름은 1~${MAX_NAME_LENGTH}자여야 합니다")
        }
        val window =
            MealWindow.entries.firstOrNull { it.name == request.mealWindow.trim().uppercase() }
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "끼니는 BREAKFAST·LUNCH·DINNER 중 하나여야 합니다")
        request.plannedPortions?.let {
            if (it < 0 || it > MAX_PORTIONS) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "계획 인분은 0~${MAX_PORTIONS} 사이여야 합니다")
            }
        }
        // 남의 조직 사업장 id 를 넣어 그 사업장 식단을 오염시키지 못하게 한다.
        request.siteId?.let { siteId ->
            val site = siteRepository.findById(siteId).orElse(null)
            if (site == null || site.orgId != orgId) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "이 조직의 사업장이 아닙니다")
            }
        }
        return request.copy(
            name = name,
            mealWindow = window.name,
            corner = request.corner?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun toView(menu: MealMenu) =
        MealMenuView(
            id = menu.id!!,
            siteId = menu.siteId,
            menuDate = menu.menuDate,
            mealWindow = menu.mealWindow,
            name = menu.name,
            category = menu.categoryEnum(),
            corner = menu.corner,
            plannedPortions = menu.plannedPortions,
            sortOrder = menu.sortOrder,
        )

    private companion object {
        const val MAX_NAME_LENGTH = 120
        const val MAX_PORTIONS = 100_000
        const val MAX_WINDOW_DAYS = 120L
        const val MAX_BULK_ROWS = 200
    }
}
