package com.taspa.server.iam

import org.springframework.stereotype.Component

/**
 * 정책 평가 엔진 — AWS IAM 판정 규칙 그대로.
 *  1. 기본값 = 암묵적 거부.
 *  2. 적용 가능한(action∧resource∧condition 매치) 명시적 Deny 가 하나라도 있으면 → 거부(다른 Allow 무시).
 *  3. Deny 없고 적용 가능한 Allow 가 있으면 → 허용.
 *  4. 그 외 → 거부.
 *
 * 상태가 없는 순수 함수이며, 부여할 정책 집합(effective policies)은 호출자(PrincipalPolicyResolver)가 조립한다.
 *
 * ★테넌시: 평가 직전 요청 리소스에서 org 세그먼트를 **구조적으로** 뽑아 `taspa:ResourceOrg` context 키를
 *   권위적으로 채운다(호출자가 넘긴 동명 키는 덮어써 스푸핑을 차단). org 스코프 정책은 이 키에 대한 정확일치
 *   조건으로 테넌시를 강제하므로, `*` 가 `:` 경계를 넘는 글롭 특성에 org 경계를 의존시키지 않는다.
 */
@Component
class PolicyEvaluator {
    fun evaluate(
        policies: List<PolicyDocument>,
        request: AuthorizationRequest,
    ): Decision {
        // ★스코프 조건키는 **먼저 지우고** 엔진이 다시 채운다. `+` 로 덮어쓰기만 하면 새 키를 추가할 때마다
        // "덮어쓰는 것을 잊지 않았나"를 사람이 확인해야 하는데, 한 번 잊으면 호출자가 자기 권한 밖 자원을
        // 자기 것처럼 위장할 수 있다. 지우는 쪽이 기본값이 안전하다(빠뜨리면 키가 없어 Allow 미적용).
        val effectiveContext =
            (request.context - SCOPE_OWNED_KEYS) +
                buildMap {
                    put(RESOURCE_ORG_KEY, Trn.orgSegmentOf(request.resource))
                    // 부서 축이 없는 자원은 키를 방출하지 않는다 — 위임 정책의 양성 조건이 미적용돼 거부로 수렴한다.
                    request.scope.departmentPath?.let { put(IamContextKeys.RESOURCE_DEPARTMENT_PATH, it) }
                }

        var firstAllow: Statement? = null
        for (document in policies) {
            for (statement in document.statements) {
                if (!applies(statement, request.action, request.resource, effectiveContext)) continue
                if (statement.effect == Effect.DENY) {
                    return Decision(
                        effect = DecisionEffect.DENY,
                        reason = "explicit deny (sid=${statement.sid ?: "-"})",
                        matchedSid = statement.sid,
                    )
                }
                if (firstAllow == null) firstAllow = statement
            }
        }
        return if (firstAllow != null) {
            Decision(DecisionEffect.ALLOW, "allowed (sid=${firstAllow.sid ?: "-"})", firstAllow.sid)
        } else {
            Decision(DecisionEffect.DENY, "implicit deny (no matching allow)", null)
        }
    }

    private fun applies(
        statement: Statement,
        action: String,
        resource: String,
        context: Map<String, String>,
    ): Boolean =
        PolicyMatching.actionMatches(statement.actions, action) &&
            PolicyMatching.resourceMatches(statement.resources, resource, context) &&
            ConditionEvaluator.satisfied(statement.conditions, context, statement.effect == Effect.DENY)

    companion object {
        /** 리소스 소유 org(TRN 4번째 세그먼트). 엔진이 권위적으로 설정하는 예약 조건 키. */
        const val RESOURCE_ORG_KEY = IamContextKeys.RESOURCE_ORG

        /**
         * **엔진 소유** 조건키 — 평가 직전에 컨텍스트에서 제거된 뒤 엔진이 다시 채운다.
         * 호출자가 이 키들을 문자열로 넣어도 무시된다(테넌시·스코프 스푸핑 차단).
         * 새 스코프 키를 추가하면 **반드시 여기에도 넣을 것**.
         */
        val SCOPE_OWNED_KEYS =
            setOf(
                IamContextKeys.RESOURCE_ORG,
                IamContextKeys.RESOURCE_DEPARTMENT_PATH,
            )
    }
}
