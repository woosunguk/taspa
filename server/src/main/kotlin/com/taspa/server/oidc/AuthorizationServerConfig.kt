package com.taspa.server.oidc

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings

@Configuration
class AuthorizationServerConfig {
    @Bean
    fun authorizationServerSettings(
        @Value("\${taspa.issuer-uri}") issuerUri: String,
    ): AuthorizationServerSettings =
        AuthorizationServerSettings
            .builder()
            .issuer(issuerUri)
            .build()

    // 진행 중인 authorization(동의 왕복 포함)과 동의 내역을 JDBC 로 영속화한다 (V2 스키마 사용).
    @Bean
    fun authorizationService(
        jdbcTemplate: JdbcTemplate,
        registeredClientRepository: RegisteredClientRepository,
    ): OAuth2AuthorizationService = JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository)

    @Bean
    fun authorizationConsentService(
        jdbcTemplate: JdbcTemplate,
        registeredClientRepository: RegisteredClientRepository,
    ): OAuth2AuthorizationConsentService = JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository)
}
