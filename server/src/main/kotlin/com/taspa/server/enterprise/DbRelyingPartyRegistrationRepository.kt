package com.taspa.server.enterprise

import com.taspa.server.domain.sso.SsoConnectionRepository
import com.taspa.server.domain.sso.SsoProtocol
import org.slf4j.LoggerFactory
import org.springframework.security.saml2.core.Saml2X509Credential
import org.springframework.security.saml2.provider.service.registration.IterableRelyingPartyRegistrationRepository
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * sso_connections(SAML) → RelyingPartyRegistration. saml2Login 의 진입·ACS·SP 메타데이터 필터가
 * registrationId 로 조회한다. 짧은 캐시(변환 결과)로 재사용하되 SsoConnectionService 의 어떤 변경에서도
 * evictAll() 로 비운다.
 *
 * IterableRelyingPartyRegistrationRepository 구현 — SP 메타데이터 열거·enumerable 리졸버가 순회한다.
 * SAML 커넥션이 0건이면 findByRegistrationId 는 null, iterator 는 빈 시퀀스를 반환한다.
 *
 * SP 서명/암호화 키페어(SP 가 AuthnRequest 서명 / 암호화 어서션 복호)는 v1 범위 밖이다. IdP 응답
 * 서명 검증(verificationX509Credentials, 보안 핵심)만 구성한다.
 */
@Component
class DbRelyingPartyRegistrationRepository(
    private val connectionRepository: SsoConnectionRepository,
    private val properties: SsoConnectionProperties,
) : IterableRelyingPartyRegistrationRepository {
    private val log = LoggerFactory.getLogger(DbRelyingPartyRegistrationRepository::class.java)

    private data class Cached(
        val registration: RelyingPartyRegistration,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun findByRegistrationId(registrationId: String): RelyingPartyRegistration? {
        val now = System.currentTimeMillis()
        cache[registrationId]?.let { if (it.expiresAt > now) return it.registration }
        val connection =
            connectionRepository
                .findByRegistrationId(registrationId)
                ?.takeIf { it.enabled && it.protocolEnum() == SsoProtocol.SAML }
                ?: return null
        return try {
            val registration = toRelyingPartyRegistration(connection)
            cache[registrationId] = Cached(registration, now + properties.cacheTtl.toMillis())
            registration
        } catch (ex: Exception) {
            // 잘못된 인증서 등으로 변환 실패 시 등록을 노출하지 않는다(로그인 시도는 일반 실패로 수렴).
            log.warn("SAML relying party registration build failed for {}", registrationId, ex)
            null
        }
    }

    override fun iterator(): MutableIterator<RelyingPartyRegistration> =
        connectionRepository
            .findByEnabledTrueAndProtocol(SsoProtocol.SAML.name)
            .mapNotNull { findByRegistrationId(it.registrationId) }
            .toMutableList()
            .iterator()

    fun evictAll() = cache.clear()

    private fun toRelyingPartyRegistration(c: com.taspa.server.domain.sso.SsoConnection): RelyingPartyRegistration {
        val cert = parseCertificate(requireNotNull(c.samlVerificationCert) { "SAML connection has no verification cert" })
        return RelyingPartyRegistration
            .withRegistrationId(c.registrationId)
            // SP entityId / ACS 는 {baseUrl}/{registrationId} 템플릿 — 요청에서 baseUrl 이 치환된다.
            .entityId("{baseUrl}/saml2/service-provider-metadata/{registrationId}")
            .assertionConsumerServiceLocation("{baseUrl}/login/saml2/sso/{registrationId}")
            .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
            .assertingPartyMetadata { party ->
                party.entityId(requireNotNull(c.samlIdpEntityId) { "SAML connection has no idp entity id" })
                party.singleSignOnServiceLocation(requireNotNull(c.samlSsoUrl) { "SAML connection has no sso url" })
                party.singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)
                party.wantAuthnRequestsSigned(c.samlWantAuthnSigned ?: false)
                party.verificationX509Credentials { creds -> creds.add(Saml2X509Credential.verification(cert)) }
            }.build()
    }

    private fun parseCertificate(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return ByteArrayInputStream(pem.trim().toByteArray(Charsets.UTF_8)).use {
            factory.generateCertificate(it) as X509Certificate
        }
    }
}
