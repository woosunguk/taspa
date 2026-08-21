package com.taspa.server.domain.jwk

enum class JwkKeyStatus {
    /** 현재 서명에 사용되는 키. 항상 1개를 유지한다. */
    ACTIVE,

    /** 회전으로 물러난 키. 유예 기간 동안 JWKS 에 게시되어 기존 토큰 검증을 지탱한다. */
    RETIRED,
}
