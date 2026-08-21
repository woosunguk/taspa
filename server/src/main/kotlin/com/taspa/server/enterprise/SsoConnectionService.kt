package com.taspa.server.enterprise

import com.taspa.server.common.crypto.AesEncryptionService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.sso.SsoConnection
import com.taspa.server.domain.sso.SsoConnectionRepository
import com.taspa.server.domain.sso.SsoDomain
import com.taspa.server.domain.sso.SsoDomainRepository
import com.taspa.server.domain.sso.SsoProtocol
import com.taspa.server.enterprise.dto.SsoConnectionRequest
import com.taspa.server.enterprise.dto.SsoConnectionView
import com.taspa.server.enterprise.dto.SsoDomainView
import com.taspa.server.federation.SocialProviders
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthenticationMethod
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 기업 SSO 커넥션 CRUD + HRD(도메인→커넥션) + 도메인 일치 강제(정책 5) + DB→ClientRegistration 변환.
 *
 * OIDC ClientRegistration 은 짧은 캐시(cacheTtl)로 재사용하되 어떤 변경에서도 전부 evict 한다 —
 * 관리자가 커넥션을 끄거나(enabled=false) 엔드포인트를 바꾼 직후 즉시 반영되게 한다.
 * SAML RelyingPartyRegistration 캐시(DbRelyingPartyRegistrationRepository)도 함께 evict 한다.
 */
@Service
class SsoConnectionService(
    private val connectionRepository: SsoConnectionRepository,
    private val domainRepository: SsoDomainRepository,
    @Qualifier("mfaEncryptionService") private val encryptionService: AesEncryptionService,
    private val relyingPartyRegistrationCache: DbRelyingPartyRegistrationRepository,
    @Value("\${taspa.issuer-uri}") private val issuerUri: String,
    private val properties: SsoConnectionProperties,
) {
    private data class CachedRegistration(
        val registration: ClientRegistration,
        val expiresAt: Long,
    )

    private val oidcRegistrationCache = ConcurrentHashMap<String, CachedRegistration>()

    // ---- HRD / 강제 ----

    /**
     * HRD: 정규화된 이메일 도메인이 verified 로 매핑된 enabled 커넥션을 찾는다. 없으면 null(로컬 흐름 유지).
     * verified 도메인만 라우팅한다 — 미검증 도메인으로 라우팅하면 성공 핸들러의 도메인 일치 강제가
     * 반드시 실패하므로 라우팅 자체를 verified 로 제한해 흐름을 일관되게 유지한다.
     */
    @Transactional(readOnly = true)
    fun findEnabledConnectionByDomain(domain: String): SsoConnection? {
        val normalized = domain.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        val mapping = domainRepository.findByDomainAndVerifiedTrue(normalized) ?: return null
        return connectionRepository.findById(mapping.connectionId).orElse(null)?.takeIf { it.enabled }
    }

    @Transactional(readOnly = true)
    fun findByRegistrationId(registrationId: String): SsoConnection? = connectionRepository.findByRegistrationId(registrationId)

    /**
     * 도메인 일치 강제(정책 5, 보안 핵심): 공급자가 주장하는 이메일의 도메인이 이 커넥션의 verified
     * 도메인 집합에 속하는지. 불일치면 조직 IdP 가 타 도메인 이메일로 계정을 탈취하는 것을 차단한다.
     * 커넥션에 verified 도메인이 하나도 없으면 어떤 이메일도 통과하지 못한다(안전 기본값).
     */
    @Transactional(readOnly = true)
    fun isEmailDomainVerified(
        connection: SsoConnection,
        email: String?,
    ): Boolean {
        val domain =
            email
                ?.substringAfterLast('@', "")
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return false
        val mapping = domainRepository.findByDomainAndVerifiedTrue(domain) ?: return false
        return mapping.connectionId == connection.id
    }

    // ---- OIDC 변환(CompositeClientRegistrationRepository 가 호출) ----

    /** enabled OIDC 커넥션 registrationId → ClientRegistration. 미존재/비활성/타프로토콜이면 null. */
    fun findOidcClientRegistration(registrationId: String): ClientRegistration? {
        val now = System.currentTimeMillis()
        oidcRegistrationCache[registrationId]?.let { if (it.expiresAt > now) return it.registration }
        val connection =
            connectionRepository
                .findByRegistrationId(registrationId)
                ?.takeIf { it.enabled && it.protocolEnum() == SsoProtocol.OIDC }
                ?: return null
        val registration = toClientRegistration(connection)
        oidcRegistrationCache[registrationId] =
            CachedRegistration(registration, now + properties.cacheTtl.toMillis())
        return registration
    }

    /** 로그인 페이지 버튼(enforced=false 인 enabled 커넥션)용 — HRD 가 주 경로이므로 보조 진입점. */
    @Transactional(readOnly = true)
    fun optionalLoginButtons(): List<SsoConnectionView> =
        connectionRepository
            .findAllByOrderByCreatedAtDesc()
            .filter { it.enabled && !it.enforced }
            .map { toView(it) }

    private fun toClientRegistration(c: SsoConnection): ClientRegistration {
        val secret = c.oidcClientSecretEncrypted?.let { encryptionService.decrypt(it) }
        val scopes =
            (
                c.oidcScopes
                    ?.split(",", " ")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            ).toMutableSet()
        // OIDC 는 openid 스코프가 필수(id_token 발급). 관리자가 빠뜨려도 강제 포함한다.
        scopes.add("openid")
        val builder =
            ClientRegistration
                .withRegistrationId(c.registrationId)
                .clientId(requireNotNull(c.oidcClientId) { "OIDC connection has no client_id" })
                .clientAuthenticationMethod(
                    if (secret == null) ClientAuthenticationMethod.NONE else ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
                ).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(scopes)
                .authorizationUri(c.oidcAuthorizationUri)
                .tokenUri(c.oidcTokenUri)
                .userInfoUri(c.oidcUserInfoUri)
                .userInfoAuthenticationMethod(AuthenticationMethod.HEADER)
                .userNameAttributeName(c.oidcUserNameAttr?.takeIf { it.isNotBlank() } ?: "sub")
                .jwkSetUri(c.oidcJwksUri)
                .clientName(c.displayName)
        c.oidcIssuer?.takeIf { it.isNotBlank() }?.let { builder.issuerUri(it) }
        if (secret != null) builder.clientSecret(secret)
        return builder.build()
    }

    // ---- CRUD ----

    @Transactional(readOnly = true)
    fun list(): List<SsoConnectionView> = connectionRepository.findAllByOrderByCreatedAtDesc().map { toView(it) }

    @Transactional(readOnly = true)
    fun get(id: UUID): SsoConnectionView = toView(findConnection(id))

    @Transactional
    fun create(request: SsoConnectionRequest): SsoConnectionView {
        val registrationId = normalizeRegistrationId(request.registrationId)
        if (connectionRepository.existsByRegistrationId(registrationId)) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "이미 존재하는 registration_id 입니다")
        }
        val protocol = parseProtocol(request.protocol)
        val connection =
            SsoConnection(
                registrationId = registrationId,
                displayName =
                    request.displayName.trim().ifEmpty {
                        throw AuthException(ErrorCode.VALIDATION_ERROR, "표시명을 입력하세요")
                    },
                protocol = protocol.name,
                enabled = request.enabled,
                enforced = request.enforced,
                trustIdpMfa = request.trustIdpMfa,
            )
        applyProtocolFields(connection, protocol, request)
        val saved = connectionRepository.save(connection)
        replaceDomains(saved.id!!, request.domains)
        evictCaches()
        return toView(findConnection(saved.id))
    }

    @Transactional
    fun update(
        id: UUID,
        request: SsoConnectionRequest,
    ): SsoConnectionView {
        val connection = findConnection(id)
        // registration_id·protocol 은 변경 불가(경로·연결 provider 값 안정성) — 요청값이 달라도 무시한다.
        connection.displayName =
            request.displayName.trim().ifEmpty {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "표시명을 입력하세요")
            }
        connection.enabled = request.enabled
        connection.enforced = request.enforced
        connection.trustIdpMfa = request.trustIdpMfa
        applyProtocolFields(connection, connection.protocolEnum(), request)
        connectionRepository.save(connection)
        replaceDomains(id, request.domains)
        evictCaches()
        return toView(findConnection(id))
    }

    @Transactional
    fun delete(id: UUID) {
        val connection = findConnection(id)
        // sso_domains 는 ON DELETE CASCADE, federated_identities.connection_id 는 ON DELETE SET NULL.
        connectionRepository.delete(connection)
        evictCaches()
    }

    @Transactional
    fun setDomainVerified(
        id: UUID,
        domain: String,
        verified: Boolean,
    ) {
        val connection = findConnection(id)
        val normalized = domain.trim().lowercase()
        // 공개 이메일 공급자 도메인(gmail.com 등)의 verified 지정 차단(정책 5 안전 불변식 보호):
        // "verified 도메인 = 조직 전용 도메인" 이라는 가정이 무너지면, 그 커넥션 IdP 가 임의의 공개
        // 메일 주소를 주장해 해당 공급자를 쓰는 모든 로컬 계정(auto-link 경로)을 탈취할 수 있다.
        // 관리자 수동 토글이 유일한 소유 증명이므로(정책 4) 실수 방지를 위해 차단한다.
        // 도메인 소유 자동검증(DNS TXT 등)은 후속 과제.
        if (verified && normalized in PUBLIC_EMAIL_DOMAINS) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "공개 이메일 도메인($normalized)은 verified 로 지정할 수 없습니다 — 조직 전용 도메인만 허용됩니다",
            )
        }
        val mapping =
            domainRepository
                .findById(normalized)
                .orElse(null)
                ?.takeIf { it.connectionId == connection.id }
                ?: throw AuthException(ErrorCode.NOT_FOUND, "매핑되지 않은 도메인입니다")
        mapping.verified = verified
        domainRepository.save(mapping)
        evictCaches()
    }

    // ---- 내부 ----

    private fun applyProtocolFields(
        c: SsoConnection,
        protocol: SsoProtocol,
        request: SsoConnectionRequest,
    ) {
        when (protocol) {
            SsoProtocol.OIDC -> {
                c.oidcIssuer = request.oidcIssuer?.trim()
                c.oidcAuthorizationUri = requireField(request.oidcAuthorizationUri, "authorization_uri")
                c.oidcTokenUri = requireField(request.oidcTokenUri, "token_uri")
                c.oidcJwksUri = requireField(request.oidcJwksUri, "jwks_uri")
                c.oidcUserInfoUri = request.oidcUserInfoUri?.trim()
                c.oidcUserNameAttr = request.oidcUserNameAttr?.trim()?.takeIf { it.isNotEmpty() }
                c.oidcClientId = requireField(request.oidcClientId, "client_id")
                c.oidcScopes = request.oidcScopes?.trim()?.takeIf { it.isNotEmpty() }
                // secret 은 값이 제공될 때만 암호화 갱신 — update 시 빈 값은 기존 secret 유지.
                request.oidcClientSecret?.takeIf { it.isNotBlank() }?.let {
                    c.oidcClientSecretEncrypted = encryptionService.encrypt(it.trim())
                }
            }
            SsoProtocol.SAML -> {
                c.samlIdpEntityId = requireField(request.samlIdpEntityId, "idp_entity_id")
                c.samlSsoUrl = requireField(request.samlSsoUrl, "sso_url")
                c.samlVerificationCert = requireField(request.samlVerificationCert, "verification_cert")
                c.samlWantAuthnSigned = request.samlWantAuthnSigned ?: false
                c.samlEmailAttr = request.samlEmailAttr?.trim()?.takeIf { it.isNotEmpty() } ?: "email"
                c.samlNameAttr = request.samlNameAttr?.trim()?.takeIf { it.isNotEmpty() } ?: "name"
            }
        }
    }

    private fun replaceDomains(
        connectionId: UUID,
        domains: List<String>,
    ) {
        val existing = domainRepository.findByConnectionId(connectionId).associateBy { it.domain }
        val normalized = domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        // 다른 커넥션이 이미 점유한 도메인은 거부(도메인 PK — 단일 커넥션 매핑 불변식).
        normalized.forEach { d ->
            val owner = domainRepository.findById(d).orElse(null)
            if (owner != null && owner.connectionId != connectionId) {
                throw AuthException(ErrorCode.VALIDATION_ERROR, "다른 커넥션에 매핑된 도메인입니다: $d")
            }
        }
        // 제거 대상 삭제.
        existing.keys.filterNot { normalized.contains(it) }.forEach { domainRepository.deleteById(it) }
        // 신규 추가(기존 verified 플래그는 보존).
        normalized.filterNot { existing.containsKey(it) }.forEach {
            domainRepository.save(SsoDomain(domain = it, connectionId = connectionId, verified = false))
        }
    }

    private fun toView(c: SsoConnection): SsoConnectionView {
        val domains =
            domainRepository
                .findByConnectionId(c.id!!)
                .sortedBy { it.domain }
                .map { SsoDomainView(it.domain, it.verified) }
        return SsoConnectionView(
            id = c.id,
            registrationId = c.registrationId,
            displayName = c.displayName,
            protocol = c.protocol,
            orgId = c.orgId,
            enabled = c.enabled,
            enforced = c.enforced,
            trustIdpMfa = c.trustIdpMfa,
            domains = domains,
            oidcIssuer = c.oidcIssuer,
            oidcAuthorizationUri = c.oidcAuthorizationUri,
            oidcTokenUri = c.oidcTokenUri,
            oidcJwksUri = c.oidcJwksUri,
            oidcUserInfoUri = c.oidcUserInfoUri,
            oidcUserNameAttr = c.oidcUserNameAttr,
            oidcClientId = c.oidcClientId,
            oidcScopes = c.oidcScopes,
            hasOidcSecret = c.oidcClientSecretEncrypted != null,
            samlIdpEntityId = c.samlIdpEntityId,
            samlSsoUrl = c.samlSsoUrl,
            samlVerificationCert = c.samlVerificationCert,
            samlWantAuthnSigned = c.samlWantAuthnSigned ?: false,
            samlEmailAttr = c.samlEmailAttr,
            samlNameAttr = c.samlNameAttr,
            // 상대 IdP 에 등록할 SP 값(SAML). 관리자가 그대로 교환한다.
            spEntityId = "$issuerUri/saml2/service-provider-metadata/${c.registrationId}",
            spAcsUrl = "$issuerUri/login/saml2/sso/${c.registrationId}",
            spMetadataUrl = "$issuerUri/saml2/service-provider-metadata/${c.registrationId}",
            oidcRedirectUri = "$issuerUri/login/oauth2/code/${c.registrationId}",
        )
    }

    private fun findConnection(id: UUID): SsoConnection =
        connectionRepository.findById(id).orElse(null) ?: throw AuthException(ErrorCode.NOT_FOUND)

    private fun parseProtocol(value: String): SsoProtocol =
        SsoProtocol.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "protocol 은 OIDC 또는 SAML 이어야 합니다")

    /**
     * registration_id 정규화·검증. 경로 세그먼트로 쓰이므로 [a-z0-9-] 로 제한하고, 연결 provider 값
     * (`saml:`/`oidc:` + regId, federated_identities.provider VARCHAR(32))이 넘치지 않도록 길이를 제한한다.
     */
    private fun normalizeRegistrationId(value: String): String {
        val normalized = value.trim().lowercase()
        if (!normalized.matches(Regex("^[a-z0-9-]{1,$MAX_REGISTRATION_ID_LENGTH}$"))) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "registration_id 는 소문자·숫자·하이픈 $MAX_REGISTRATION_ID_LENGTH 자 이내여야 합니다",
            )
        }
        // 소셜 예약어(google/kakao/naver) 차단: 이 id 는 프로그래매틱 소셜 ClientRegistration 과 충돌한다.
        // CompositeClientRegistrationRepository 가 소셜을 먼저 반환하므로 조직 커넥션은 로그인에 결코
        // 도달하지 못하고(영구 비기능·HRD 오라우팅), 동시에 FederatedLoginSuccessHandler 의 orgConnection
        // 조회는 non-null 이 되어 해당 소셜 로그인 전체에 이 커넥션의 도메인 강제가 걸려 소셜 로그인을 깨뜨린다.
        if (normalized in SocialProviders.all) {
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "registration_id 로 소셜 공급자 예약어(${SocialProviders.all.joinToString("/")})는 사용할 수 없습니다",
            )
        }
        return normalized
    }

    private fun requireField(
        value: String?,
        name: String,
    ): String =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "$name 은(는) 필수입니다")

    /**
     * 캐시 무효화. @Transactional 안에서 호출되면 커밋 이후(afterCommit)로 미룬다 — 커밋 전에 비우면
     * 커밋 창(window) 동안 진행 중인 비트랜잭션 로그인 조회가 아직 커밋되지 않은 구(舊) 행을 다시 캐싱해
     * 커밋 후에도 최대 cacheTtl 만큼 stale ClientRegistration/RelyingPartyRegistration 이 노출된다.
     * 트랜잭션이 없으면 즉시 비운다.
     */
    private fun evictCaches() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = evictCachesNow()
                },
            )
        } else {
            evictCachesNow()
        }
    }

    private fun evictCachesNow() {
        oidcRegistrationCache.clear()
        relyingPartyRegistrationCache.evictAll()
    }

    companion object {
        // "oidc:"/"saml:"(5) + regId ≤ 32(federated_identities.provider VARCHAR(32)).
        const val MAX_REGISTRATION_ID_LENGTH = 27

        /**
         * 공개 이메일 공급자 도메인 blocklist — 이들 도메인을 커넥션의 verified 도메인으로 지정하는 것을
         * 막는다(도메인 일치 강제의 "verified=조직 전용" 불변식 보호). 완전한 목록은 아니며 흔한 공급자를
         * 커버한다(국내 포함). 관리자가 실수로 공용 도메인을 verified 로 올려 대량 계정 탈취를 유발하는 것 방지.
         */
        val PUBLIC_EMAIL_DOMAINS: Set<String> =
            setOf(
                "gmail.com",
                "googlemail.com",
                "outlook.com",
                "hotmail.com",
                "live.com",
                "msn.com",
                "yahoo.com",
                "yahoo.co.kr",
                "ymail.com",
                "icloud.com",
                "me.com",
                "mac.com",
                "aol.com",
                "gmx.com",
                "mail.com",
                "proton.me",
                "protonmail.com",
                "pm.me",
                "yandex.com",
                "zoho.com",
                "qq.com",
                "163.com",
                "126.com",
                "naver.com",
                "daum.net",
                "hanmail.net",
                "nate.com",
                "kakao.com",
            )
    }
}
