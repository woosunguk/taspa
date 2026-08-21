package com.taspa.server.iam

/**
 * 관리 콘솔 경로 패턴의 **단일 출처**.
 *
 * `SecurityConfig`(체인 매처)·`WebMvcConfig`(인터셉터 등록)·`PlatformSurfaceValidator`(선언 검증)가
 * 같은 목록을 봐야 한다. 세 곳에 문자열을 따로 쓰면 하나만 넓어지거나 좁아졌을 때 **그 차집합이 곧
 * 인가 공백**이 된다 — 체인은 막는데 인터셉터는 안 도는(또는 그 반대) 경로가 생긴다.
 */
object AdminSurface {
    /** Spring 경로 패턴(체인 매처·인터셉터 등록용). `/admin` 자체와 그 이하, 그리고 관리 API. */
    val PATH_PATTERNS = arrayOf("/admin", "/admin/**", "/api/admin/**")

    /**
     * 매핑 문자열이 이 표면에 속하는지 — validator 가 핸들러 전수를 걸러낼 때 쓴다.
     * Spring 패턴 매칭이 아니라 접두 비교다(validator 는 등록된 패턴 문자열을 직접 다룬다).
     */
    fun matches(path: String): Boolean = path == "/admin" || path.startsWith("/admin/") || path.startsWith("/api/admin/")
}
