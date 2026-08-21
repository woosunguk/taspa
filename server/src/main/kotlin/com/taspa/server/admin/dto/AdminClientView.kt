package com.taspa.server.admin.dto

import com.taspa.server.token.TokenCustomizerConfig
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import java.time.Instant

data class AdminClientView(
    val id: String,
    val clientId: String,
    val clientName: String,
    val publicClient: Boolean,
    val redirectUris: List<String>,
    val postLogoutRedirectUris: List<String>,
    val scopes: List<String>,
    val grantTypes: List<String>,
    val clientIdIssuedAt: Instant?,
    /** 선언된 조직 커스텀 역할 이름. 실제 발급은 이 목록 ∩ 사용자 보유 역할이다. */
    val roleNames: List<String>,
) {
    companion object {
        fun from(client: RegisteredClient): AdminClientView =
            AdminClientView(
                id = client.id,
                clientId = client.clientId,
                clientName = client.clientName,
                publicClient = client.clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE),
                redirectUris = client.redirectUris.sorted(),
                postLogoutRedirectUris = client.postLogoutRedirectUris.sorted(),
                scopes = client.scopes.sorted(),
                grantTypes = client.authorizationGrantTypes.map { it.value }.sorted(),
                clientIdIssuedAt = client.clientIdIssuedAt,
                roleNames =
                    TokenCustomizerConfig.parseRoleNames(
                        client.clientSettings?.getSetting<Any?>(TokenCustomizerConfig.CLIENT_ROLE_NAMES_SETTING)?.toString(),
                    ),
            )
    }
}
