package com.taspa.server.common.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object SecureTokenGenerator {
    private val secureRandom = SecureRandom()

    fun generateToken(): String {
        val bytes = ByteArray(32) // 256-bit
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
