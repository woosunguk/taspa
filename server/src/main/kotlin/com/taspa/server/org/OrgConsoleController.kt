package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.org.dto.AdministeredOrgView
import com.taspa.server.org.dto.MyMembershipView
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 자율 콘솔 진입 API — 로그인 사용자가 ORG_ADMIN 으로 관리하는 조직 목록을 반환한다. 세션 전용 콘솔이므로
 * 위임 베어러는 거부한다(멤버 관리 API 와 동일한 confused-deputy 방지). 미인증은 조직 스코프 API 체인이 차단한다.
 *
 * 경로 주의: `/api/orgs/mine` 은 정확 경로라 `/api/orgs/{orgId}/...` 패턴보다 우선 매칭된다(orgId 로 오인 없음).
 *
 * **IAM 엔진 이관 제외**: 두 엔드포인트 모두 org 앵커(경로 orgId)가 없어 리소스 TRN 을 만들 수 없고,
 * 인가는 "로그인 사용자 본인 소유 행만 반환"하는 행단위 소유권 필터(listAdministeredOrgs·listMyMemberships)가
 * 담당한다 — 요청 단위 allow/deny 판정 대상이 아니므로 정책 엔진을 태우지 않는다.
 */
@RestController
class OrgConsoleController(
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
) {
    @GetMapping("/api/orgs/mine")
    fun mine(authentication: Authentication): ResponseEntity<List<AdministeredOrgView>> {
        if (authentication is JwtAuthenticationToken) {
            throw AuthException(ErrorCode.FORBIDDEN, "콘솔은 세션 인증에서만 가능합니다")
        }
        val user = userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)
        return ResponseEntity.ok(organizationService.listAdministeredOrgs(user.id!!))
    }

    /**
     * 계정 페이지 "내 조직"(읽기 전용) — 로그인 사용자가 소속된 활성 조직 목록을 반환한다(역할·부서 포함).
     * 세션 사용자 본인 데이터만 반환하고 위임 베어러는 거부한다(/mine 과 동일한 confused-deputy 방지).
     * step-up 불요(읽기 전용). 정확 경로라 `/api/orgs/{orgId}/...` 보다 우선 매칭된다(orgId 로 오인 없음).
     */
    @GetMapping("/api/orgs/memberships")
    fun memberships(authentication: Authentication): ResponseEntity<List<MyMembershipView>> {
        if (authentication is JwtAuthenticationToken) {
            throw AuthException(ErrorCode.FORBIDDEN, "콘솔은 세션 인증에서만 가능합니다")
        }
        val user = userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)
        return ResponseEntity.ok(organizationService.listMyMemberships(user.id!!))
    }
}
