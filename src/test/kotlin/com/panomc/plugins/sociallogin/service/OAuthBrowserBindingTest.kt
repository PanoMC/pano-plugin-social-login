package com.panomc.plugins.sociallogin.service

import com.panomc.platform.access.AccessContext
import com.panomc.platform.access.FrontendKeyRef
import io.vertx.core.http.Cookie
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.RoutingContext
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

    private class FakeExchange(val sent: Map<String, String> = emptyMap(), val key: FrontendKeyRef? = null) {
        val set = mutableListOf<Cookie>()

        val context: RoutingContext = run {
            val request: HttpServerRequest = proxy { name, args ->
                when (name) {
                    "getCookie" -> sent[args[0] as String]?.let { Cookie.cookie(args[0] as String, it) }
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
                    "get" -> if (args[0] == AccessContext.CONTEXT_KEY && key != null) AccessContext(key, "127.0.0.1") else null
                    else -> null
                }
            }
        }

        fun browserAfter() = FakeExchange(set.associate { it.name to it.value })
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
