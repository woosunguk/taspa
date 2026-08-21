package com.taspa.server.admin

import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** 관리 콘솔 페이지 — ADMIN 전용(SecurityConfig). 데이터 갱신은 페이지 JS 가 admin API 로 수행한다. */
@Controller
@PlatformAction(
    action = IamActions.PLATFORM_ACCESS_CONSOLE,
    resource = "trn:taspa:platform::console/page",
)
class AdminPageController(
    private val userRepository: UserRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val adminAuditService: AdminAuditService,
) {
    // 대시보드는 페이지 진입이 아니라 집계·최근 감사를 렌더하는 데이터 표면이라 별도 action 으로 가른다.
    @PlatformAction(
        action = IamActions.PLATFORM_READ_CONSOLE_DASHBOARD,
        resource = "trn:taspa:platform::dashboard",
    )
    @GetMapping("/admin")
    fun dashboard(model: Model): String {
        model.addAttribute("userCount", userRepository.count())
        model.addAttribute(
            "clientCount",
            jdbcTemplate.queryForObject("SELECT count(*) FROM oauth2_registered_client", Long::class.javaObjectType),
        )
        // 활성 세션 = SPRING_SESSION 에서 만료 시각(epoch millis)이 아직 지나지 않은 행.
        model.addAttribute(
            "activeSessionCount",
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spring_session WHERE expiry_time > ?",
                Long::class.javaObjectType,
                System.currentTimeMillis(),
            ),
        )
        model.addAttribute("recentEvents", adminAuditService.search(null, null, RECENT_EVENT_LIMIT, 0))
        return "admin/dashboard"
    }

    @GetMapping("/admin/clients")
    fun clients(): String = "admin/clients"

    @GetMapping("/admin/orgs")
    fun orgs(): String = "admin/orgs"

    @GetMapping("/admin/calendar")
    fun calendar(): String = "admin/calendar"

    @GetMapping("/admin/merchants")
    fun merchants(): String = "admin/merchants"

    @GetMapping("/admin/users")
    fun users(): String = "admin/users"

    @GetMapping("/admin/sso")
    fun sso(): String = "admin/sso"

    @GetMapping("/admin/audit")
    fun audit(): String = "admin/audit"

    companion object {
        private const val RECENT_EVENT_LIMIT = 10
    }
}
