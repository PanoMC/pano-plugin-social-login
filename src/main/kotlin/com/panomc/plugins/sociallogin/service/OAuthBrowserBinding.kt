package com.panomc.plugins.sociallogin.service

import com.panomc.platform.access.AccessContext
import com.panomc.platform.util.TrustedProxyIpResolver
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.ext.web.RoutingContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Ties an OAuth state to the caller that started the round trip, so a callback link made by someone else does
 * nothing for another person.
 *
 * - A browser gets a random value in an HttpOnly, SameSite=Lax cookie whose hash is kept in the state row. The
 *   cookie name carries a short, non-secret derivative of the state ([cookieName]), so parallel flows (two tabs,
 *   sign-in plus link) each keep their own cookie. `/process` only finishes a state that comes back with it.
 * - A front-end server with a stored front-end key cannot hold our cookie. Its init answer carries a random
 *   `bindingSecret` (only to that caller) whose hash is kept in the state row; `/process` must send it back.
 */
object OAuthBrowserBinding {
    const val COOKIE_PREFIX = "pano_social_login_flow_"
    private val random = SecureRandom()

    /** What [issue] produced: the hashes to keep with the state, and the secret to hand to a stored-key caller. */
    data class Issued(val browserHash: String?, val secretHash: String?, val bindingSecret: String?)

    fun hasStoredKey(context: RoutingContext): Boolean =
        AccessContext.of(context)?.key?.let { !it.isInternal } == true

    fun sha256(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    /** The binding cookie of the flow with [state]: a short hash of the state, which does not reveal it. */
    fun cookieName(state: String): String = COOKIE_PREFIX + sha256("cookie:$state").take(16)

    private fun randomValue(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Binds [state] to this caller. A browser gets the cookie; a stored-key request gets a binding secret that
     * only the init answer carries.
     */
    fun issue(context: RoutingContext, state: String, ttlMs: Long, trustedProxies: List<String> = emptyList()): Issued {
        val value = randomValue()

        if (hasStoredKey(context)) {
            return Issued(browserHash = null, secretHash = sha256(value), bindingSecret = value)
        }

        context.response().addCookie(cookie(context, cookieName(state), value, ttlMs / 1000, trustedProxies))
        return Issued(browserHash = sha256(value), secretHash = null, bindingSecret = null)
    }

    /**
     * Whether this request is the caller the state was made for: the browser holding the cookie of [browserHash],
     * or the stored-key front-end that sends the [bindingSecret] of [secretHash]. A state with neither is refused.
     */
    fun matches(
        context: RoutingContext,
        state: String,
        browserHash: String?,
        secretHash: String?,
        bindingSecret: String?
    ): Boolean {
        if (browserHash != null) {
            val value = context.request().getCookie(cookieName(state))?.value ?: return false
            return constantTimeEquals(sha256(value), browserHash)
        }

        if (secretHash != null) {
            if (!hasStoredKey(context) || bindingSecret.isNullOrEmpty()) return false
            return constantTimeEquals(sha256(bindingSecret), secretHash)
        }

        return false
    }

    fun clear(context: RoutingContext, state: String, trustedProxies: List<String> = emptyList()) {
        context.response().addCookie(cookie(context, cookieName(state), "", 0, trustedProxies))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /**
     * HTTPS from the connection itself, or from `X-Forwarded-Proto` only when the peer is a trusted proxy (the
     * platform's `server.trusted-proxies`, plus private addresses when the platform trusts those).
     */
    private fun isSecure(context: RoutingContext, trustedProxies: List<String>): Boolean {
        val request = context.request()
        if (request.isSSL) return true

        val peer = request.remoteAddress()?.host().orEmpty()
        if (peer.isEmpty() || !TrustedProxyIpResolver.isTrustedPeer(peer, trustedProxies)) return false

        return request.getHeader("X-Forwarded-Proto")
            ?.split(",")?.any { it.trim().equals("https", ignoreCase = true) } == true
    }

    private fun cookie(
        context: RoutingContext,
        name: String,
        value: String,
        maxAgeSeconds: Long,
        trustedProxies: List<String>
    ): Cookie =
        Cookie.cookie(name, value).apply {
            path = "/"
            isHttpOnly = true
            isSecure = isSecure(context, trustedProxies)
            sameSite = CookieSameSite.LAX
            maxAge = maxAgeSeconds
        }
}
