package com.taspa.server.admin

import com.taspa.server.common.export.CsvWriter
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import com.taspa.server.meal.MerchantConsoleService
import com.taspa.server.meal.dto.PlatformPayablesView
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * 전역 지급 현황 — **운영자의 자금 계획 도구**.
 *
 * 매장별 정산(`/api/merchant-console/{id}/settlement`)은 그 매장 사장이 자기 몫을 확인할 때 연다.
 * 이 표면은 반대 방향이다: 매장이 100개면 하나씩 열어 볼 수 없고, 열어 보지 않으면 이번 달 총 지급액을
 * 아무도 모른다.
 *
 * **실 자금이동은 없다** — 이 화면은 집계이고 실제 지급은 별도 절차다.
 * 읽기 전용이라 step-up 은 걸지 않는다(다른 조회 표면과 같은 판단).
 */
@RestController
@RequestMapping("/api/admin/payables")
class AdminPayablesController(
    private val merchantConsoleService: MerchantConsoleService,
) {
    /**
     * @param period 'YYYY-MM'. 생략하면 UTC 기준 **이번 달** — 지급은 진행 중인 달을 계획해야 하므로
     *   전역 대사(지난달 기본)와 기본값이 다르다. 매장 정산 화면의 기본값과도 같은 이유로 일치한다.
     */
    @PlatformAction(
        action = IamActions.PLATFORM_READ_PAYABLES,
        resource = "trn:taspa:platform::payables",
    )
    @GetMapping
    fun payables(
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<PlatformPayablesView> = ResponseEntity.ok(merchantConsoleService.platformPayables(resolvePeriod(period)))

    /**
     * 같은 집계의 CSV — 자금 담당이 이체 목록을 만들 때 쓴다. **숫자는 화면과 같은 호출에서 나온다.**
     *
     * action 만 조회와 다르다(`platform:ExportPayables`): 관리 표면은 핸들러당 action 이 유일해야 하고
     * (`PlatformSurfaceValidator` 가 기동 시 강제), 파일은 조직 밖으로 나가므로 화면 조회보다 큰 능력이다.
     */
    @PlatformAction(
        action = IamActions.PLATFORM_EXPORT_PAYABLES,
        resource = "trn:taspa:platform::payables",
    )
    @GetMapping("/csv")
    fun payablesCsv(
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<String> {
        val view = merchantConsoleService.platformPayables(resolvePeriod(period))
        val body =
            CsvWriter.render(
                listOf("기간", "가맹점", "타임존", "승인건수", "지급예정액(원)", "환불(원)"),
                view.lines.map {
                    listOf(
                        view.period,
                        it.merchantName,
                        it.timezone,
                        it.approvedCount,
                        it.payableMinor,
                        it.refundedMinor,
                    )
                },
            )
        return CsvWriter.download("taspa-지급현황-${view.period}.csv", body)
    }

    private fun resolvePeriod(period: String?): String = period?.takeIf { it.isNotBlank() } ?: YearMonth.now(ZoneOffset.UTC).toString()
}
