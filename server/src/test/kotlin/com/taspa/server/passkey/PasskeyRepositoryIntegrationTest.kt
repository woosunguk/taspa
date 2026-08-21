package com.taspa.server.passkey

import com.taspa.server.domain.passkey.PasskeyCredentialRepository
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.webauthn.api.AuthenticatorTransport
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import java.time.Instant
import java.time.temporal.ChronoUnit

class PasskeyRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userEntityAdapter: JpaPublicKeyCredentialUserEntityRepository

    @Autowired lateinit var credentialAdapter: JpaUserCredentialRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passkeyUserEntityRepository: PasskeyUserEntityRepository

    @Autowired lateinit var passkeyCredentialRepository: PasskeyCredentialRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val email = "passkey-repo@example.com"

    @BeforeEach
    fun setUp() {
        passkeyCredentialRepository.deleteAll()
        passkeyUserEntityRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode("SecureP@ssw0rd123"), emailVerified = true),
        )
    }

    @Test
    fun `user entity roundtrip - save then findByUsername and findById`() {
        val externalId = Bytes.random()
        userEntityAdapter.save(
            ImmutablePublicKeyCredentialUserEntity
                .builder()
                .id(externalId)
                .name(email)
                .displayName("테스터")
                .build(),
        )

        val byUsername = userEntityAdapter.findByUsername(email)
        assertThat(byUsername).isNotNull
        assertThat(byUsername!!.id).isEqualTo(externalId)
        assertThat(byUsername.name).isEqualTo(email)
        assertThat(byUsername.displayName).isEqualTo("테스터")

        val byId = userEntityAdapter.findById(externalId)
        assertThat(byId).isNotNull
        assertThat(byId!!.name).isEqualTo(email)

        // users.id 와 조인되어 저장되었는지 확인 (external_id 는 users.id 를 노출하지 않는다).
        val row = passkeyUserEntityRepository.findByExternalId(externalId.toBase64UrlString())
        assertThat(row).isNotNull
        assertThat(row!!.userId).isEqualTo(userRepository.findByEmail(email)!!.id)
    }

    @Test
    fun `credential record 14 fields roundtrip`() {
        val userEntityId = savedUserEntityId()
        val credentialId = Bytes.random()
        val publicKeyBytes = byteArrayOf(1, 2, 3, 4, 5)
        val attestationObject = Bytes(byteArrayOf(10, 11, 12))
        val attestationClientDataJson = Bytes(byteArrayOf(20, 21))
        val created = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val lastUsed = created.plusSeconds(60)

        credentialAdapter.save(
            ImmutableCredentialRecord
                .builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(credentialId)
                .userEntityUserId(userEntityId)
                .publicKey(ImmutablePublicKeyCose(publicKeyBytes))
                .signatureCount(42)
                .uvInitialized(true)
                .transports(setOf(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID))
                .backupEligible(true)
                .backupState(true)
                .attestationObject(attestationObject)
                .attestationClientDataJSON(attestationClientDataJson)
                .created(created)
                .lastUsed(lastUsed)
                .label("테스트 키")
                .build(),
        )

        val found = credentialAdapter.findByCredentialId(credentialId)
        assertThat(found).isNotNull
        assertRecord(found!!, credentialId, userEntityId, publicKeyBytes, created, lastUsed)
        assertThat(found.attestationObject).isEqualTo(attestationObject)
        assertThat(found.attestationClientDataJSON).isEqualTo(attestationClientDataJson)

        val byUser = credentialAdapter.findByUserId(userEntityId)
        assertThat(byUser).hasSize(1)
        assertThat(byUser[0].credentialId).isEqualTo(credentialId)
    }

    @Test
    fun `save is upsert - signature count and lastUsed are updated on re-save`() {
        val userEntityId = savedUserEntityId()
        val credentialId = Bytes.random()
        val created = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        credentialAdapter.save(record(credentialId, userEntityId, signatureCount = 1, created = created))
        val updatedLastUsed = created.plusSeconds(120)
        credentialAdapter.save(
            record(credentialId, userEntityId, signatureCount = 7, created = created, lastUsed = updatedLastUsed),
        )

        assertThat(passkeyCredentialRepository.count()).isEqualTo(1)
        val found = credentialAdapter.findByCredentialId(credentialId)!!
        assertThat(found.signatureCount).isEqualTo(7)
        assertThat(found.lastUsed).isEqualTo(updatedLastUsed)
    }

    private fun savedUserEntityId(): Bytes {
        val externalId = Bytes.random()
        userEntityAdapter.save(
            ImmutablePublicKeyCredentialUserEntity
                .builder()
                .id(externalId)
                .name(email)
                .displayName(email)
                .build(),
        )
        return externalId
    }

    @Test
    fun `label longer than column limit is truncated instead of failing with a constraint violation`() {
        // 등록 필터는 저장 예외를 400 으로 매핑하지 않으므로(실측) 어댑터가 varchar(100) 초과분을 절단한다.
        val userEntityId = savedUserEntityId()
        val credentialId = Bytes.random()
        val created = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        credentialAdapter.save(
            record(credentialId, userEntityId, signatureCount = 1, created = created, label = "가".repeat(150)),
        )

        val found = credentialAdapter.findByCredentialId(credentialId)!!
        assertThat(found.label).isEqualTo("가".repeat(100))
    }

    private fun record(
        credentialId: Bytes,
        userEntityId: Bytes,
        signatureCount: Long,
        created: Instant,
        lastUsed: Instant = created,
        label: String = "업서트 키",
    ): CredentialRecord =
        ImmutableCredentialRecord
            .builder()
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(credentialId)
            .userEntityUserId(userEntityId)
            .publicKey(ImmutablePublicKeyCose(byteArrayOf(9, 9, 9)))
            .signatureCount(signatureCount)
            .uvInitialized(true)
            .transports(setOf(AuthenticatorTransport.INTERNAL))
            .backupEligible(false)
            .backupState(false)
            .created(created)
            .lastUsed(lastUsed)
            .label(label)
            .build()

    private fun assertRecord(
        found: CredentialRecord,
        credentialId: Bytes,
        userEntityId: Bytes,
        publicKeyBytes: ByteArray,
        created: Instant,
        lastUsed: Instant,
    ) {
        assertThat(found.credentialType).isEqualTo(PublicKeyCredentialType.PUBLIC_KEY)
        assertThat(found.credentialId).isEqualTo(credentialId)
        assertThat(found.userEntityUserId).isEqualTo(userEntityId)
        assertThat(found.publicKey.bytes).isEqualTo(publicKeyBytes)
        assertThat(found.signatureCount).isEqualTo(42)
        assertThat(found.isUvInitialized).isTrue()
        assertThat(found.transports)
            .containsExactlyInAnyOrder(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID)
        assertThat(found.isBackupEligible).isTrue()
        assertThat(found.isBackupState).isTrue()
        assertThat(found.created).isEqualTo(created)
        assertThat(found.lastUsed).isEqualTo(lastUsed)
        assertThat(found.label).isEqualTo("테스트 키")
    }
}
