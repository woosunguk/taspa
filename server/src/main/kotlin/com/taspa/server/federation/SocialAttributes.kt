package com.taspa.server.federation

/**
 * 공급자별 userinfo 를 정규화한 결과. FederatedLoginSuccessHandler 의 분기(A-2)는 이 형태만 본다.
 *
 * emailVerifiedByProvider:
 *  - google: id_token 의 email_verified
 *  - kakao: is_email_valid && is_email_verified 둘 다 true 일 때만
 *  - naver: 검증 플래그가 없으므로 항상 false (미검증 취급)
 */
data class SocialAttributes(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val emailVerifiedByProvider: Boolean,
    val displayName: String?,
    /** 기업 SSO(Stage E) 커넥션 id — 소셜 로그인은 null. 연결(federated_identities.connection_id)에 기록된다. */
    val connectionId: java.util.UUID? = null,
)
