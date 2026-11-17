package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Base class for a single social provider's OAuth implementation. Concrete subclasses only
 * differ in endpoints, scopes and how the provider's user payload maps onto [SocialProfile].
 */
abstract class OAuthProvider(
    protected val configManager: PluginConfigManager<SocialLoginConfig>,
    protected val vertx: Vertx
) {
    abstract val provider: SocialProvider

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_SECONDS = 15
    }

    /** X (formerly Twitter) requires PKCE; the others use the classic confidential-client flow. */
    open val usesPkce: Boolean = false

    @Volatile
    private var client: WebClient? = null

    /**
     * Created on first use, on the host Vert.x. Timeouts keep a hanging provider from hanging the sign-in: the
     * connect gives up after [CONNECT_TIMEOUT_MS], a connection that sends nothing for [IDLE_TIMEOUT_SECONDS]
     * is closed. [close] drops it; the next use creates a fresh one.
     */
    protected val webClient: WebClient
        get() = client ?: synchronized(this) {
            client ?: WebClient.create(
                vertx,
                WebClientOptions()
                    .setSsl(true)
                    .setTrustAll(false)
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setIdleTimeout(IDLE_TIMEOUT_SECONDS)
                    .setIdleTimeoutUnit(TimeUnit.SECONDS)
            ).also { client = it }
        }

    /** Closes the web client, if one was created. Called when the plugin stops or is disabled. */
    fun close() {
        val current = synchronized(this) { client.also { client = null } } ?: return
        runCatching { current.close() }
    }

    protected val logger by lazy { LoggerFactory.getLogger("SocialLogin:${provider.key}") }

    /** Switched on and fully configured: a provider without a client id or secret is never offered or accepted. */
    fun isEnabled(): Boolean = configManager.config.isEnabled(provider) &&
        clientId().isNotBlank() && clientSecret().isNotBlank()

    protected fun clientId(): String = configManager.config.clientId(provider)

    protected fun clientSecret(): String = configManager.config.clientSecret(provider)

    protected fun debugLog(msg: String) {
        if (configManager.config.debugLogging) {
            logger.info(msg)
        }
    }

    protected fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    protected fun buildQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    /**
     * Builds the provider authorization URL the user is redirected to.
     * [codeChallenge] is non-null only when [usesPkce] is true.
     */
    abstract fun buildAuthorizationUrl(redirectUri: String, state: String, codeChallenge: String?): String

    /**
     * Exchanges the returned authorization [code] for a normalized [SocialProfile].
     * [codeVerifier] is non-null only when [usesPkce] is true.
     */
    abstract suspend fun exchangeCodeForProfile(
        redirectUri: String,
        code: String,
        codeVerifier: String?
    ): SocialProfile
}
