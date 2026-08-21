package com.taspa.server.admin

import com.taspa.server.billing.ReconciliationService
import com.taspa.server.billing.dto.PlatformReconciliationView
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * 전역 정합성 대사 — **운영자의 조기 경보**.
 *
 * 조직별 대사(`/api/orgs/{org}/reconciliation`)는 그 조직 관리자가 자기 청구서를 의심할 때 보는
 * 화면이다. 이 표면은 반대 방향이다: 시스템이 어딘가에서 깨졌는지를 **운영자가 조직 수와 무관하게**
 * 한 번에 확인한다. 조직이 100개면 하나씩 열어 볼 수 없고, 열어 보지 않으면 아무도 모른다.
 *
 * 읽기 전용이라 step-up 은 걸지 않는다(다른 조회 표면과 같은 판단).
 */
@RestController
@RequestMapping("/api/admin/reconciliation")
class AdminReconciliationController(
    private val reconciliationService: ReconciliationService,
) {
    /** @param period 'YYYY-MM'. 생략하면 UTC 기준 지난달(이번 달은 아직 쌓이는 중이라 기본값으로 부적절). */
    @PlatformAction(
        action = IamActions.PLATFORM_READ_RECONCILIATION,
        resource = "trn:taspa:platform::reconciliation",
    )
    @GetMapping
    fun reconcileAll(
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<PlatformReconciliationView> {
        val target =
            period?.takeIf { it.isNotBlank() }
                ?: YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString()
        return ResponseEntity.ok(reconciliationService.reconcileAll(target))
    }
}
