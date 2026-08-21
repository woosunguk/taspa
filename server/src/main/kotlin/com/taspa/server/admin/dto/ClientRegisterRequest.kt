package com.taspa.server.admin.dto

import java.util.UUID

data class ClientRegisterRequest(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String> = emptyList(),
    val postLogoutRedirectUris: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
    val grantTypes: List<String> = emptyList(),
    /** true = 공개 클라이언트(SPA·모바일): secret 없음(token_endpoint_auth_method=none) + PKCE 강제. */
    val publicClient: Boolean = false,
    /**
     * 생산자(M2M) 클라이언트를 결속할 조직 UUID(선택, Phase 0ب-C). 지정하면 발급되는 client_credentials
     * 토큰에 org_id 클레임이 실려 소비 이벤트 적재(org 결속 write)가 M2M 로 도달 가능해진다. 존재하는 조직만 허용.
     */
    val orgId: UUID? = null,
    /**
     * 가맹(POS·CPM 단말) 클라이언트를 결속할 merchant UUID(선택, 식권 L1). 지정하면 발급되는
     * client_credentials 토큰에 merchant_id 클레임이 실려 /api/merchant/redeem 인가에 도달한다.
     * 존재하는 가맹만 허용.
     */
    val merchantId: UUID? = null,
    /**
     * 이 클라이언트가 **인가에 쓰겠다고 선언한 조직 커스텀 역할 이름**(선택). 비우면 `roles` 클레임을
     * 아예 발급하지 않는다(fail-closed) — 선언 없이 전부 실어 보내면 조직의 역할 구성이 연동 서비스마다
     * 누출되고, 조직이 역할을 하나 추가할 때마다 모든 RP 의 인가 입력이 조용히 바뀐다.
     * 실제 발급은 이 목록과 **사용자가 실제로 가진 역할의 교집합**이다(`org.roles` scope 필요).
     */
    val roleNames: List<String> = emptyList(),
)
