package com.taspa.server.iam

import java.time.Instant

/**
 * 조건 연산자 평가(AWS IAM 의미론). 한 문장의 조건들은 AND, 한 조건의 값들은 OR(양성 연산자 기준).
 *
 * **fail-safe by effect**: 조건을 확정 평가할 수 없을 때(양성 연산자인데 키 부재, 수치/날짜 파싱 불가,
 * 미지원 연산자) 결과를 문장 effect 로 편향한다 — Deny 문장이면 "적용"(true, 거부를 살림), Allow 문장이면
 * "미적용"(false, 권한 미부여). 이로써 Deny 가드가 데이터 누락으로 조용히 무력화되는 fail-open 을 막는다.
 * (`denyStatement` 파라미터가 이 편향을 나른다. 키가 존재하고 확정적으로 false 인 경우는 effect 무관하게 false.)
 *
 * 키부재 기본 규칙(AWS): `:IfExists` 면 통과, 음성 연산자(...NotEquals/NotLike)는 공허참(true),
 * `Null` 은 키 존재 자체를 검사. 양성 연산자의 키부재만 위 fail-safe 편향을 받는다.
 */
object ConditionEvaluator {
    fun satisfied(
        conditions: List<Condition>,
        context: Map<String, String>,
        denyStatement: Boolean,
    ): Boolean = conditions.all { evaluate(it, context, denyStatement) }

    private val NEGATIVE =
        setOf(
            "StringNotEquals",
            "StringNotEqualsIgnoreCase",
            "StringNotLike",
            "NumericNotEquals",
            "DateNotEquals",
        )

    fun evaluate(
        condition: Condition,
        context: Map<String, String>,
        denyStatement: Boolean,
    ): Boolean {
        val ifExists = condition.operator.endsWith(":IfExists")
        val op = if (ifExists) condition.operator.removeSuffix(":IfExists") else condition.operator

        // Null 은 값 의미와 무관하게 키 존재만 검사(확정적).
        if (op == "Null") {
            val wantAbsent = condition.values.any { it.equals("true", ignoreCase = true) }
            val present = context.containsKey(condition.key)
            return if (wantAbsent) !present else present
        }

        val actual = context[condition.key]
        if (actual == null) {
            if (ifExists) return true
            if (op in NEGATIVE) return true
            // 양성 연산자 + 키 부재 = 미평가 → effect 로 편향(Deny 는 적용, Allow 는 미적용).
            return denyStatement
        }

        return when (op) {
            "StringEquals" -> condition.values.any { it == actual }
            "StringNotEquals" -> condition.values.none { it == actual }
            "StringEqualsIgnoreCase" -> condition.values.any { it.equals(actual, ignoreCase = true) }
            "StringNotEqualsIgnoreCase" -> condition.values.none { it.equals(actual, ignoreCase = true) }
            "StringLike" -> condition.values.any { PolicyMatching.globMatches(it, actual) }
            "StringNotLike" -> condition.values.none { PolicyMatching.globMatches(it, actual) }
            "Bool" -> condition.values.any { it.toBooleanLoose() == actual.toBooleanLoose() }
            "NumericEquals" -> numericAny(actual, condition.values) { it == 0 } ?: denyStatement
            "NumericNotEquals" -> numericAny(actual, condition.values) { it == 0 }?.not() ?: denyStatement
            "NumericLessThan" -> numericAny(actual, condition.values) { it < 0 } ?: denyStatement
            "NumericLessThanEquals" -> numericAny(actual, condition.values) { it <= 0 } ?: denyStatement
            "NumericGreaterThan" -> numericAny(actual, condition.values) { it > 0 } ?: denyStatement
            "NumericGreaterThanEquals" -> numericAny(actual, condition.values) { it >= 0 } ?: denyStatement
            "DateEquals" -> dateAny(actual, condition.values) { it == 0 } ?: denyStatement
            "DateNotEquals" -> dateAny(actual, condition.values) { it == 0 }?.not() ?: denyStatement
            "DateLessThan" -> dateAny(actual, condition.values) { it < 0 } ?: denyStatement
            "DateLessThanEquals" -> dateAny(actual, condition.values) { it <= 0 } ?: denyStatement
            "DateGreaterThan" -> dateAny(actual, condition.values) { it > 0 } ?: denyStatement
            "DateGreaterThanEquals" -> dateAny(actual, condition.values) { it >= 0 } ?: denyStatement
            // 미지원 연산자(저장 검증을 통과했어야 하나, 프로그래매틱 Statement 방어) → effect 로 편향.
            else -> denyStatement
        }
    }

    /** actual 이 수치로 파싱되면 값들에 대해 OR 로 predicate 평가, 아니면 null(미평가). */
    private inline fun numericAny(
        actual: String,
        values: List<String>,
        predicate: (Int) -> Boolean,
    ): Boolean? {
        val a = actual.toDoubleOrNull() ?: return null
        return values.any { v -> v.toDoubleOrNull()?.let { predicate(a.compareTo(it)) } ?: false }
    }

    private inline fun dateAny(
        actual: String,
        values: List<String>,
        predicate: (Int) -> Boolean,
    ): Boolean? {
        val a = parseInstant(actual) ?: return null
        return values.any { v -> parseInstant(v)?.let { predicate(a.compareTo(it)) } ?: false }
    }

    private fun parseInstant(s: String): Instant? =
        try {
            Instant.parse(s)
        } catch (_: Exception) {
            null
        }

    private fun String.toBooleanLoose(): Boolean = this.equals("true", ignoreCase = true)
}
