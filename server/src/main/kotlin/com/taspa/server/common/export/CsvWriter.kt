package com.taspa.server.common.export

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 회계용 CSV 생성기.
 *
 * 이 파일들은 **사람이 읽는 화면이 아니라 남의 시스템(ERP·엑셀)에 들어간다.** 그래서 여기서 지켜야 할
 * 것이 화면과 다르다:
 *
 * 1. ★**수식 인젝션 방어**. `=`·`+`·`-`·`@`·탭·CR 로 시작하는 값은 엑셀이 **수식으로 실행**한다.
 *    조직명·매장명은 사용자가 정하므로 `=HYPERLINK(...)` 같은 이름이 그대로 셀에 들어가면, 청구서를 연
 *    회계 담당자의 엑셀에서 실행된다(CWE-1236). 따옴표로 감싸는 것만으로는 **막히지 않는다** — 엑셀은
 *    셀 값을 언따옴표한 **뒤** 판정한다. 그래서 값 앞에 작은따옴표를 덧붙여 텍스트로 못박는다.
 * 2. **UTF-8 BOM**. 윈도우 엑셀은 BOM 이 없으면 UTF-8 을 로컬 코드페이지로 읽어 한글이 전부 깨진다.
 *    파일이 열리기는 하므로 **조용히 틀린다** — 받는 사람이 우리 버그로 인지하지도 못한다.
 * 3. **파일명**. 한글 파일명은 `filename=` 에 그대로 넣을 수 없다(HTTP 헤더는 ASCII). RFC 5987
 *    `filename*=UTF-8''...` 를 함께 주고, 그걸 모르는 클라이언트를 위해 ASCII 폴백을 남긴다.
 */
object CsvWriter {
    /**
     * UTF-8 BOM. ★**리터럴 문자가 아니라 이스케이프로 쓴다.**
     *
     * 예전엔 보이지 않는 U+FEFF 를 따옴표 안에 그대로 넣어 두었는데, `ktlintFormat` 이 그 문자를 조용히
     * 지워 **빈 문자열**이 됐다(에디터·리뷰·컴파일 어디에서도 보이지 않는다). 그 상태로 배포되면
     * 윈도우 엑셀이 UTF-8 을 로컬 코드페이지로 읽어 **파일은 열리는데 한글이 전부 깨진다** —
     * 받는 사람이 우리 결함으로 인지하지도 못하는 형태다. 이스케이프는 어떤 도구를 지나도 살아남는다.
     */
    private const val BOM = "\uFEFF"

    /** 엑셀이 수식으로 해석하기 시작하는 문자들. */
    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * 헤더 1줄 + 데이터 N줄을 CSV 본문으로 만든다. 줄바꿈은 CRLF(RFC 4180 — 엑셀 호환).
     * null 은 빈 칸으로 둔다(문자열 "null" 이 회계 장부에 남지 않게).
     */
    fun render(
        header: List<String>,
        rows: List<List<Any?>>,
    ): String {
        val builder = StringBuilder(BOM)
        builder.append(header.joinToString(",") { escape(it) }).append("\r\n")
        rows.forEach { row ->
            builder.append(row.joinToString(",") { escape(it?.toString()) }).append("\r\n")
        }
        return builder.toString()
    }

    /**
     * 한 셀. 수식 트리거를 먼저 무력화하고(1) 그다음 RFC 4180 따옴표 처리(2)를 한다 — 순서가 중요하다:
     * 따옴표를 먼저 씌우면 작은따옴표가 감싸는 따옴표 **안**으로 들어가야 하는데, 그 판단을 두 곳에서
     * 하게 되어 한쪽만 고치는 실수가 난다.
     */
    private fun escape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val guarded = if (value[0] in FORMULA_TRIGGERS) "'$value" else value
        return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else {
            guarded
        }
    }

    /**
     * 다운로드 응답. `filename` 은 한글을 포함할 수 있다 — ASCII 폴백과 RFC 5987 을 함께 싣는다.
     *
     * charset=UTF-8 을 Content-Type 에 명시해도 엑셀은 무시하므로 BOM 이 실질적인 신호다(render 가 붙인다).
     */
    fun download(
        filename: String,
        body: String,
    ): ResponseEntity<String> {
        val ascii = filename.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded")
            // 회계 문서는 캐시되면 옛 숫자가 재사용될 수 있다 — 브라우저·프록시 모두 막는다.
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body)
    }
}
