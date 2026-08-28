package com.taspa.server.meal

import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.meal.dto.MerchantMenuView
import com.taspa.server.meal.dto.MerchantMenusResponse
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * POS 단말이 보는 "지금 끼니의 식단".
 *
 * 조직 결정은 **매장 → 사업장 → 조직** 한 방향뿐이다(`merchants.site_id`). 연결이 없으면 빈 목록 —
 * 여러 조직 손님을 받는 매장은 어느 조직 식단인지 결정 불가이고, 추측하면 남의 조직 메뉴를 배식 목록으로
 * 띄운다.
 *
 * ★날짜와 끼니는 **매장 타임존** 기준이다(`merchants.timezone`). 매장의 하루 경계가 현장 감각이고,
 * 조직 달력을 빌리면 자정 근처에 단말이 어제 메뉴를 띄운다. 대신 조직 식단 행의 날짜는 org-로컬이라,
 * 두 타임존이 다르면 경계 시각에 하루 어긋날 수 있다 — 구내식당(둘이 같은 지역)에서는 발생하지 않고,
 * 발생하는 구성에서는 메뉴가 안 잡혀 **빈 목록**이 된다(틀린 메뉴를 띄우는 것보다 낫다).
 */
@Component
class MerchantMenuLookup(
    private val siteRepository: SiteRepository,
    private val organizationRepository: OrganizationRepository,
    private val menuService: MealMenuService,
    private val policyResolver: MealPolicyResolver,
) {
    @Transactional(readOnly = true)
    fun today(merchant: Merchant): MerchantMenusResponse {
        val zone = MealPolicyCalculus.zoneOf(merchant.timezone)
        val now =
            java.time.Instant
                .now()
                .atZone(zone)
        val date = now.toLocalDate()
        val site = merchant.siteId?.let { siteRepository.findById(it).orElse(null) }
        if (site == null) {
            return MerchantMenusResponse(DEFAULT_WINDOW, date, emptyList())
        }
        val window = windowOf(site.orgId, site.id, now)
        val menus = menuService.forSlot(site.orgId, date, window, site.id)
        return MerchantMenusResponse(
            window,
            date,
            menus.map {
                MerchantMenuView(
                    menuId = it.id!!,
                    name = it.name,
                    category = it.category,
                    corner = it.corner,
                )
            },
        )
    }

    /**
     * 지금 시각이 속한 끼니 — **승인이 쓰는 것과 같은 정책 창**으로 판정한다(`MealPolicyResolver`).
     * 여기서 따로 판정하면 단말이 점심 메뉴를 띄우는데 승인은 저녁으로 기록하는 상태가 가능하다.
     * 어느 창에도 없으면 점심으로 둔다(목록이 비면 화면이 이유를 말하지 못하는 것보다, 그 끼니 메뉴를
     * 보여 주고 승인 시 서버가 창을 판정하는 편이 낫다 — 귀속은 승인 시각으로 다시 계산된다).
     */
    private fun windowOf(
        orgId: java.util.UUID,
        siteId: java.util.UUID?,
        localNow: java.time.ZonedDateTime,
    ): String {
        if (organizationRepository.findById(orgId).orElse(null) == null) return DEFAULT_WINDOW
        val policy = policyResolver.resolve(orgId, MemberPolicyScope(null, siteId), localNow.toLocalDate())
        return MealPolicyCalculus.resolveWindow(policy.policy, localNow)?.name ?: DEFAULT_WINDOW
    }

    private companion object {
        const val DEFAULT_WINDOW = "LUNCH"
    }
}
