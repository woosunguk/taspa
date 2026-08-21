package com.taspa.server.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 관리 콘솔 action 네임스페이스의 **격리 불변식**. 순수 단위 테스트(Docker 불필요).
 *
 * ★이 테스트가 지키는 것이 이 설계의 유일한 방어선이다.
 *
 * 브리지의 org 스코프 문장은 리소스 글롭 `trn:taspa:*:{org}:*` 를 쓰는데, 관리 자원의 TRN
 * (`trn:taspa:platform:{org}:...`)도 그 글롭에 **매치한다**. 즉 조직관리자가 관리 조작에 도달하지
 * 못하는 이유는 리소스 축이 아니라 **action 축이 겹치지 않는다는 사실 하나**다. 여기서 교집합이
 * 생기는 순간, 조직관리자가 자기 org 에 대해 도메인 force-verify(자동가입 보안앵커 우회)나
 * org 정지 자가 해제 같은 플랫폼 전용 조작을 얻는다.
 *
 * 그래서 이 테스트는 "정리" 명목으로도 삭제하면 안 된다.
 */
class PlatformActionNamespaceTest {
    @Test
    fun `관리 콘솔 action 은 조직 멤버 action 과 겹치지 않는다`() {
        assertThat(IamActions.PLATFORM_CONSOLE_ACTIONS.intersect(IamActions.ORG_MEMBER_ACTIONS.toSet()))
            .describedAs("멤버 action 과 겹치면 **일반 직원 전원**이 그 관리 조작을 얻는다")
            .isEmpty()
    }

    @Test
    fun `관리 콘솔 action 은 조직관리자 action 과 겹치지 않는다`() {
        assertThat(IamActions.PLATFORM_CONSOLE_ACTIONS.intersect(IamActions.ORG_ADMIN_ACTIONS.toSet()))
            .describedAs("ORG_ADMIN 과 겹치면 조직관리자가 임의 org 관리 조작에 도달한다")
            .isEmpty()
    }

    @Test
    fun `조직 action 목록에 platform 네임스페이스가 섞이지 않는다`() {
        // 반대 방향 가드. 위 두 테스트는 집합 교집합만 보므로, 누가 ORG_ADMIN_ACTIONS 에
        // "platform:..." 문자열을 직접 적어 넣는 실수는 이 단언이 잡는다.
        assertThat(IamActions.ORG_ADMIN_ACTIONS + IamActions.ORG_MEMBER_ACTIONS)
            .noneMatch { it.startsWith(PLATFORM_PREFIX) || it.startsWith(IAM_PREFIX) }
    }

    @Test
    fun `관리 콘솔 action 은 platform 또는 iam 네임스페이스뿐이다`() {
        assertThat(IamActions.PLATFORM_CONSOLE_ACTIONS)
            .allMatch { it.startsWith(PLATFORM_PREFIX) || it.startsWith(IAM_PREFIX) }
    }

    @Test
    fun `관리 콘솔 action 에 중복이 없다`() {
        // 상수 목록에 같은 문자열이 두 번 들어가면, 기동 검증기의 "핸들러 간 중복" 검사와 무관하게
        // 목록 자체가 신뢰를 잃는다(어느 쪽이 의도였는지 알 수 없다).
        assertThat(IamActions.PLATFORM_CONSOLE_ACTIONS).doesNotHaveDuplicates()
    }

    @Test
    fun `플랫폼 관리자 제외 목록은 관리 콘솔 action 을 담지 않는다`() {
        // 제외 목록은 명시 Deny 로 들어가고 **멤버 Allow 도 이긴다**. 여기에 관리 콘솔 action 이
        // 들어가면 그 표면이 모든 플랫폼 관리자에게 닫혀 관리 콘솔 자체가 잠긴다.
        assertThat(IamActions.PLATFORM_ADMIN_EXCLUDED_ACTIONS.intersect(IamActions.PLATFORM_CONSOLE_ACTIONS.toSet()))
            .isEmpty()
    }

    private companion object {
        const val PLATFORM_PREFIX = "platform:"
        const val IAM_PREFIX = "iam:"
    }
}
