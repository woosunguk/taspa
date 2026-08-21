package com.taspa.server.iam

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

/**
 * 정책 문서 JSON ↔ 모델 변환 + 저장 시점 검증. AWS 형식 수용:
 *   Action/Resource 는 문자열 또는 문자열 배열, Statement 는 객체 또는 배열,
 *   Condition 은 `{ 연산자: { 키: 값|[값] } }` 중첩 구조.
 * 파싱 실패·형식 오류는 IllegalArgumentException(서비스 계층이 AuthException 으로 매핑).
 */
@Component
class PolicyParser(
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val DEFAULT_VERSION = "2026-07-25"

        /** 평가기가 지원하는 조건 연산자(`:IfExists` 접미사 제외한 기본형). 저장 검증이 이 집합만 허용. */
        val KNOWN_OPERATORS =
            setOf(
                "StringEquals",
                "StringNotEquals",
                "StringEqualsIgnoreCase",
                "StringNotEqualsIgnoreCase",
                "StringLike",
                "StringNotLike",
                "Bool",
                "Null",
                "NumericEquals",
                "NumericNotEquals",
                "NumericLessThan",
                "NumericLessThanEquals",
                "NumericGreaterThan",
                "NumericGreaterThanEquals",
                "DateEquals",
                "DateNotEquals",
                "DateLessThan",
                "DateLessThanEquals",
                "DateGreaterThan",
                "DateGreaterThanEquals",
            )

        /** Phase 1 이 해석하는 Statement 요소. 그 외는 저장 시점에 거부한다. */
        val KNOWN_STATEMENT_KEYS = setOf("Sid", "Effect", "Action", "Resource", "Condition")

        /** 형식은 AWS 표준이지만 Phase 1 미구현 — 조용한 무시가 과대부여가 되므로 명시적으로 거부. */
        val UNSUPPORTED_STATEMENT_KEYS = setOf("NotAction", "NotResource", "Principal", "NotPrincipal")
    }

    /**
     * 중복 키를 거부하는 엄격 리더 — Jackson 기본은 마지막 값이 이기므로
     * `{"Effect":"Deny",...,"Effect":"Allow"}` 가 조용히 Allow 로 저장돼 리뷰를 회피할 수 있다.
     */
    private val strictReader =
        objectMapper
            .copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    fun parse(json: String): PolicyDocument {
        val root =
            try {
                strictReader.readTree(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("정책 JSON 파싱 실패: ${e.message}")
            }
        require(root != null && root.isObject) { "정책 문서는 JSON 객체여야 한다" }
        val version = root.get("Version")?.asText()?.takeIf { it.isNotBlank() } ?: DEFAULT_VERSION
        val statementNode = root.get("Statement") ?: throw IllegalArgumentException("Statement 누락")
        val statements =
            when {
                statementNode.isArray -> statementNode.map { parseStatement(it) }
                statementNode.isObject -> listOf(parseStatement(statementNode))
                else -> throw IllegalArgumentException("Statement 는 객체 또는 배열이어야 한다")
            }
        require(statements.isNotEmpty()) { "Statement 가 비어있다" }
        return PolicyDocument(version, statements)
    }

    private fun parseStatement(node: JsonNode): Statement {
        require(node.isObject) { "각 Statement 는 객체여야 한다" }
        // 엄격 파싱: 지원하지 않는 요소를 조용히 무시하지 않는다. NotResource 등을 무시하면 작성자가
        // 의도한 제외(carve-out)가 사라져 과대부여(Allow *)가 된다 — 저장 시점에 거부한다.
        node.fieldNames().forEach { field ->
            require(field in KNOWN_STATEMENT_KEYS) {
                if (field in UNSUPPORTED_STATEMENT_KEYS) {
                    "Phase 1 에서 지원하지 않는 Statement 요소: \"$field\" (정책을 저장할 수 없음)"
                } else {
                    "알 수 없는 Statement 요소: \"$field\""
                }
            }
        }
        val sid = node.get("Sid")?.asText()?.takeIf { it.isNotBlank() }
        val effect =
            when (node.get("Effect")?.asText()) {
                "Allow" -> Effect.ALLOW
                "Deny" -> Effect.DENY
                else -> throw IllegalArgumentException("Effect 는 \"Allow\" 또는 \"Deny\" 여야 한다")
            }
        val actions = stringList(node.get("Action"))
        require(actions.isNotEmpty()) { "Action 이 비어있다" }
        val resources = stringList(node.get("Resource"))
        require(resources.isNotEmpty()) { "Resource 가 비어있다" }
        return Statement(sid, effect, actions, resources, parseConditions(node.get("Condition")))
    }

    private fun stringList(node: JsonNode?): List<String> =
        when {
            node == null || node.isNull -> emptyList()
            node.isTextual -> listOf(node.asText())
            node.isArray -> node.mapNotNull { if (it.isTextual) it.asText() else throw IllegalArgumentException("배열 요소는 문자열이어야 한다") }
            else -> throw IllegalArgumentException("문자열 또는 문자열 배열이어야 한다")
        }

    private fun parseConditions(node: JsonNode?): List<Condition> {
        if (node == null || node.isNull) return emptyList()
        require(node.isObject) { "Condition 은 객체여야 한다" }
        val result = mutableListOf<Condition>()
        node.fields().forEach { (operator, keyMap) ->
            require(keyMap.isObject) { "Condition[$operator] 는 { 키: 값 } 객체여야 한다" }
            keyMap.fields().forEach { (key, valueNode) ->
                result += Condition(operator, key, stringList(valueNode))
            }
        }
        return result
    }

    /** 저장 전 검증: 알 수 없는 연산자·빈 조건값·비정상 action/resource 를 거부한다. */
    fun validate(document: PolicyDocument) {
        require(document.statements.isNotEmpty()) { "정책에 Statement 가 하나도 없다" }
        document.statements.forEach { statement ->
            statement.actions.forEach { action ->
                require(action == "*" || action.contains(":")) { "action 형식 오류: \"$action\" (service:Action 또는 *)" }
            }
            statement.resources.forEach { resource ->
                require(resource == "*" || resource.startsWith("trn:taspa:")) {
                    "resource 형식 오류: \"$resource\" (trn:taspa:… 또는 *)"
                }
            }
            statement.conditions.forEach { condition ->
                val base = condition.operator.removeSuffix(":IfExists")
                require(base in KNOWN_OPERATORS) { "지원하지 않는 조건 연산자: \"${condition.operator}\"" }
                require(condition.values.isNotEmpty()) { "조건 \"${condition.operator}\"[${condition.key}] 값이 비어있다" }
            }
        }
    }

    fun serialize(document: PolicyDocument): String {
        val root = objectMapper.createObjectNode()
        root.put("Version", document.version)
        val array = root.putArray("Statement")
        document.statements.forEach { statement ->
            val stmt = array.addObject()
            statement.sid?.let { stmt.put("Sid", it) }
            stmt.put("Effect", if (statement.effect == Effect.ALLOW) "Allow" else "Deny")
            putStringOrArray(stmt, "Action", statement.actions)
            putStringOrArray(stmt, "Resource", statement.resources)
            if (statement.conditions.isNotEmpty()) {
                val conditionNode = stmt.putObject("Condition")
                val grouped: Map<String, List<Condition>> = statement.conditions.groupBy { it.operator }
                for ((operatorName, group) in grouped) {
                    val operatorNode = conditionNode.putObject(operatorName)
                    for (condition in group) {
                        if (condition.values.size == 1) {
                            operatorNode.put(condition.key, condition.values[0])
                        } else {
                            val valuesNode = operatorNode.putArray(condition.key)
                            for (value in condition.values) valuesNode.add(value)
                        }
                    }
                }
            }
        }
        return objectMapper.writeValueAsString(root)
    }

    private fun putStringOrArray(
        node: ObjectNode,
        field: String,
        values: List<String>,
    ) {
        if (values.size == 1) {
            node.put(field, values[0])
        } else {
            val array = node.putArray(field)
            values.forEach { array.add(it) }
        }
    }
}
