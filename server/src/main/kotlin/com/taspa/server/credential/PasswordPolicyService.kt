package com.taspa.server.credential

import com.taspa.server.config.i18n.MessageResolver
import org.springframework.stereotype.Service

@Service
class PasswordPolicyService(
    private val properties: PasswordPolicyProperties,
    private val messages: MessageResolver,
    private val breachedPasswordChecker: BreachedPasswordChecker,
) {
    fun validate(password: String): List<String> {
        val violations = mutableListOf<String>()

        if (password.length < properties.minLength) {
            // 길이는 문자열로 넘겨 로케일별 숫자 그룹핑(예: 1,000) 차이를 배제한다.
            violations.add(messages.get("password.policy.minLength", properties.minLength.toString()))
        }
        if (password.length > properties.maxLength) {
            violations.add(messages.get("password.policy.maxLength", properties.maxLength.toString()))
        }
        if (properties.requireUppercase && !password.any { it.isUpperCase() }) {
            violations.add(messages.get("password.policy.uppercase"))
        }
        if (properties.requireLowercase && !password.any { it.isLowerCase() }) {
            violations.add(messages.get("password.policy.lowercase"))
        }
        if (properties.requireDigit && !password.any { it.isDigit() }) {
            violations.add(messages.get("password.policy.digit"))
        }
        if (properties.requireSpecial && !password.any { !it.isLetterOrDigit() }) {
            violations.add(messages.get("password.policy.special"))
        }

        // HIBP 유출 검사는 로컬 정책을 통과한 비밀번호에만 수행한다 — 이미 형식 위반이면 외부 호출을
        // 생략(불필요한 네트워크 왕복 방지)하고, enabled=false 면 checker 가 즉시 false 를 반환한다.
        if (violations.isEmpty() && breachedPasswordChecker.isBreached(password)) {
            violations.add(messages.get("password.policy.breached"))
        }

        return violations
    }
}
