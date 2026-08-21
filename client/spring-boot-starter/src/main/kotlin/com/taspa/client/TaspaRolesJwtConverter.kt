package com.taspa.client

import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter

/**
 * taspa 토큰의 **조직 커스텀 역할** 클레임을 Spring Security 권한으로 옮긴다.
 *
 * 이게 없으면 연동 서비스는 `jwt.getClaim("roles")` 를 화면·컨트롤러마다 직접 파싱하게 되고,
 * 그때마다 아래 두 함정(대표 org 판정·복수 org)을 각자 다시 만난다.
 *
 * 방출하는 권한은 두 종류다:
 * - `SCOPE_*` — Spring 기본 변환기가 만드는 scope 권한(그대로 유지한다. 이 변환기는 **추가**만 한다).
 * - `{prefix}{역할이름}` — 기본 접두사 `ROLE_` 이라 `hasRole("회계 담당")` 으로 쓸 수 있다.
 *
 * ## org 를 지정해야 하는 이유
 *
 * 역할 이름은 **조직 안에서만** 유일하다. A사의 "회계 담당"과 B사의 "회계 담당"은 다른 역할이고,
 * 한 사용자가 두 조직에 속할 수 있다. 그래서 [TaspaClientProperties.orgId] 를 설정하면 그 조직의
 * 역할만 읽고, 설정하지 않으면 토큰의 **대표 org**(`org_id` 와 짝인 `roles`)만 읽는다.
 * ★두 경우 모두 **여러 조직의 역할을 합치지 않는다** — 합치면 B사 소속으로 얻은 역할 이름이
 * A사 자원에 대한 권한으로 쓰인다.
 */
class TaspaRolesJwtConverter(
    private val rolePrefix: String = "ROLE_",
    /** 이 서비스가 담당하는 조직(UUID 문자열). null 이면 토큰의 대표 org 를 쓴다. */
    private val orgId: String? = null,
) : Converter<Jwt, Collection<GrantedAuthority>> {

    private val scopes = JwtGrantedAuthoritiesConverter()

    override fun convert(jwt: Jwt): Collection<GrantedAuthority> =
        scopes.convert(jwt).orEmpty() + rolesOf(jwt).map { SimpleGrantedAuthority(rolePrefix + it) }

    private fun rolesOf(jwt: Jwt): List<String> {
        if (orgId == null) return stringList(jwt.getClaim<Any?>(ROLES_CLAIM))
        // 지정 org 가 토큰의 대표 org 면 `roles` 가 그 값이다(복수 org 가 아니면 org_roles 는 없다).
        if (orgId == jwt.getClaimAsString(ORG_ID_CLAIM)) {
            return stringList(jwt.getClaim<Any?>(ROLES_CLAIM))
        }
        val perOrg = jwt.getClaim<Any?>(ORG_ROLES_CLAIM) as? Collection<*> ?: return emptyList()
        val match = perOrg.filterIsInstance<Map<*, *>>().firstOrNull { it["org"] == orgId } ?: return emptyList()
        return stringList(match["roles"])
    }

    /** 클레임 모양이 예상과 다르면 **권한을 주지 않는다**(fail-closed) — 추측해서 채우지 않는다. */
    private fun stringList(value: Any?): List<String> =
        (value as? Collection<*>)?.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }.orEmpty()

    companion object {
        const val ROLES_CLAIM = "roles"
        const val ORG_ROLES_CLAIM = "org_roles"
        const val ORG_ID_CLAIM = "org_id"
    }
}
