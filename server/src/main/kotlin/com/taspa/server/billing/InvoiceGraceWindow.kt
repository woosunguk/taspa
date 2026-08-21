package com.taspa.server.billing

import com.taspa.server.meal.MealPolicyCalculus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.YearMonth

/**
 * 월 청구서 자동 생성의 **유예 기간** — 달이 바뀐 직후 며칠은 아직 초안을 만들지 않는다.
 *
 * 경계 직전 거래의 POS 재전송·취소가 며칠 늦게 도착할 수 있고, 잡은 "없을 때만 만든다"는 규칙이라
 * 첫 초안이 옛 숫자로 굳는다. 며칠 기다렸다 만드는 편이 정확하다.
 *
 * ★**한 곳에서만 정의한다.** 이 판정이 잡(생성)과 현황 조회(미확정 추적) 두 곳에 각각 있으면,
 * 매달 1~2일에 현황 화면이 "아직 만들 시점이 아닌" 조직 전부를 **"청구서 없음"(시스템이 못 만들었다)**
 * 적색 경보로 띄운다. 조직이 100개면 100줄이 거짓 경보이고, 그 사이에 있는 진짜 실패 한 건이 묻힌다.
 * 자동 생성 잡이 막으려던 것이 바로 그 "묻혀서 아무도 모르는" 상태다.
 */
@Component
class InvoiceGraceWindow(
    @Value("\${taspa.billing.auto-generate-grace-days:2}")
    val graceDays: Long,
) {
    /**
     * `target` 달의 초안을 **아직 만들 시점이 아닌가**(그 조직의 로컬 달력 기준).
     *
     * 유예는 **직전 달**에만 의미가 있다 — 그보다 과거 달인데 청구서가 없으면 유예가 아니라 누락이다.
     */
    fun notYetDue(
        timezone: String,
        target: YearMonth,
    ): Boolean {
        val today = LocalDate.now(MealPolicyCalculus.zoneOf(timezone))
        if (YearMonth.from(today).minusMonths(1) != target) return false
        return today.dayOfMonth <= graceDays
    }
}
