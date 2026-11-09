package com.panomc.plugins.sociallogin.service

import com.panomc.platform.access.AccessContext
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.ext.web.RoutingContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Ties an OAuth state to the browser that started the round trip: a random value in an HttpOnly, SameSite=Lax
 * cookie, whose hash is kept in the state row. `/process` only finishes a state that comes back with that
 * cookie, so a callback link made by someone else does nothing in another person's browser.
 *
 * A front-end server with a stored front-end key cannot hold our cookie; its states carry no hash and are
 * only accepted from a stored-key request.
 */
object OAuthBrowserBinding {
    const val COOKIE_NAME = "pano_social_login_flow"
    private val random = SecureRandom()

    fun hasStoredKey(context: RoutingContext): Boolean =
        AccessContext.of(context)?.key?.let { !it.isInternal } == true

    fun sha256(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    /** Sets the binding cookie and returns the hash to store with the state; null for a stored-key request. */
    fun issue(context: RoutingContext, ttlMs: Long): String? {
        if (hasStoredKey(context)) return null

        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        context.response().addCookie(cookie(context, value, ttlMs / 1000))
        return sha256(value)
    }

    /** Whether this request is the browser (or stored-key front-end) the state with [expectedHash] was made for. */
    fun matches(context: RoutingContext, expectedHash: String?): Boolean {
        if (expectedHash == null) return hasStoredKey(context)

        val value = context.request().getCookie(COOKIE_NAME)?.value ?: return false
        return MessageDigest.isEqual(sha256(value).toByteArray(), expectedHash.toByteArray())
    }

    fun clear(context: RoutingContext) {
        context.response().addCookie(cookie(context, "", 0))
    }

    private fun isSecure(context: RoutingContext): Boolean {
        val request = context.request()
        if (request.isSSL) return true
        return request.getHeader("X-Forwarded-Proto")
            ?.split(",")?.any { it.trim().equals("https", ignoreCase = true) } == true
    }

    private fun cookie(context: RoutingContext, value: String, maxAgeSeconds: Long): Cookie =
        Cookie.cookie(COOKIE_NAME, value).apply {
            path = "/"
            isHttpOnly = true
            isSecure = isSecure(context)
            sameSite = CookieSameSite.LAX
            maxAge = maxAgeSeconds
        }
}
