package com.taspa.server.iam

import java.util.UUID

/**
 * 요청 대상 자원의 **조직 내 위치**. 부서 서브트리 위임(부서장이 자기 조직만 관리)의 판정 입력이다.
 *
 * ★왜 값 객체인가 — 이 정보는 조건키로 정책에 노출되는데, 호출자가 컨텍스트 맵에 문자열로 넣게 두면
 * `taspa:ResourceDepartmentPath` 를 `"/{내가원하는부서}/"` 로 지어내 **자기 권한 밖 자원을 자기 것처럼**
 * 만들 수 있다. `taspa:ResourceOrg` 가 리소스 TRN 에서 구조적으로 도출되는 것과 같은 이유로, 이 키도
 * 엔진이 [PolicyEvaluator.SCOPE_OWNED_KEYS] 를 **지운 뒤** 이 객체에서만 채운다. 타입이 그 경계를
 * 눈에 보이게 만든다: 스코프를 넣으려면 이 객체를 만들어야 하고, 그러려면 대상을 DB 에서 조회해야 한다.
 *
 * @param departmentPath 루트부터 자신까지의 부서 경로. 형식은 `/{root}/{...}/{self}/` —
 *   **앞뒤 구분자가 필수**다. 정책 조건은 `StringLike` 글롭 `(별표)/{deptId}/(별표)` 로 서브트리 포함을 판정하는데,
 *   구분자가 없으면 경로 끝의 부서가 매치되지 않는다(`/a/b` 는 그 글롭에 안 걸린다).
 */
data class ResourceScope(
    val departmentPath: String? = null,
) {
    companion object {
        /** 부서 축이 없는 자원(조직 전체 설정, 청구서 등). 키를 방출하지 않는다 → 위임 정책은 미적용. */
        val NONE = ResourceScope()

        const val SEPARATOR = "/"

        /**
         * 루트→자신 순서의 id 목록을 경로 문자열로. 빈 목록이면 [NONE] 과 같은 의미(키 없음)다.
         *
         * ★부서 축이 **없는 것**과 **비어 있는 것**을 구분하지 않는 게 중요하다. 미배정 멤버에게
         * 빈 경로 `"/"` 를 주면 정책 글롭 `(별표)/{dept}/(별표)` 는 걸리지 않으니 결과는 같지만, "키가 있는데
         * 안 걸림"과 "키가 없음"은 조건 평가에서 다르게 취급될 수 있다(음성 연산자·IfExists).
         * 하나로 수렴시켜 그 미묘한 갈래를 아예 만들지 않는다.
         */
        fun ofDepartmentChain(chain: List<UUID>): ResourceScope =
            if (chain.isEmpty()) {
                NONE
            } else {
                ResourceScope(chain.joinToString(SEPARATOR, prefix = SEPARATOR, postfix = SEPARATOR))
            }

        /** 이 부서(및 그 하위 전체)를 포함하는 정책 조건 글롭. */
        fun subtreeGlob(departmentId: UUID): String = "*$SEPARATOR$departmentId$SEPARATOR*"
    }
}
