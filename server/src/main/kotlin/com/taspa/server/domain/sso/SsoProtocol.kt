package com.taspa.server.domain.sso

/** 기업 SSO 커넥션 프로토콜. sso_connections.protocol 컬럼(VARCHAR 8)에 name 으로 저장된다. */
enum class SsoProtocol {
    OIDC,
    SAML,
}
