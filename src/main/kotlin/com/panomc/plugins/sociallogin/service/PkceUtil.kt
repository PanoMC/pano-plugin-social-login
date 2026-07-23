package com.panomc.plugins.sociallogin.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) helpers. Required by Twitter/X OAuth 2.0; the verifier is created at
 * authorization-init time, stored server-side in the OAuth state row, and replayed during
 * the token exchange.
 */
object PkceUtil {
    private val random = SecureRandom()

    fun generateVerifier(): String {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
