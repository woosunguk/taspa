package com.taspa.server.common.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM 대칭 암호화. 키 문자열을 SHA-256 으로 32바이트 키로 정규화한다.
 * 용도별(mfa/jwk) 키 분리를 위해 빈 등록은 EncryptionConfig 에서 한다 — 주입 시 @Qualifier 필수.
 */
class AesEncryptionService(
    private val encryptionKey: String,
) {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
    }

    private val secretKey: SecretKeySpec by lazy {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(encryptionKey.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext

        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(ciphertext: String): String {
        val combined = Base64.getDecoder().decode(ciphertext)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        val plaintext = cipher.doFinal(encrypted)
        return String(plaintext, Charsets.UTF_8)
    }
}
