package com.taspa.server.federation

/** 지원 공급자 registrationId 와 표시명. 브랜드 로고 에셋은 커밋하지 않는다(텍스트 버튼). */
object SocialProviders {
    const val GOOGLE = "google"
    const val KAKAO = "kakao"
    const val NAVER = "naver"

    private val labels =
        mapOf(
            GOOGLE to "Google",
            KAKAO to "카카오",
            NAVER to "네이버",
        )

    val all: Set<String> = labels.keys

    /**
     * 표시명. 소셜 3종은 고정 라벨, 기업 SSO(Stage E)의 provider 값(`saml:{regId}` / `oidc:{regId}`)은
     * regId 에 프로토콜 꼬리표를 붙여 사람이 읽을 수 있게 한다.
     */
    fun label(registrationId: String): String =
        labels[registrationId] ?: when {
            registrationId.startsWith("saml:") -> "${registrationId.removePrefix("saml:")} (SAML)"
            registrationId.startsWith("oidc:") -> "${registrationId.removePrefix("oidc:")} (OIDC)"
            else -> registrationId
        }
}
