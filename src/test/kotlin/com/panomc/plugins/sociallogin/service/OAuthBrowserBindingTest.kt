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
        val start = FakeExchange()
        val hash = OAuthBrowserBinding.issue(start.context, 600_000)
        assertNotNull(hash)
        assertTrue(start.set.single().isHttpOnly)

        assertTrue(OAuthBrowserBinding.matches(start.browserAfter().context, hash))
        assertFalse(OAuthBrowserBinding.matches(FakeExchange().context, hash))
        assertFalse(OAuthBrowserBinding.matches(FakeExchange(mapOf(OAuthBrowserBinding.COOKIE_NAME to "x")).context, hash))
    }

    @Test
    fun `a stored-key state is finished only by a stored-key request`() {
        val key = FrontendKeyRef(3L, "headless")
        val start = FakeExchange(key = key)
        assertNull(OAuthBrowserBinding.issue(start.context, 600_000))
        assertTrue(start.set.isEmpty())

        assertTrue(OAuthBrowserBinding.matches(FakeExchange(key = key).context, null))
        assertFalse(OAuthBrowserBinding.matches(FakeExchange().context, null))
    }
}
