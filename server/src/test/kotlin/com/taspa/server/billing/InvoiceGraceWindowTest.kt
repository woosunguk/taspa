package com.taspa.server.billing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 유예 기간 판정 — 순수 단위 테스트.
 *
 * ★이 판정이 없으면 미확정 청구서 화면이 **매달 1~2일에 모든 조직을 "청구서 없음" 적색 경보로** 띄운다
 * (그 시점엔 잡이 아직 아무것도 만들지 않았으니까). 조직이 100개면 100줄이 거짓 경보이고, 그 속에서
 * 진짜 실패 한 건이 묻힌다 — 자동 생성 잡이 막으려던 "묻혀서 아무도 모르는" 상태 그대로다.
 *
 * 날짜에 의존하지 않도록 **오늘 날짜에서 역산해** 케이스를 만든다. 특정 날짜에만 통과하는 테스트는
 * 이 코드베이스가 반복해서 당한 형태다.
 */
class InvoiceGraceWindowTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `유예는 직전 달에만 적용된다`() {
        val today = LocalDate.now(seoul)
        val previous = YearMonth.from(today).minusMonths(1)
        val twoMonthsAgo = YearMonth.from(today).minusMonths(2)

        // 유예를 31일로 두면 "직전 달"은 오늘이 며칠이든 항상 유예 안이다.
        val alwaysWithin = InvoiceGraceWindow(graceDays = 31)
        assertThat(alwaysWithin.notYetDue("Asia/Seoul", previous)).isTrue()
        // ★그보다 과거 달은 유예가 아니라 **누락**이다 — 여기서 true 가 나오면 오래된 미청구가 영영 숨는다.
        assertThat(alwaysWithin.notYetDue("Asia/Seoul", twoMonthsAgo)).isFalse()
    }

    @Test
    fun `유예 0 이면 직전 달도 언제나 생성 시점이다(대조군)`() {
        // 대조군이 없으면 위 테스트가 "항상 true"인지 "유예라서 true"인지 구별되지 않는다.
        val noGrace = InvoiceGraceWindow(graceDays = 0)
        val previous = YearMonth.from(LocalDate.now(seoul)).minusMonths(1)
        assertThat(noGrace.notYetDue("Asia/Seoul", previous)).isFalse()
    }

    @Test
    fun `이번 달은 유예 대상이 아니다`() {
        // 이번 달 청구서는 애초에 자동 생성 대상이 아니다(달이 아직 안 끝났다).
        val window = InvoiceGraceWindow(graceDays = 31)
        val thisMonth = YearMonth.from(LocalDate.now(seoul))
        assertThat(window.notYetDue("Asia/Seoul", thisMonth)).isFalse()
    }

    @Test
    fun `깨진 타임존은 UTC 로 낙하해 판정을 멈추지 않는다`() {
        // 저장 시 검증되지만, 손상된 값 하나가 전체 현황 조회를 죽이면 안 된다.
        val window = InvoiceGraceWindow(graceDays = 31)
        val previousUtc = YearMonth.from(LocalDate.now(ZoneId.of("UTC"))).minusMonths(1)
        assertThat(window.notYetDue("Not/AZone", previousUtc)).isTrue()
    }
}
