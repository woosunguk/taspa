package com.taspa.server.admin

import com.taspa.server.admin.dto.AdminClientView
import com.taspa.server.admin.dto.ClientRegisterRequest
import com.taspa.server.admin.dto.ClientSecretResponse
import com.taspa.server.admin.dto.ClientUpdateRequest
import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.oidc.OAuthProperties
import com.taspa.server.token.TokenCustomizerConfig
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * 관리 콘솔의 OAuth2 클라이언트 관리. JdbcRegisteredClientRepository 에는 findAll 이 없으므로
 * 목록은 id 를 직접 SELECT 한 뒤 findById 로 복원한다(SAS 역직렬화 재사용).
 */
@Service
class AdminClientService(
    private val registeredClientRepository: RegisteredClientRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val auditEventService: AuditEventService,
    private val oauthProperties: OAuthProperties,
    private val organizationRepository: OrganizationRepository,
    private val merchantRepository: MerchantRepository,
) {
    fun list(): List<AdminClientView> =
        jdbcTemplate
            .queryForList("SELECT id FROM oauth2_registered_client ORDER BY client_id", String::class.java)
            .mapNotNull { registeredClientRepository.findById(it) }
            .map { AdminClientView.from(it) }

    fun register(
        request: ClientRegisterRequest,
        actorId: UUID,
    ): ClientSecretResponse {
        val clientId = request.clientId.trim()
        val clientName = request.clientName.trim()
        if (clientId.isEmpty() || clientName.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR)
        }
        if (registeredClientRepository.findByClientId(clientId) != null) {
            throw AuthException(ErrorCode.CLIENT_ID_ALREADY_EXISTS)
        }
        val grantTypes = resolveGrantTypes(request.grantTypes, request.publicClient)
        val scopes = resolveScopes(request.scopes)
        val redirectUris = normalizeUris(request.redirectUris)
        val postLogoutRedirectUris = normalizeUris(request.postLogoutRedirectUris)
        if (grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE) && redirectUris.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR)
        }

        val rawSecret = if (request.publicClient) null else SecureTokenGenerator.generateToken()
        val builder =
            RegisteredClient
                .withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientName)
        if (rawSecret == null) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            builder.clientSecret(encodeSecret(rawSecret))
        }
        grantTypes.forEach { builder.authorizationGrantType(it) }
        redirectUris.forEach { builder.redirectUri(it) }
        postLogoutRedirectUris.forEach { builder.postLogoutRedirectUri(it) }
        scopes.forEach { builder.scope(it) }
        // 생산자(M2M) org 결속(Phase 0ب-C): orgId 지정 시 존재하는 조직만 허용하고 client 설정에 결속을 심는다.
        //   TokenCustomizer 가 이 설정을 읽어 client_credentials 토큰에 org_id 클레임을 실어 org 결속 write 를 가능케 한다.
        val clientSettingsBuilder =
            ClientSettings
                .builder()
                // PKCE 는 유형과 무관하게 항상 강제(공개 클라이언트는 이것이 유일한 방어층).
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
        request.orgId?.let { orgId ->
            if (!organizationRepository.existsById(orgId)) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "결속할 조직을 찾을 수 없습니다")
            }
            clientSettingsBuilder.setting(TokenCustomizerConfig.CLIENT_ORG_ID_SETTING, orgId.toString())
        }
        // 가맹(POS) merchant 결속(식권 L1): 존재하는 가맹만 허용 — TokenCustomizer 가 merchant_id 클레임을 발급한다.
        request.merchantId?.let { merchantId ->
            if (!merchantRepository.existsById(merchantId)) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "결속할 가맹을 찾을 수 없습니다")
            }
            clientSettingsBuilder.setting(TokenCustomizerConfig.CLIENT_MERCHANT_ID_SETTING, merchantId.toString())
        }
        // 조직 커스텀 역할 선언: 이 클라이언트가 인가에 쓰겠다고 밝힌 이름만 토큰에 실린다(∩ 사용자 보유).
        normalizeRoleNames(request.roleNames)?.let {
            clientSettingsBuilder.setting(TokenCustomizerConfig.CLIENT_ROLE_NAMES_SETTING, it)
        }
        val client =
            builder
                .clientSettings(clientSettingsBuilder.build())
                .tokenSettings(
                    TokenSettings
                        .builder()
                        .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                        .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                        .reuseRefreshTokens(false)
                        .build(),
                ).build()
        try {
            registeredClientRepository.save(client)
        } catch (ex: DuplicateKeyException) {
            // 선검사(findByClientId)와 save 사이의 동시 등록 레이스 — V12 의 client_id UNIQUE 가
            // 잡아낸다. 선검사와 동일한 409 로 변환한다.
            throw AuthException(ErrorCode.CLIENT_ID_ALREADY_EXISTS)
        }
        auditEventService.record(
            "ADMIN_CLIENT_REGISTERED",
            actorId,
            mapOf("clientId" to clientId, "clientName" to clientName, "public" to request.publicClient),
        )
        return ClientSecretResponse(AdminClientView.from(client), rawSecret)
    }

    fun update(
        id: String,
        request: ClientUpdateRequest,
        actorId: UUID,
    ): AdminClientView {
        val existing = registeredClientRepository.findById(id) ?: throw AuthException(ErrorCode.NOT_FOUND)
        val clientName = request.clientName.trim()
        if (clientName.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR)
        }
        val scopes = resolveScopes(request.scopes)
        val redirectUris = normalizeUris(request.redirectUris)
        val postLogoutRedirectUris = normalizeUris(request.postLogoutRedirectUris)
        if (existing.authorizationGrantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE) &&
            redirectUris.isEmpty()
        ) {
            throw AuthException(ErrorCode.VALIDATION_ERROR)
        }
        val updated =
            RegisteredClient
                .from(existing)
                .clientName(clientName)
                .redirectUris {
                    it.clear()
                    it.addAll(redirectUris)
                }.postLogoutRedirectUris {
                    it.clear()
                    it.addAll(postLogoutRedirectUris)
                }.scopes {
                    it.clear()
                    it.addAll(scopes)
                }.clientSettings(roleNamesApplied(existing, request.roleNames))
                .build()
        // JdbcRegisteredClientRepository.save 는 동일 id 존재 시 UPDATE 한다.
        registeredClientRepository.save(updated)
        auditEventService.record(
            "ADMIN_CLIENT_UPDATED",
            actorId,
            mapOf("clientId" to existing.clientId, "clientName" to clientName),
        )
        return AdminClientView.from(updated)
    }

    /** 클라이언트 삭제 — 발급된 authorization/동의 행을 함께 정리한다(고아 행 방지). */
    @Transactional
    fun delete(
        id: String,
        actorId: UUID,
    ) {
        val existing = registeredClientRepository.findById(id) ?: throw AuthException(ErrorCode.NOT_FOUND)
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?", id)
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?", id)
        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", id)
        auditEventService.record("ADMIN_CLIENT_DELETED", actorId, mapOf("clientId" to existing.clientId))
    }

    /** 기밀 클라이언트 secret 재발급 — 새 secret 은 응답에서 1회만 노출된다. */
    fun regenerateSecret(
        id: String,
        actorId: UUID,
    ): ClientSecretResponse {
        val existing = registeredClientRepository.findById(id) ?: throw AuthException(ErrorCode.NOT_FOUND)
        if (existing.clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE)) {
            throw AuthException(ErrorCode.CLIENT_NOT_CONFIDENTIAL)
        }
        val rawSecret = SecureTokenGenerator.generateToken()
        val updated =
            RegisteredClient
                .from(existing)
                .clientSecret(encodeSecret(rawSecret))
                .build()
        registeredClientRepository.save(updated)
        auditEventService.record(
            "ADMIN_CLIENT_SECRET_REGENERATED",
            actorId,
            mapOf("clientId" to existing.clientId),
        )
        return ClientSecretResponse(AdminClientView.from(updated), rawSecret)
    }

    /**
     * client secret 을 공유 [PasswordEncoder] 빈(BCryptPasswordEncoder)으로 해시해 저장한다.
     * SAS 토큰 엔드포인트의 클라이언트 인증은 컨텍스트의 PasswordEncoder 빈을 그대로 공유하므로
     * ({bcrypt} 같은) 인코더 접두사를 붙이면 BCryptPasswordEncoder.matches 가 접두사째 비교해
     * 항상 실패한다(invalid_client). 사용자 비밀번호와 동일하게 접두사 없이 저장한다.
     */
    private fun encodeSecret(rawSecret: String): String = passwordEncoder.encode(rawSecret)

    /** 빈 목록은 **설정을 심지 않는다**(미선언 = `roles` 미발급). 빈 문자열을 저장하면 뜻이 흐려진다. */
    private fun normalizeRoleNames(names: List<String>): String? {
        // ★쉼표는 저장 포맷의 구분자다 — 이름에 들어가면 **조용히 여러 선언으로 쪼개져** 의도한 역할은
        //   영영 매칭되지 않고, 쪼개진 조각이 선언한 적 없는 다른 역할과 일치할 수도 있다.
        //   역할을 만드는 쪽(`OrgRoleService.normalizeName`)에서도 같이 막는다.
        names.firstOrNull { it.contains(',') }?.let {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "역할 이름에는 쉼표를 쓸 수 없습니다: $it")
        }
        return TokenCustomizerConfig.formatRoleNames(names).takeIf { it.isNotEmpty() }
    }

    /**
     * 수정 시 선언 역할 이름을 반영한다.
     *
     * ★**기존 설정을 통째로 보존한 위에 얹는다.** `clientSettings(...)` 는 설정 맵을 **교체**하므로
     * 새로 만들어 넘기면 org-id·merchant-id 결속이 조용히 사라진다 — 클라이언트 이름만 고쳤을 뿐인데
     * POS 단말이 결제하지 못하게 되고, 원인은 화면 어디에도 나타나지 않는다.
     *
     * `roleNames` 가 null(미전송)이면 기존 값을 그대로 둔다. 빈 목록은 **선언 해제**다.
     */
    private fun roleNamesApplied(
        existing: RegisteredClient,
        roleNames: List<String>?,
    ): ClientSettings {
        val settings = existing.clientSettings.settings.toMutableMap()
        if (roleNames != null) {
            val normalized = normalizeRoleNames(roleNames)
            if (normalized == null) {
                settings.remove(TokenCustomizerConfig.CLIENT_ROLE_NAMES_SETTING)
            } else {
                settings[TokenCustomizerConfig.CLIENT_ROLE_NAMES_SETTING] = normalized
            }
        }
        return ClientSettings.withSettings(settings).build()
    }

    private fun resolveGrantTypes(
        values: List<String>,
        publicClient: Boolean,
    ): Set<AuthorizationGrantType> {
        val resolved =
            values
                .map {
                    ALLOWED_GRANT_TYPES[it.trim()] ?: throw AuthException(ErrorCode.VALIDATION_ERROR)
                }.toMutableSet()
        // 공개 클라이언트는 토큰 엔드포인트 인증 수단이 없어 client_credentials 를 쓸 수 없다.
        if (publicClient) {
            resolved.remove(AuthorizationGrantType.CLIENT_CREDENTIALS)
        }
        if (resolved.isEmpty()) {
            throw AuthException(ErrorCode.VALIDATION_ERROR)
        }
        return resolved
    }

    private fun resolveScopes(values: List<String>): Set<String> {
        // 화이트리스트는 설정(taspa.oauth.allowed-scopes)에서 온다. 미설정 시 OIDC 표준 3개로 폴백한다.
        val allowed = oauthProperties.effectiveAllowedScopes()
        return values
            .map { it.trim() }
            .onEach { if (it !in allowed) throw AuthException(ErrorCode.VALIDATION_ERROR) }
            .toSet()
    }

    private fun normalizeUris(values: List<String>): Set<String> =
        values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .onEach {
                // RegisteredClient.Builder 의 IllegalArgumentException(500) 대신 400 으로 거른다.
                // 스킴은 http/https 화이트리스트 — javascript:/data: 등은 URI.isAbsolute 만으로는
                // 통과하므로 저장 자체를 막는다(악의적/손상된 관리자에 대한 심층 방어 — SAS 는
                // 등록값 정확 일치만 허용하지만 위험 스킴이 DB 에 남는 것 자체를 차단).
                // 네이티브 앱 커스텀 스킴이 필요해지면 여기에 명시적으로 추가한다.
                val valid =
                    runCatching {
                        val uri = URI(it)
                        !it.contains("#") && uri.isAbsolute && uri.scheme.lowercase() in ALLOWED_URI_SCHEMES
                    }.getOrDefault(false)
                if (!valid) throw AuthException(ErrorCode.VALIDATION_ERROR)
            }.toSet()

    companion object {
        private val ACCESS_TOKEN_TTL = Duration.ofMinutes(15)
        private val REFRESH_TOKEN_TTL = Duration.ofDays(30)
        private val ALLOWED_URI_SCHEMES = setOf("http", "https")
        private val ALLOWED_GRANT_TYPES =
            mapOf(
                AuthorizationGrantType.AUTHORIZATION_CODE.value to AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN.value to AuthorizationGrantType.REFRESH_TOKEN,
                AuthorizationGrantType.CLIENT_CREDENTIALS.value to AuthorizationGrantType.CLIENT_CREDENTIALS,
                // Device Authorization Grant(Stage 5). redirect_uri 를 쓰지 않으므로 위 authorization_code
                // 전용 redirect 검증에 걸리지 않는다. 공개 클라이언트(TV·CLI 등)에도 그대로 허용된다.
                AuthorizationGrantType.DEVICE_CODE.value to AuthorizationGrantType.DEVICE_CODE,
            )
    }
}
