package com.taspa.server.token

import com.nimbusds.jose.jwk.RSAKey
import com.taspa.server.common.crypto.AesEncryptionService
import com.taspa.server.domain.jwk.JwkKey
import com.taspa.server.domain.jwk.JwkKeyRepository
import com.taspa.server.domain.jwk.JwkKeyStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * JWT 서명용 RSA 키의 DB 영속화와 회전.
 *
 * - 부트스트랩: ACTIVE 키가 없으면 RSA 2048 키를 생성해 AES-GCM 암호화 저장(kid=UUID) — 재기동 멱등.
 * - currentKeys(): ACTIVE+RETIRED 전체를 복호화 반환. `/oauth2/jwks` 게시(공개 부분만 노출)와
 *   kid 매칭 검증에 쓰인다. 토큰 발급/JWKS 요청마다 DB 를 치지 않도록 60초 인메모리 캐시.
 * - 회전 유예 구간(ACTIVE+RETIRED 공존)에는 NimbusJwtEncoder 가 서명 키 다중 매칭으로 예외를
 *   던지므로, TokenCustomizerConfig 가 jwsHeader.keyId(activeKid())로 서명 키를 고정한다.
 */
@Service
class JwkStorageService(
    private val jwkKeyRepository: JwkKeyRepository,
    @Qualifier("jwkEncryptionService")
    private val encryptionService: AesEncryptionService,
    private val properties: JwkProperties,
) {
    companion object {
        private val CACHE_TTL = Duration.ofSeconds(60)
    }

    private val log = LoggerFactory.getLogger(JwkStorageService::class.java)

    private data class Snapshot(
        val keys: List<RSAKey>,
        val activeKid: String,
        val loadedAt: Instant,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun currentKeys(): List<RSAKey> = current().keys

    fun activeKid(): String = current().activeKid

    /** 즉시 회전: ACTIVE 전부 RETIRED 로 내리고 새 ACTIVE 를 만든다(유출 대응 runbook 포함). */
    @Transactional
    fun rotate(): String = rotateLocked(jwkKeyRepository.findByStatusForUpdate(JwkKeyStatus.ACTIVE), Instant.now())

    /** 스케줄러용: ACTIVE 키 활성화 후 rotation-period 가 지났을 때만 회전한다. */
    @Transactional
    fun rotateIfDue(): Boolean {
        val actives = jwkKeyRepository.findByStatusForUpdate(JwkKeyStatus.ACTIVE)
        val now = Instant.now()
        val due =
            actives.isEmpty() ||
                actives.any { (it.activatedAt ?: it.createdAt).plus(properties.rotationPeriod) <= now }
        if (!due) {
            return false
        }
        rotateLocked(actives, now)
        return true
    }

    /** retirement-grace 를 넘긴 RETIRED 키 삭제 — 삭제 즉시 해당 키로 서명된 토큰은 전부 무효가 된다. */
    @Transactional
    fun purgeExpired(): Int {
        val cutoff = Instant.now().minus(properties.retirementGrace)
        val purged = jwkKeyRepository.deleteRetiredBefore(JwkKeyStatus.RETIRED, cutoff)
        if (purged > 0) {
            invalidateCacheAfterCommit()
            log.info("jwk purge: removed {} retired key(s)", purged)
        }
        return purged
    }

    private fun rotateLocked(
        actives: List<JwkKey>,
        now: Instant,
    ): String {
        actives.forEach {
            it.status = JwkKeyStatus.RETIRED
            it.retiredAt = now
        }
        val created = createActiveKey(now)
        invalidateCacheAfterCommit()
        log.info("jwk rotate: retired={}, newActiveKid={}", actives.map { it.kid }, created.kid)
        return created.kid
    }

    /**
     * 캐시 무효화는 커밋 확정 **후** 에 수행한다. 트랜잭션 본문에서 즉시 비우면 무효화~커밋 사이의
     * 동시 조회가 READ COMMITTED 스냅샷상 회전 전 상태를 최대 TTL(60초) 동안 재캐시해 무효화가
     * 무력화된다. 롤백 시에는 DB 상태가 그대로이므로 기존 캐시도 그대로 두는 것이 맞다.
     * (다중 인스턴스에서는 어차피 원격 캐시를 무효화할 수 없어 TTL 60초가 본질적 반영 상한이다.)
     */
    private fun invalidateCacheAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        snapshot = null
                    }
                },
            )
        } else {
            snapshot = null
        }
    }

    private fun current(): Snapshot {
        snapshot?.takeIf { Duration.between(it.loadedAt, Instant.now()) < CACHE_TTL }?.let { return it }
        synchronized(this) {
            snapshot?.takeIf { Duration.between(it.loadedAt, Instant.now()) < CACHE_TTL }?.let { return it }
            return load().also { snapshot = it }
        }
    }

    private fun load(): Snapshot {
        var rows = jwkKeyRepository.findAll()
        var active = rows.firstOrNull { it.status == JwkKeyStatus.ACTIVE }
        if (active == null) {
            active = createActiveKey(Instant.now())
            rows = rows + active
            log.info("jwk bootstrap: created active key kid={}", active.kid)
        }
        val keys =
            rows.map { row ->
                try {
                    RSAKey.parse(encryptionService.decrypt(row.keyJsonEncrypted))
                } catch (e: Exception) {
                    // mfa 폴백 키로 저장된 행을 전용 taspa.jwk.encryption-key 로 읽는 키 전환 misconfig 가
                    // 전형적 원인 — 원인 불명의 AEADBadTagException 대신 복구 절차를 가리키는 메시지로 죽인다.
                    throw IllegalStateException(
                        "jwk_keys 복호화 실패(kid=${row.kid}) — 암호화 키 변경이 의심된다. " +
                            "이전 키(taspa.jwk.encryption-key 미설정 시 mfa 키 폴백)로 되돌리거나, " +
                            "키 전환 절차(architecture.md §3.1: 재암호화 스크립트 또는 전체 삭제 후 " +
                            "재부트스트랩 — 후자는 기존 토큰 전체 무효화)를 따르라.",
                        e,
                    )
                }
            }
        return Snapshot(keys = keys, activeKid = active.kid, loadedAt = Instant.now())
    }

    private fun createActiveKey(now: Instant): JwkKey {
        val kid = UUID.randomUUID().toString()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rsaKey =
            RSAKey
                .Builder(keyPair.public as RSAPublicKey)
                .privateKey(keyPair.private as RSAPrivateKey)
                .keyID(kid)
                .build()
        return jwkKeyRepository.save(
            JwkKey(
                kid = kid,
                keyJsonEncrypted = encryptionService.encrypt(rsaKey.toJSONString()),
                status = JwkKeyStatus.ACTIVE,
                activatedAt = now,
            ),
        )
    }
}
