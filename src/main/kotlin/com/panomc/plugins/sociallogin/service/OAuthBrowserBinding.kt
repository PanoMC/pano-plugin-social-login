package com.panomc.plugins.sociallogin.service

import com.panomc.platform.access.AccessContext
import com.panomc.platform.access.OriginClass
import com.panomc.platform.util.TrustedProxyIpResolver
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.ext.web.RoutingContext
import java.net.Inet6Address
import java.net.InetAddress
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
 *   Over HTTPS the name gets the `__Host-` prefix (Secure, `Path=/`, no `Domain`), so a sibling subdomain cannot
 *   plant or overwrite it. A browser page on an allowed cross-site origin gets `SameSite=None` (HTTPS only), since
 *   its credentialed fetch would never carry a Lax cookie. At most [MAX_FLOW_COOKIES] flow cookies are kept.
 * - A front-end server with a stored front-end key cannot hold our cookie. Its init answer carries a random
 *   `bindingSecret` (only to that caller) whose hash is kept in the state row; `/process` must send it back.
 */
object OAuthBrowserBinding {
    const val COOKIE_PREFIX = "pano_social_login_flow_"

    /** The prefix browsers only accept on a Secure cookie with `Path=/` and no `Domain`. */
    const val HOST_PREFIX = "__Host-"

    /** Flow cookies one browser keeps at most (the new one included); older ones are expired when a flow starts. */
    const val MAX_FLOW_COOKIES = 5
    private val random = SecureRandom()

    /** What [issue] produced: the hashes to keep with the state, and the secret to hand to a stored-key caller. */
    data class Issued(val browserHash: String?, val secretHash: String?, val bindingSecret: String?)

    fun hasStoredKey(context: RoutingContext): Boolean =
        AccessContext.of(context)?.key?.let { !it.isInternal } == true

    fun sha256(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    /** The binding cookie of the flow with [state]: a short hash of the state, which does not reveal it. */
    fun cookieName(state: String): String = COOKIE_PREFIX + sha256("cookie:$state").take(16)

    /** [cookieName] with the `__Host-` prefix: the name used over HTTPS. */
    fun hostCookieName(state: String): String = HOST_PREFIX + cookieName(state)

    private fun isFlowCookie(name: String): Boolean =
        name.startsWith(COOKIE_PREFIX) || name.startsWith(HOST_PREFIX + COOKIE_PREFIX)

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

        val secure = isSecure(context, trustedProxies)
        expireExtraFlowCookies(context, secure, trustedProxies)

        val name = if (secure) hostCookieName(state) else cookieName(state)
        context.response().addCookie(cookie(context, name, value, ttlMs / 1000, secure))
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
            val request = context.request()
            val value = (request.getCookie(hostCookieName(state)) ?: request.getCookie(cookieName(state)))?.value
                ?: return false
            return constantTimeEquals(sha256(value), browserHash)
        }

        if (secretHash != null) {
            if (!hasStoredKey(context) || bindingSecret.isNullOrEmpty()) return false
            return constantTimeEquals(sha256(bindingSecret), secretHash)
        }

        return false
    }

    /** Expires the flow cookie of [state] under both names (the `__Host-` one is only accepted as Secure). */
    fun clear(context: RoutingContext, state: String, trustedProxies: List<String> = emptyList()) {
        val request = context.request()
        val secure = isSecure(context, trustedProxies)
        val response = context.response()
        if (secure || request.getCookie(hostCookieName(state)) != null) {
            response.addCookie(cookie(context, hostCookieName(state), "", 0, true))
        }
        if (!secure || request.getCookie(cookieName(state)) != null) {
            response.addCookie(cookie(context, cookieName(state), "", 0, secure))
        }
    }

    /**
     * Keeps the browser at [MAX_FLOW_COOKIES] flow cookies once the new one is added: abandoned flows (closed tabs)
     * would otherwise pile up cookies until the request headers get too big. Their age is unknown, so the extras are
     * picked by name; a flow whose cookie is expired here can simply be started again.
     */
    private fun expireExtraFlowCookies(context: RoutingContext, secure: Boolean, trustedProxies: List<String>) {
        val names = context.request().cookies().orEmpty().map { it.name }.filter { isFlowCookie(it) }.distinct().sorted()
        val extra = names.size - (MAX_FLOW_COOKIES - 1)
        if (extra <= 0) return

        names.take(extra).forEach { name ->
            context.response().addCookie(cookie(context, name, "", 0, secure || name.startsWith(HOST_PREFIX)))
        }
    }

    /**
     * A listed proxy, or a loopback / private-network peer (the platform's default `trust-private-proxies`).
     * Deliberately not `TrustedProxyIpResolver.isTrustedPeer`: Pano v1.2.1 changed its JVM signature (a third,
     * defaulted parameter), so a plugin compiled against one version throws NoSuchMethodError on the other.
     * `isTrustedProxy(String, List)` is the same in every version.
     */
    private fun isTrustedPeer(peer: String, trustedProxies: List<String>): Boolean {
        if (TrustedProxyIpResolver.isTrustedProxy(peer, trustedProxies)) return true

        val address = try {
            InetAddress.getByName(peer.removePrefix("[").removeSuffix("]"))
        } catch (_: Exception) {
            return false
        }

        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
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
        if (peer.isEmpty() || !isTrustedPeer(peer, trustedProxies)) return false

        return request.getHeader("X-Forwarded-Proto")
            ?.split(",")?.any { it.trim().equals("https", ignoreCase = true) } == true
    }

    /**
     * `SameSite=None` only for a browser page on an origin the site owner allowed that is not this site, and only
     * over HTTPS (browsers drop a non-Secure None cookie); Lax otherwise.
     */
    private fun sameSiteFor(context: RoutingContext, secure: Boolean): CookieSameSite {
        val access = AccessContext.of(context)
        return if (secure && access?.key == null && access?.origin == OriginClass.ALLOWED) {
            CookieSameSite.NONE
        } else {
            CookieSameSite.LAX
        }
    }

    // `Path=/` and no `Domain`: what a `__Host-` name requires.
    private fun cookie(
        context: RoutingContext,
        name: String,
        value: String,
        maxAgeSeconds: Long,
        secure: Boolean
    ): Cookie =
        Cookie.cookie(name, value).apply {
            path = "/"
            isHttpOnly = true
            isSecure = secure
            sameSite = sameSiteFor(context, secure)
            maxAge = maxAgeSeconds
        }
}
