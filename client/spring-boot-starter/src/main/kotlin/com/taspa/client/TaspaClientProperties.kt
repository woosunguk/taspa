package com.taspa.client

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "taspa.client")
data class TaspaClientProperties(
    val issuerUri: String? = null,
    /**
     * 이 리소스 서버가 받아들일 `aud` 클레임 값(선택).
     *
     * ★지정하면 **실제로 검증한다** — 그 값이 토큰 `aud` 에 없으면 거부한다.
     * 한동안 이 프로퍼티는 **선언만 되어 있고 아무 데서도 읽히지 않았다**. 문서(`integration-guide.md`)의
     * 예시에는 있었으므로 연동 개발자는 "내 서비스용 토큰만 받는다"고 믿었지만, 실제로는 같은 IdP 가
     * 발급한 **다른 서비스의 토큰**도 그대로 통과했다(iss·exp·nbf 만 봤다). 설정이 있는데 안 도는 것은
     * 없는 것보다 나쁘다 — 없으면 최소한 직접 붙였을 검증을 안 붙이게 만들기 때문이다.
     */
    val audience: String? = null,
    /**
     * 이 서비스가 담당하는 조직 UUID(선택). 지정하면 그 조직의 커스텀 역할만 권한으로 옮긴다.
     * 미지정이면 토큰의 대표 org(`org_id`) 역할을 쓴다 — 어느 쪽이든 **여러 조직의 역할을 합치지 않는다**.
     */
    val orgId: String? = null,
    /** 역할 권한 접두사. 기본 `ROLE_` — `hasRole("회계 담당")` 으로 쓸 수 있다. */
    val roleAuthorityPrefix: String = "ROLE_",
    /** 역할 → 권한 변환을 켤지. 끄면 scope 권한만 나온다(기존 동작). */
    val rolesEnabled: Boolean = true,
)
