package com.taspa.server.admin

import com.taspa.server.billing.InvoiceService
import com.taspa.server.billing.dto.UnfinalizedInvoicesView
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
 * 미확정 청구서 현황 — **자동 생성 루프의 마지막 구멍**.
 *
 * 초안은 매일 자동으로 만들어지고 조직관리자에게 메일까지 나간다. 그런데 그 사람이 확정하지 않으면
 * 청구서는 그대로 방치되고, 회사가 쓴 식대를 우리가 **끝내 청구하지 않는다** — 자동 생성 잡이 막으려던
 * 사고(알람이 울리지 않는 매출 누락)가 한 단계 뒤에서 그대로 반복되는 셈이다. 이 표면이 그 지점을 연다.
 */
@RestController
@RequestMapping("/api/admin/invoices/unfinalized")
class AdminInvoiceStatusController(
    private val invoiceService: InvoiceService,
) {
    /**
     * @param period 'YYYY-MM'. 생략하면 UTC 기준 **지난달** — 이번 달은 아직 확정할 시점이 아니므로
     *   기본값으로 부적절하다(지급 현황이 이번 달 기본인 것과 반대 이유).
     */
    @PlatformAction(
        action = IamActions.PLATFORM_READ_UNFINALIZED_INVOICES,
        resource = "trn:taspa:platform::invoices",
    )
    @GetMapping
    fun unfinalized(
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<UnfinalizedInvoicesView> {
        val target =
            period?.takeIf { it.isNotBlank() }
                ?: YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString()
        return ResponseEntity.ok(invoiceService.listUnfinalized(target))
    }
}
