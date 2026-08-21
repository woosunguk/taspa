package com.taspa.server.iam

/**
 * AWS IAM 스타일 정책의 인메모리 표현. 저장은 JSON 문자열(iam_policies.document 등), 이 모델은 파싱 결과다.
 * 평가 규칙(AWS 동일): 기본 암묵적 거부 → 적용 가능한 명시적 Deny 하나라도 있으면 거부(Allow 무시) →
 * 적용 가능한 Allow 가 있으면 허용 → 그 외 거부.
 */

enum class Effect { ALLOW, DENY }

/** 정책 문서 하나. version 은 문서 스키마 날짜 라벨(AWS 의 "2012-10-17" 대응, 우리는 발급일). */
data class PolicyDocument(
    val version: String,
    val statements: List<Statement>,
)

/**
 * 정책 문장. action 이 매치되고 resource 가 매치되며 모든 condition 이 만족될 때 이 문장이 "적용"된다.
 * Phase 1 은 Action/Resource/Condition 만 다룬다(NotAction/NotResource/Principal 은 후속 단계).
 */
data class Statement(
    val sid: String? = null,
    val effect: Effect,
    /** action 패턴들. "service:Action" 형식, "*" 및 "org:*" 같은 와일드카드 허용. 매칭은 대소문자 무시. */
    val actions: List<String>,
    /** TRN 리소스 패턴들(trn:taspa 접두). `*`(0+ 문자)·`?`(1 문자) 글롭과 `${'$'}{key}` 정책 변수 허용. */
    val resources: List<String>,
    val conditions: List<Condition> = emptyList(),
)

/**
 * 조건 하나 = 연산자 + 키 + 값목록. 저장 JSON 의 `{ "StringEquals": { "taspa:OrgId": ["v1","v2"] } }` 를
 * 평탄화한 형태(연산자·키 조합마다 Condition 1개). 한 문장의 조건들은 AND, 한 조건의 값들은 OR(양성 연산자 기준).
 * 연산자에 ":IfExists" 접미사를 붙이면 키 부재 시 통과(AWS 동일).
 */
data class Condition(
    val operator: String,
    val key: String,
    val values: List<String>,
)

/**
 * 인가 질의. action 은 검사 대상 동작("invoice:Finalize"), resource 는 이미 확정된 대상 TRN,
 * context 는 조건키 맵(예: "taspa:OrgId","taspa:ResourceOrg","taspa:StepUpPresent","taspa:CurrentTime").
 * resource 패턴의 `${'$'}{key}` 정책 변수도 이 context 에서 치환된다.
 */
data class AuthorizationRequest(
    val action: String,
    val resource: String,
    val context: Map<String, String> = emptyMap(),
    /**
     * 대상 자원의 조직 내 위치(부서 서브트리 위임 판정 입력). 엔진이 이 값에서만 스코프 조건키를
     * 채우고 [context] 의 동명 키는 지운다 — 스푸핑 차단([ResourceScope] KDoc 참고).
     */
    val scope: ResourceScope = ResourceScope.NONE,
)

enum class DecisionEffect { ALLOW, DENY }

/** 평가 결과. reason/matchedSid 는 shadow 감사·디버깅용. */
data class Decision(
    val effect: DecisionEffect,
    val reason: String,
    val matchedSid: String? = null,
) {
    val isAllowed: Boolean get() = effect == DecisionEffect.ALLOW
}
