package com.taspa.server.admin

import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** IAM 정책 관리 콘솔 페이지 — ADMIN 전용(SecurityConfig). 데이터는 페이지 JS 가 /api/admin/iam 으로 수행한다. */
@Controller
class AdminIamPageController {
    @PlatformAction(
        action = IamActions.PLATFORM_ACCESS_CONSOLE,
        resource = "trn:taspa:platform::console/iam",
    )
    @GetMapping("/admin/iam")
    fun iam(): String = "admin/iam"
}
