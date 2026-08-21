package com.taspa.server.iam

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.iam.IamPolicyRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.user.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * **IAM 편집은 자신을 잠글 수 없다** — 정책 편집 후 IAM 콘솔로 되돌아올 수 있는 사람이 최소 한 명은
 * 남아 있는지 확인한다.
 *
 * ## 왜 필요한가
 *
 * 인가 권위가 정책 엔진 하나로 수렴한 뒤(CLAUDE.md "엔진이 유일한 인가 권위"), 플랫폼 관리자는
 * **자기 권한을 스스로 좁힐 수 있다.** 명시 Deny 는 Allow 를 이기고 정책 간 순서도 무관하므로,
 * `{"Effect":"Deny","Action":"iam:*","Resource":"*"}` 한 줄을 저장하면 그 순간부터 **아무도 그 정책을
 * 지울 수 없다.** 체인의 `hasRole("ADMIN")` 은 심층 방어라 판정을 되돌리지 못하고, 복구 경로는
 * DB 직접 수정뿐이다 — 운영 중에는 사실상 서비스 정지에 가깝다.
 *
 * 조직 쪽에는 이미 같은 성질의 가드가 있다(`OrganizationService.guardLastAdmin` — 마지막 관리자
 * 락아웃 방지). 이 클래스는 그 개념을 **플랫폼 IAM 축**으로 옮긴 것이다.
 *
 * ## 어떻게 판정하는가 — 패턴 매칭이 아니라 **엔진으로 실제 평가**
 *
 * "위험한 문서"를 정규식으로 찾으려 하면 반드시 새는 형태가 나온다(와일드카드·조건·그룹 경유·중첩 부착).
 * 그래서 이 가드는 문서를 읽지 않는다. 변경을 **적용한 뒤**(같은 트랜잭션, 커밋 전) 활성 플랫폼
 * 관리자들에게 [RECOVERY_ACTIONS] 를 실제로 물어보고, 전부 통과하는 사람이 하나도 없으면 거절한다.
 * 거절은 예외이므로 트랜잭션이 롤백되고 변경은 남지 않는다.
 *
 * ## 평가 자체가 실패하면 — 막지 않는다(의도)
 *
 * 저장된 정책이 손상되면 `PrincipalPolicyResolver.parseOrFail` 이 던진다(fail-closed). 그 상태에서
 * 가드가 편집을 막으면 **손상된 정책을 지우는 것조차 불가능**해져, 가드가 막으려던 상태를 가드가
 * 만들어 낸다. 그래서 모든 관리자에 대해 평가가 실패하면 ERROR 로그를 남기고 통과시킨다 —
 * 그 상황에서 유일한 탈출구가 정책 편집이기 때문이다.
 */
@Component
class IamLockoutGuard(
    private val userRepository: UserRepository,
    private val policyRepository: IamPolicyRepository,
    private val authorizationService: IamAuthorizationService,
) {
    private val log = LoggerFactory.getLogger(IamLockoutGuard::class.java)

    /**
     * 변경을 적용한 **뒤** 호출한다(커밋 전). 복구 가능한 관리자가 없으면 [ErrorCode.IAM_LOCKOUT].
     */
    fun verifyRecoverable() {
        // 정지된 관리자는 로그인할 수 없으므로 복구 경로가 아니다 — 세면 거짓 안심을 준다.
        val admins = userRepository.findIdsByRoleAndStatus(UserRole.ADMIN.name, UserStatus.ACTIVE.name)
        if (admins.isEmpty()) {
            // 부트스트랩 전(첫 관리자 지정 이전)에는 잠글 대상이 없다 — 여기서 막으면 초기 시딩이 깨진다.
            return
        }

        val outcomes = admins.map { userId -> runCatching { canRecover(userId) } }
        // ★실패를 **세지 않고** 삼키면, 일부 관리자만 평가에 실패했을 때 가드가 '락아웃 방지'라는
        //   잘못된 이유로 편집을 막고 진짜 원인(정책 데이터 손상)은 어디에도 드러나지 않는다.
        //   전역 순회 3종이 scanned/skipped/failed 를 함께 내려보내는 것과 같은 규약이다.
        val failed = outcomes.count { it.isFailure }
        if (failed > 0) {
            log.warn(
                "IAM lockout guard: {} of {} platform admin(s) could not be evaluated (corrupted policy?). First: {}",
                failed,
                admins.size,
                outcomes.firstOrNull { it.isFailure }?.exceptionOrNull()?.message,
            )
        }
        if (outcomes.any { it.getOrDefault(false) }) return

        if (outcomes.none { it.isSuccess }) {
            // 저장된 정책이 손상돼 아무도 평가할 수 없는 상태. 위 주석의 이유로 통과시킨다.
            log.error(
                "IAM lockout guard could not evaluate any of {} platform admin(s) — allowing the change so a " +
                    "corrupted policy can still be repaired. First failure: {}",
                admins.size,
                outcomes.first().exceptionOrNull()?.message,
            )
            return
        }

        // 운영자가 "정말 아무도 없다"와 "판단할 수 없었다"를 구분할 수 있어야 한다.
        if (failed > 0) {
            throw AuthException(
                ErrorCode.IAM_LOCKOUT,
                "이 변경을 적용하면 IAM 정책을 관리할 수 있는 플랫폼 관리자가 남지 않습니다" +
                    "(관리자 ${admins.size}명 중 ${failed}명은 정책 손상으로 평가하지 못했습니다).",
            )
        }
        throw AuthException(ErrorCode.IAM_LOCKOUT)
    }

    /**
     * 이 관리자가 **IAM 콘솔로 돌아와 정책을 되돌릴 수 있는가**.
     *
     * step-up 은 `true` 로 둔다 — 복구는 재인증을 거친 세션에서 일어나므로, step-up 조건이 붙은 Deny 를
     * "지금 step-up 이 없다"는 이유로 통과시켜 **거짓 안심**을 주면 안 된다.
     */
    private fun canRecover(userId: java.util.UUID): Boolean =
        recoveryProbes(userId).all { (action, resource) ->
            val request =
                AuthorizationRequest(
                    action,
                    resource,
                    mapOf(
                        IamContextKeys.PRINCIPAL_TYPE to IamPrincipalKind.SESSION.name,
                        IamContextKeys.STEP_UP_PRESENT to "true",
                    ),
                )
            // 관리 표면의 유일한 Allow 원천은 플랫폼 관리자 브리지다(org/merchant 앵커는 넘기지 않는다 —
            // CLAUDE.md "decideSession 에 orgAdminOf/memberOf/merchantAdminOf 를 넘기지 않는다").
            authorizationService
                .decideSession(userId, platformAdmin = true, orgAdminOf = null, memberOf = null, request = request)
                .effect == DecisionEffect.ALLOW
        }

    /**
     * **잘못 저장한 정책을 콘솔에서 되돌리는 데 필요한 최소 능력.**
     *
     * 하나라도 막히면 복구가 성립하지 않는다: 화면에 못 들어가거나(AccessConsole), 무엇이 문제인지
     * 못 보거나(ListPolicies), 고칠 수 없다(Update/Delete/Detach/RemoveInline).
     * 정책은 managed 부착·inline 두 경로로 붙으므로 **양쪽 해제 능력이 모두** 있어야 한다.
     *
     * ★★**프로브 리소스는 집행이 실제로 렌더하는 형태와 같아야 한다.** 예전엔 inline 해제를
     * principal 타입·id 자리에 별표를 넣은 **와일드카드 리터럴**로 물었는데, 집행은
     * `trn:taspa:iam::principal/USER/{uuid}` 로 렌더한다. 그래서 Resource 를
     * `trn:taspa:iam::principal/USER/` + 별표로 좁게 겨냥한 Deny 는 그 리터럴 값과 매치하지 않아
     * **가드에는 보이지 않고**, 정작 실제 복구 요청은 구체 TRN 으로 렌더돼 403 이 된다 —
     * 가드를 통과한 변경이 복구 경로만 정확히 끊는다.
     * 그래서 **구체 TRN(평가 대상 관리자 자신 · 실제 정책 id)** 과 컬렉션 와일드카드를 **둘 다** 묻는다.
     * 와일드카드 쪽은 넓은 Deny 를, 구체 쪽은 좁은 Deny 를 잡는다.
     *
     * ★주의: KDoc 안에 슬래시+별표 시퀀스를 쓰지 말 것 — Kotlin 은 블록 주석 **중첩**을 지원해서
     * 그 두 글자가 새 주석을 열고, 파일 끝에서 "Unclosed comment" 로 컴파일이 깨진다(실제로 겪었다).
     */
    private fun recoveryProbes(userId: java.util.UUID): List<Pair<String, String>> {
        val probes =
            mutableListOf(
                IamActions.PLATFORM_ACCESS_CONSOLE to Trn.platformConsole("iam"),
                IamActions.IAM_LIST_POLICIES to Trn.iamPolicies(),
                IamActions.IAM_UPDATE_POLICY to Trn.iamPolicies(),
                IamActions.IAM_DELETE_POLICY to Trn.iamPolicies(),
                IamActions.IAM_DETACH_POLICY to Trn.iamPolicies(),
                // inline 해제는 **이 관리자 자신의 principal TRN** 으로 — 자기 inline Deny 가 놓이는 자리다.
                IamActions.IAM_REMOVE_INLINE_POLICY to
                    Trn.iamPrincipal(IamPrincipalType.USER.name, userId.toString()),
                IamActions.IAM_SET_INLINE_POLICY to
                    Trn.iamPrincipal(IamPrincipalType.USER.name, userId.toString()),
            )
        // 저장된 각 정책의 **구체 id** 로도 묻는다 — `policy/{uuid}` 를 겨냥한 Deny 는 `policy/*` 프로브에
        // 걸리지 않는다. 정책 수는 관리 대상이라 소수이고, 이 질의는 IAM 편집 시에만 일어난다.
        policyRepository.findAll().mapNotNull { it.id }.forEach { policyId ->
            probes += IamActions.IAM_UPDATE_POLICY to Trn.iamPolicy(policyId.toString())
            probes += IamActions.IAM_DELETE_POLICY to Trn.iamPolicy(policyId.toString())
        }
        return probes
    }
}
