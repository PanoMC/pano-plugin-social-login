package com.panomc.plugins.sociallogin.service

import com.panomc.platform.access.AccessContext
import com.panomc.platform.access.FrontendKeyRef
import com.panomc.platform.access.OriginClass
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val result = handler(method.name, args ?: arrayOf())
        when {
            result != null || !method.returnType.isPrimitive -> result
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == Integer.TYPE -> 0
            else -> 0L
        }
    } as T

/** An OAuth state only finishes in the browser (or stored-key front-end) that started the round trip. */
class OAuthBrowserBindingTest {

    private class FakeExchange(
        val sent: Map<String, String> = emptyMap(),
        val key: FrontendKeyRef? = null,
        val ssl: Boolean = false,
        val origin: OriginClass = OriginClass.NONE
    ) {
        val set = mutableListOf<Cookie>()

        val context: RoutingContext = run {
            val request: HttpServerRequest = proxy { name, args ->
                when (name) {
                    "getCookie" -> sent[args[0] as String]?.let { Cookie.cookie(args[0] as String, it) }
                    "cookies" -> sent.map { (n, v) -> Cookie.cookie(n, v) }.toSet()
                    "isSSL" -> ssl
                    else -> null
                }
            }
            val response: HttpServerResponse = proxy { name, args ->
                if (name == "addCookie") set += args[0] as Cookie
                null
            }
            proxy { name, args ->
                when (name) {
                    "request" -> request
                    "response" -> response
                    "get" -> if (args[0] == AccessContext.CONTEXT_KEY && (key != null || origin != OriginClass.NONE)) {
                        AccessContext(key, "127.0.0.1", origin)
                    } else {
                        null
                    }
                    else -> null
                }
            }
        }

        fun browserAfter() = FakeExchange(set.associate { it.name to it.value }, ssl = ssl)
    }

    @Test
    fun `over https the cookie is a __Host- cookie and still matches`() {
        val state = "state-s"
        val start = FakeExchange(ssl = true)
        val hash = OAuthBrowserBinding.issue(start.context, state, 600_000).browserHash
        val cookie = start.set.single()

        assertEquals(OAuthBrowserBinding.hostCookieName(state), cookie.name)
        assertTrue(cookie.name.startsWith("__Host-"))
        assertTrue(cookie.isSecure)
        assertEquals("/", cookie.path)
        assertNull(cookie.domain)
        assertEquals(CookieSameSite.LAX, cookie.sameSite)
        assertTrue(OAuthBrowserBinding.matches(start.browserAfter().context, state, hash, null, null))
    }

    @Test
    fun `plain http keeps the unprefixed name and clear expires both forms`() {
        val state = "state-p"
        val start = FakeExchange()
        OAuthBrowserBinding.issue(start.context, state, 600_000)
        assertEquals(OAuthBrowserBinding.cookieName(state), start.set.single().name)
        assertFalse(start.set.single().isSecure)

        val both = FakeExchange(
            mapOf(OAuthBrowserBinding.cookieName(state) to "a", OAuthBrowserBinding.hostCookieName(state) to "b")
        )
        OAuthBrowserBinding.clear(both.context, state)
        assertEquals(
            setOf(OAuthBrowserBinding.cookieName(state), OAuthBrowserBinding.hostCookieName(state)),
            both.set.map { it.name }.toSet()
        )
        assertTrue(both.set.all { it.maxAge == 0L })
    }

    @Test
    fun `an allowed cross-site origin over https gets SameSite None, otherwise Lax`() {
        val allowed = FakeExchange(ssl = true, origin = OriginClass.ALLOWED)
        OAuthBrowserBinding.issue(allowed.context, "state-o", 600_000)
        assertEquals(CookieSameSite.NONE, allowed.set.single().sameSite)
        assertTrue(allowed.set.single().isSecure)

        val plain = FakeExchange(origin = OriginClass.ALLOWED)
        OAuthBrowserBinding.issue(plain.context, "state-o", 600_000)
        assertEquals(CookieSameSite.LAX, plain.set.single().sameSite)

        val same = FakeExchange(ssl = true, origin = OriginClass.SAME)
        OAuthBrowserBinding.issue(same.context, "state-o", 600_000)
        assertEquals(CookieSameSite.LAX, same.set.single().sameSite)
    }

    @Test
    fun `a browser keeps at most five flow cookies`() {
        val old = (1..7).associate { OAuthBrowserBinding.cookieName("old-$it") to "v" } + mapOf("other" to "x")
        val start = FakeExchange(old)
        OAuthBrowserBinding.issue(start.context, "state-new", 600_000)

        val expired = start.set.filter { it.maxAge == 0L }.map { it.name }
        val added = start.set.filter { it.maxAge > 0L }.map { it.name }
        assertEquals(3, expired.size)
        assertFalse("other" in expired)
        assertEquals(listOf(OAuthBrowserBinding.cookieName("state-new")), added)
        assertEquals(OAuthBrowserBinding.MAX_FLOW_COOKIES, old.size - 1 - expired.size + added.size)

        val few = FakeExchange((1..3).associate { OAuthBrowserBinding.cookieName("f-$it") to "v" })
        OAuthBrowserBinding.issue(few.context, "state-few", 600_000)
        assertEquals(1, few.set.size)
    }

    @Test
    fun `the starting browser matches, another browser does not`() {
        val state = "state-1"
        val start = FakeExchange()
        val issued = OAuthBrowserBinding.issue(start.context, state, 600_000)
        val hash = issued.browserHash
        assertNotNull(hash)
        assertNull(issued.bindingSecret)
        assertTrue(start.set.single().isHttpOnly)

        assertTrue(OAuthBrowserBinding.matches(start.browserAfter().context, state, hash, null, null))
        assertFalse(OAuthBrowserBinding.matches(FakeExchange().context, state, hash, null, null))
        assertFalse(
            OAuthBrowserBinding.matches(FakeExchange(mapOf(OAuthBrowserBinding.cookieName(state) to "x")).context, state, hash, null, null)
        )
    }

    @Test
    fun `parallel flows keep their own cookies`() {
        val first = FakeExchange()
        val firstHash = OAuthBrowserBinding.issue(first.context, "state-a", 600_000).browserHash
        val second = FakeExchange()
        val secondHash = OAuthBrowserBinding.issue(second.context, "state-b", 600_000).browserHash
        val browser = FakeExchange(first.set.associate { it.name to it.value } + second.set.associate { it.name to it.value })

        assertTrue(OAuthBrowserBinding.matches(browser.context, "state-a", firstHash, null, null))
        assertTrue(OAuthBrowserBinding.matches(browser.context, "state-b", secondHash, null, null))
    }

    @Test
    fun `a stored-key state is finished only by a stored-key request with its binding secret`() {
        val key = FrontendKeyRef(3L, "headless")
        val start = FakeExchange(key = key)
        val issued = OAuthBrowserBinding.issue(start.context, "state-k", 600_000)
        assertNull(issued.browserHash)
        assertNotNull(issued.secretHash)
        assertNotNull(issued.bindingSecret)
        assertTrue(start.set.isEmpty())

        val stored = FakeExchange(key = key).context
        assertTrue(OAuthBrowserBinding.matches(stored, "state-k", null, issued.secretHash, issued.bindingSecret))
        assertFalse(OAuthBrowserBinding.matches(stored, "state-k", null, issued.secretHash, null))
        assertFalse(OAuthBrowserBinding.matches(stored, "state-k", null, issued.secretHash, "wrong"))
        assertFalse(OAuthBrowserBinding.matches(FakeExchange().context, "state-k", null, issued.secretHash, issued.bindingSecret))
        assertFalse(OAuthBrowserBinding.matches(stored, "state-k", null, null, null))
    }
}
