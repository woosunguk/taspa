package com.taspa.server.meal

import com.taspa.server.domain.meal.MealPolicy
import com.taspa.server.domain.meal.MealPolicyOverride
import com.taspa.server.domain.meal.MealPolicyOverrideRepository
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * 유효 식대 정책을 해석한다 — **승인(POS redeem)과 자격 조회(직원 화면)가 공유하는 단일 입구**.
 *
 * 우선순위는 구체적인 쪽이 이긴다: **부서(가장 가까운 조상부터) > 사업장 > 조직 > 코드 기본값**.
 * 같은 노드에 상시 재정의와 기간 한정 재정의가 함께 있으면 **기간 한정이 이긴다**(그게 기간을 지정한
 * 이유다). 병합은 **필드 단위**라, 점심시간만 재정의한 부서도 조직의 1식 한도 인상을 그대로 물려받는다.
 *
 * 왜 입구가 하나여야 하는가: 이 우선순위 코드가 두 경로에 각각 생기면 **화면은 15,000원이라는데
 * 계산대는 12,000원으로 승인**하는 어긋남이 만들어진다. 그 순간을 겪는 건 줄 서 있는 직원이다.
 *
 * **불변식**(깨면 승인 경로가 멈춘다):
 *  - 이 클래스는 **어떤 FOR UPDATE 도 걸지 않는다.** redeem 은 이미 멤버십 행 잠금을 쥔 채 여기를
 *    부른다. 여기서 또 잠그면 잠금 순서가 호출부마다 달라져 교차 데드락의 씨앗이 된다.
 *  - **`REQUIRES_NEW` 를 열지 않는다.** 바깥이 비관적 잠금을 쥔 상태에서 새 커넥션을 열면 DB 가 볼 수
 *    없는 자기 교착이 된다(CLAUDE.md "비관적 잠금 불변식" — 초대 수락에서 실제로 터졌다).
 *  - **캐시하지 않는다.** 정책 변경이 즉시 반영돼야 화면과 계산대가 같은 값을 본다.
 *  - **질의 수가 조직도 깊이에 비례하지 않는다.** 재정의는 org 단위로 한 번에 읽어 메모리에서 병합한다 —
 *    트리를 타고 올라가며 노드마다 질의하면 그 왕복이 전부 멤버십 잠금 구간 안에서 일어난다.
 */
@Service
class MealPolicyResolver(
    private val policyRepository: MealPolicyRepository,
    private val overrideRepository: MealPolicyOverrideRepository,
    private val departmentRepository: DepartmentRepository,
    private val siteRepository: SiteRepository,
) {
    /**
     * @param scope 멤버의 부서·사업장(멤버십 행에서 온다).
     * @param onDate 기간 한정 재정의 판정 기준일(org 로컬 날짜).
     */
    @Transactional(readOnly = true)
    fun resolve(
        orgId: UUID,
        scope: MemberPolicyScope,
        onDate: LocalDate,
    ): ResolvedMealPolicy {
        val stored = policyRepository.findById(orgId).orElse(null)
        // 행이 없으면 코드 기본값. redeem 과 자격 조회가 각자 폴백을 갖고 있던 것을 여기로 모았다 —
        // 정책 미설정 조직에서 두 경로가 다른 기본값을 쓰면 그 자체로 어긋남이다.
        val base = stored ?: MealPolicy(orgId = orgId)
        val baseSource =
            PolicySource(
                scope = if (stored != null) PolicyScopeType.ORG else PolicyScopeType.CODE_DEFAULT,
                scopeId = if (stored != null) orgId else null,
            )

        val chain = overrideChain(orgId, scope, onDate)
        if (chain.isEmpty()) {
            return ResolvedMealPolicy(
                policy = EffectiveMealPolicy.from(base),
                sources = MealPolicyField.entries.associateWith { baseSource },
            )
        }
        return merge(base, baseSource, chain)
    }

    /** 조직 기본값만 볼 때(정책 편집 화면·조직 단위 조회). */
    @Transactional(readOnly = true)
    fun resolveOrgDefault(
        orgId: UUID,
        onDate: LocalDate,
    ): ResolvedMealPolicy = resolve(orgId, MemberPolicyScope.ORG_DEFAULT, onDate)

    /**
     * 이 멤버에게 적용되는 재정의를 **우선순위 높은 것부터** 나열한다.
     * 부서(자기 → 부모 → … → 루트) → 사업장 순. 같은 노드에서는 기간 한정이 상시보다 앞선다.
     */
    private fun overrideChain(
        orgId: UUID,
        scope: MemberPolicyScope,
        onDate: LocalDate,
    ): List<Pair<MealPolicyOverride, PolicySource>> {
        if (scope.departmentId == null && scope.siteId == null) return emptyList()
        val all = overrideRepository.findByOrgId(orgId).filter { it.appliesOn(onDate) }
        if (all.isEmpty()) return emptyList()

        val chain = mutableListOf<Pair<MealPolicyOverride, PolicySource>>()

        if (scope.departmentId != null) {
            val departments = departmentRepository.findByOrgId(orgId).associateBy { it.id!! }
            val byDepartment = all.filter { it.departmentId != null }.groupBy { it.departmentId!! }
            for (node in ancestry(scope.departmentId, departments)) {
                byDepartment[node.id]?.let { candidates ->
                    pick(candidates)?.let { chosen ->
                        chain += chosen to
                            PolicySource(
                                scope = PolicyScopeType.DEPARTMENT,
                                scopeId = node.id,
                                label = node.name,
                                temporary = chosen.temporary,
                            )
                    }
                }
            }
        }

        if (scope.siteId != null) {
            val siteOverrides = all.filter { it.siteId == scope.siteId }
            pick(siteOverrides)?.let { chosen ->
                val name = siteRepository.findById(scope.siteId).orElse(null)?.name
                chain += chosen to
                    PolicySource(
                        scope = PolicyScopeType.SITE,
                        scopeId = scope.siteId,
                        label = name,
                        temporary = chosen.temporary,
                    )
            }
        }
        return chain
    }

    /**
     * 부서 자기 자신 → 부모 → … → 루트.
     *
     * `parent_id` 는 DB 가 트리를 강제하지 않으므로(자기참조 FK 만 있다) 손상 데이터가 순환을 만들 수
     * 있다. 방문 집합으로 끊는다 — 여기서 무한 루프가 나면 그건 결제 승인이 멈춘다는 뜻이다.
     */
    private fun ancestry(
        start: UUID,
        byId: Map<UUID, Department>,
    ): List<Department> {
        val path = mutableListOf<Department>()
        val seen = mutableSetOf<UUID>()
        var cursor: UUID? = start
        while (cursor != null && seen.add(cursor)) {
            val node = byId[cursor] ?: break
            path += node
            cursor = node.parentId
        }
        return path
    }

    /**
     * 한 노드의 후보 중 하나를 고른다 — **기간 한정이 상시를 이긴다**(기간을 지정한 것이 더 구체적인
     * 의사표시다). 기간 한정이 여럿이면 시작일이 늦은 쪽(가장 최근에 시작된 것)을 쓴다.
     */
    private fun pick(candidates: List<MealPolicyOverride>): MealPolicyOverride? =
        candidates.maxWithOrNull(
            compareBy<MealPolicyOverride> { if (it.temporary) 1 else 0 }
                .thenBy { it.effectiveFrom ?: LocalDate.MIN }
                .thenBy { it.createdAt },
        )

    /**
     * 필드 단위 병합. [chain] 은 우선순위 높은 순이라, 각 필드는 **처음 만나는 non-null** 이 이긴다.
     *
     * 끼니창은 쌍이 원자 단위다 — 시작만 재정의하고 끝은 상위값을 쓰면 `start >= end` 인 창이 만들어져
     * 그 끼니가 조용히 사라진다(DB CHECK 와 서비스 검증이 함께 막지만, 병합 규칙 자체도 쌍으로 다룬다).
     */
    private fun merge(
        base: MealPolicy,
        baseSource: PolicySource,
        chain: List<Pair<MealPolicyOverride, PolicySource>>,
    ): ResolvedMealPolicy {
        val sources = mutableMapOf<MealPolicyField, PolicySource>()

        fun <T : Any> resolveField(
            field: MealPolicyField,
            fallback: T,
            read: (MealPolicyOverride) -> T?,
        ): T {
            for ((override, source) in chain) {
                read(override)?.let {
                    sources[field] = source
                    return it
                }
            }
            sources[field] = baseSource
            return fallback
        }

        fun window(
            field: MealPolicyField,
            fallback: Pair<LocalTime, LocalTime>,
            read: (MealPolicyOverride) -> Pair<LocalTime, LocalTime>?,
        ): Pair<LocalTime, LocalTime> = resolveField(field, fallback, read)

        val perMeal = resolveField(MealPolicyField.PER_MEAL_LIMIT, base.perMealLimitMinor) { it.perMealLimitMinor }
        val daily = resolveField(MealPolicyField.DAILY_MEAL_COUNT, base.dailyMealCount) { it.dailyMealCount }
        val monthly = resolveField(MealPolicyField.MONTHLY_CAP, base.monthlyCapMinor) { it.monthlyCapMinor }
        val breakfast =
            window(
                MealPolicyField.BREAKFAST_WINDOW,
                base.breakfastStart to base.breakfastEnd,
            ) { o -> o.breakfastStart?.let { start -> o.breakfastEnd?.let { end -> start to end } } }
        val lunch =
            window(
                MealPolicyField.LUNCH_WINDOW,
                base.lunchStart to base.lunchEnd,
            ) { o -> o.lunchStart?.let { start -> o.lunchEnd?.let { end -> start to end } } }
        val dinner =
            window(
                MealPolicyField.DINNER_WINDOW,
                base.dinnerStart to base.dinnerEnd,
            ) { o -> o.dinnerStart?.let { start -> o.dinnerEnd?.let { end -> start to end } } }

        return ResolvedMealPolicy(
            policy =
                EffectiveMealPolicy(
                    perMealLimitMinor = perMeal,
                    dailyMealCount = daily,
                    monthlyCapMinor = monthly,
                    breakfastStart = breakfast.first,
                    breakfastEnd = breakfast.second,
                    lunchStart = lunch.first,
                    lunchEnd = lunch.second,
                    dinnerStart = dinner.first,
                    dinnerEnd = dinner.second,
                ),
            sources = sources,
        )
    }
}
