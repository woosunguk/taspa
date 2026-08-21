package com.taspa.server.common.export

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

/**
 * CSV 내보내기 — **남의 시스템에 들어가는 파일**이라 화면과 지켜야 할 것이 다르다.
 *
 * ★가장 중요한 단언은 수식 인젝션이다. 조직명·매장명은 사용자가 정하는 값이고, 그 파일을 여는 사람은
 * 회계 담당자다. `=HYPERLINK(...)` 같은 이름이 셀에 그대로 들어가면 그 사람의 엑셀에서 실행된다.
 */
class CsvWriterTest {
    @Test
    fun `수식으로 시작하는 값은 텍스트로 못박는다`() {
        // 엑셀이 수식으로 해석하기 시작하는 문자 전부.
        val rows =
            listOf(
                listOf("=HYPERLINK(\"http://evil\",\"click\")"),
                listOf("+1+1"),
                listOf("-1+1"),
                listOf("@SUM(A1)"),
                listOf("\tlead-tab"),
            )
        val csv = CsvWriter.render(listOf("이름"), rows)

        // 셀 값이 수식 문자로 **시작하지 않는다**. 따옴표로 감싸는 것만으로는 막히지 않으므로
        // (엑셀은 언따옴표한 뒤 판정한다) 작은따옴표 접두가 실제 방어선이다.
        val cells = csv.lines().drop(1).filter { it.isNotBlank() }
        assertThat(cells).allSatisfy { line ->
            val value = line.removePrefix("\"")
            assertThat(value).startsWith("'")
        }
    }

    @Test
    fun `평범한 값에는 작은따옴표를 붙이지 않는다`() {
        // 대조군 — 없으면 위 테스트가 "모든 값에 붙인다"와 구별되지 않는다.
        val csv = CsvWriter.render(listOf("이름"), listOf(listOf("우리회사"), listOf("1000")))
        assertThat(csv).contains("\r\n우리회사\r\n")
        assertThat(csv).contains("\r\n1000\r\n")
        assertThat(csv).doesNotContain("'우리회사")
    }

    @Test
    fun `쉼표·따옴표·줄바꿈이 든 값은 RFC 4180 으로 감싼다`() {
        val csv =
            CsvWriter.render(
                listOf("이름"),
                listOf(listOf("주식회사 가, 나"), listOf("따옴표 \" 포함"), listOf("두\n줄")),
            )
        assertThat(csv).contains("\"주식회사 가, 나\"")
        // 내부 따옴표는 두 번 반복해 이스케이프한다.
        assertThat(csv).contains("\"따옴표 \"\" 포함\"")
        assertThat(csv).contains("\"두\n줄\"")
    }

    @Test
    fun `엑셀 한글 깨짐을 막는 BOM 으로 시작한다`() {
        // BOM 이 없으면 윈도우 엑셀이 로컬 코드페이지로 읽어 **조용히** 깨진다(받는 사람은 우리 버그로
        // 인지하지도 못한다). 그래서 이건 미관이 아니라 정확성 문제다.
        val csv = CsvWriter.render(listOf("조직"), listOf(listOf("한국회사")))
        assertThat(csv.first()).isEqualTo('\uFEFF')
    }

    @Test
    fun `null 은 빈 칸이지 문자열 null 이 아니다`() {
        val csv = CsvWriter.render(listOf("a", "b"), listOf(listOf("x", null)))
        assertThat(csv).contains("x,\r\n")
        assertThat(csv).doesNotContain("null")
    }

    @Test
    fun `한글 파일명은 ASCII 폴백과 RFC 5987 을 함께 싣는다`() {
        val response = CsvWriter.download("taspa-청구서-2026-07.csv", "a\r\n")
        val disposition = response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)!!

        // HTTP 헤더는 ASCII 라 한글을 그대로 넣을 수 없다 — 폴백에는 남지 않아야 한다.
        assertThat(disposition).contains("attachment;")
        assertThat(disposition).contains("filename=\"taspa-___-2026-07.csv\"")
        assertThat(disposition).contains("filename*=UTF-8''")
        assertThat(disposition).contains("%EC%B2%AD%EA%B5%AC%EC%84%9C")
    }

    @Test
    fun `회계 문서는 캐시하지 않는다`() {
        // 확정 전 재생성으로 숫자가 바뀌는 문서다 — 프록시가 옛 파일을 재사용하면 잘못된 금액이 돈다.
        val response = CsvWriter.download("x.csv", "a\r\n")
        assertThat(response.headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
    }
}
