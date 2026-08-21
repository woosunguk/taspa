package com.taspa.server.iam

import java.util.concurrent.ConcurrentHashMap

/**
 * action/resource 패턴 매칭. AWS 규칙:
 *  - action 은 대소문자 무시 매칭("s3:GetObject" == "s3:getobject"), `*`/`?` 와일드카드.
 *  - resource(TRN)는 대소문자 구분 매칭, `*`(0+ 문자)·`?`(1 문자) 글롭. `${'$'}{key}` 정책 변수는 매칭 전에 치환.
 *
 * 보안 불변식:
 *  - 미해결 정책 변수는 리터럴로 남겨 실제 리소스와 매치되지 않게 한다(Allow 는 권한 미부여로 안전측).
 *  - **치환된 변수 값의 글롭 메타문자(`*`/`?`/`\`)는 이스케이프**해 리터럴로 취급한다 — 값에 들어온 `*` 가
 *    와일드카드로 작동해 매칭을 넓히는 injection(예: context 값 "*" 로 전 org 매치)을 차단한다.
 *  - 테넌시(org 격리)는 이 글롭이 아니라 PolicyEvaluator 가 리소스에서 구조적으로 뽑는 taspa:ResourceOrg
 *    조건으로 강제한다 — `*` 가 `:` 경계를 넘는 글롭 특성에 org 경계를 의존시키지 않는다.
 */
object PolicyMatching {
    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun actionMatches(
        patterns: List<String>,
        action: String,
    ): Boolean {
        val target = action.lowercase()
        return patterns.any { globMatches(it.lowercase(), target) }
    }

    fun resourceMatches(
        patterns: List<String>,
        resource: String,
        context: Map<String, String>,
    ): Boolean = patterns.any { globMatches(substitute(it, context), resource) }

    /**
     * 패턴 안의 `${'$'}{key}` 를 context[key] 로 치환. 값이 없으면 리터럴 유지(실 리소스와 불일치 → 안전).
     * 치환된 값의 글롭 메타문자는 이스케이프해 데이터가 매칭 의미를 갖지 못하게 한다.
     */
    fun substitute(
        pattern: String,
        context: Map<String, String>,
    ): String {
        if (!pattern.contains("\${")) return pattern
        val sb = StringBuilder(pattern.length)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '$' && i + 1 < pattern.length && pattern[i + 1] == '{') {
                val end = pattern.indexOf('}', i + 2)
                if (end > 0) {
                    val key = pattern.substring(i + 2, end)
                    val value = context[key]
                    if (value != null) sb.append(escapeGlob(value)) else sb.append(pattern, i, end + 1)
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 값의 글롭 메타문자(`\`,`*`,`?`)를 백슬래시로 이스케이프 → globMatches 가 리터럴로 처리. */
    private fun escapeGlob(value: String): String {
        if (value.none { it == '\\' || it == '*' || it == '?' }) return value
        val out = StringBuilder(value.length + 4)
        for (c in value) {
            if (c == '\\' || c == '*' || c == '?') out.append('\\')
            out.append(c)
        }
        return out.toString()
    }

    /** `*`=임의 0+ 문자, `?`=1 문자, `\x`=리터럴 x, 그 외 리터럴. 컴파일된 정규식은 패턴별로 캐시. */
    fun globMatches(
        pattern: String,
        value: String,
    ): Boolean = regexCache.getOrPut(pattern) { compileGlob(pattern) }.matches(value)

    private fun compileGlob(pattern: String): Regex {
        val regex = StringBuilder(pattern.length + 8).append('^')
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                regex.append(Regex.escape(literal.toString()))
                literal.setLength(0)
            }
        }
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '\\' -> {
                    // 이스케이프: 다음 문자를 리터럴로. 끝에 홀로 있으면 백슬래시 자체를 리터럴로.
                    if (i + 1 < pattern.length) {
                        literal.append(pattern[i + 1])
                        i += 2
                    } else {
                        literal.append('\\')
                        i++
                    }
                }
                '*' -> {
                    flushLiteral()
                    regex.append(".*")
                    i++
                }
                '?' -> {
                    flushLiteral()
                    regex.append('.')
                    i++
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flushLiteral()
        regex.append('$')
        // DOT_MATCHES_ALL 미사용: `.`/`.*` 가 개행을 넘지 않게 한다(TRN 은 개행을 포함하지 않음).
        return Regex(regex.toString())
    }
}
