package com.taspa.server.org

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * 자율 콘솔 페이지 — 데이터는 페이지 JS 가 /api/orgs/mine·/api/orgs/{id}/members·/invitations 로 채운다.
 * 미인증 접근은 default 체인의 anyRequest().authenticated() 가 /login 으로 유도한다. ORG_ADMIN 이 아니면
 * /api/orgs/mine 이 빈 목록을 반환해 "관리 권한 없음" 빈 상태를 보여준다(페이지 자체는 인증만으로 접근 가능).
 */
@Controller
class OrgConsolePageController {
    @GetMapping("/console/orgs")
    fun orgs(): String = "console/orgs"
}
