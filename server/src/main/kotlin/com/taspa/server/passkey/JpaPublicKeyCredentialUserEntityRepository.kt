package com.taspa.server.passkey

import com.taspa.server.domain.passkey.PasskeyUserEntity
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import com.taspa.server.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Spring Security WebAuthn 의 사용자 핸들 저장소 어댑터.
 * findByUsername 이 null 을 반환하면 Webauthn4JRelyingPartyOperations 가 새 엔티티를 만들어
 * save() 를 호출하므로, save 에서 email(name) 로 users 행을 조인해 user_id 를 매핑한다.
 */
@Component
@Transactional
class JpaPublicKeyCredentialUserEntityRepository(
    private val passkeyUserEntityRepository: PasskeyUserEntityRepository,
    private val userRepository: UserRepository,
) : PublicKeyCredentialUserEntityRepository {
    private val log = LoggerFactory.getLogger(JpaPublicKeyCredentialUserEntityRepository::class.java)

    @Transactional(readOnly = true)
    override fun findById(id: Bytes): PublicKeyCredentialUserEntity? =
        passkeyUserEntityRepository.findByExternalId(id.toBase64UrlString())?.toApi()

    @Transactional(readOnly = true)
    override fun findByUsername(username: String): PublicKeyCredentialUserEntity? =
        passkeyUserEntityRepository.findByName(username)?.toApi()

    override fun save(userEntity: PublicKeyCredentialUserEntity) {
        val existing = passkeyUserEntityRepository.findByName(userEntity.name)
        if (existing != null) {
            existing.displayName = userEntity.displayName
            passkeyUserEntityRepository.save(existing)
            return
        }
        // 익명 어서션 옵션 요청(usernameless) 시 Webauthn4JRelyingPartyOperations 는 인증 여부를 확인하지
        // 않고 "anonymousUser" 이름으로 save 를 호출한다(6.4.4 실측). users 행이 없는 이름은 영속화하지
        // 않는다 — 반환된 transient 엔티티만으로 allowCredentials 빈 배열 응답이 성립한다.
        val user = userRepository.findByEmail(userEntity.name)
        if (user == null) {
            log.debug("skip persisting webauthn user entity for unknown username: {}", userEntity.name)
            return
        }
        passkeyUserEntityRepository.save(
            PasskeyUserEntity(
                userId = user.id!!,
                externalId = userEntity.id.toBase64UrlString(),
                name = userEntity.name,
                displayName = userEntity.displayName,
            ),
        )
    }

    override fun delete(id: Bytes) {
        passkeyUserEntityRepository.deleteByExternalId(id.toBase64UrlString())
    }

    private fun PasskeyUserEntity.toApi(): PublicKeyCredentialUserEntity =
        ImmutablePublicKeyCredentialUserEntity
            .builder()
            .id(Bytes.fromBase64(externalId))
            .name(name)
            .displayName(displayName)
            .build()
}
