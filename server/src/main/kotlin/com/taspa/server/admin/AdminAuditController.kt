package com.taspa.server.admin

import com.taspa.server.admin.dto.AdminAuditEventView
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 감사 로그 조회 API — ADMIN 전용(SecurityConfig). 읽기 전용이라 step-up 은 걸지 않는다. */
@RestController
@RequestMapping("/api/admin/audit")
class AdminAuditController(
    private val adminAuditService: AdminAuditService,
) {
    // org 콘솔의 org:ReadAudit 을 재사용하지 않는다 — 그쪽은 org 격리 + 행위자 이메일 마스킹이 걸려
    // 있고 이 표면은 전역·무마스킹이라 능력의 크기가 다르다.
    @PlatformAction(
        action = IamActions.PLATFORM_READ_GLOBAL_AUDIT,
        resource = "trn:taspa:platform::audit",
    )
    @GetMapping
    fun search(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) email: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<List<AdminAuditEventView>> = ResponseEntity.ok(adminAuditService.search(type, email, limit, offset))
}
