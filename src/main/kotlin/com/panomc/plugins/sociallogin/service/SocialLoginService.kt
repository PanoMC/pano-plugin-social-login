package com.panomc.plugins.sociallogin.service

import com.panomc.platform.PluginEventManager
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.NotFound
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginStateDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginState
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient

/**
 * Shared backend logic for the social login flows: redirect-URI resolution, the short-lived
 * server-side flow state (CSRF protection + pending registration), and session issuance.
 * Registered as a singleton bean by [SocialLoginPlugin].
 */
class SocialLoginService(private val plugin: SocialLoginPlugin) {

    companion object {
        private const val OAUTH_STATE_TTL_MS = 10 * 60 * 1000L
        private const val PENDING_REGISTRATION_TTL_MS = 15 * 60 * 1000L
    }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<SocialLoginConfig>
    }

    private val platformConfigManager by lazy {
        plugin.applicationContext.getBean(ConfigManager::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val socialLoginStateDao by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginStateDao::class.java)
    }

    /**
     * The OAuth redirect URI for [provider]. Uses the configured `redirectBaseUrl`, falling back
     * to the platform `websiteUrl`. Must exactly match what is registered in the provider console.
     */
    fun resolveRedirectUri(provider: SocialProvider): String {
        val configuredBase = configManager.config.redirectBaseUrl.trim()
        val base = configuredBase.ifBlank { platformConfigManager.config.websiteUrl }.trimEnd('/')
        return "$base/api/social-login/${provider.key}/callback"
    }

    // --- OAuth state: random, server-stored, one-time — CSRF protection for the round-trip ---

    suspend fun createOAuthState(
        intent: String,
        provider: SocialProvider,
        codeVerifier: String?,
        userId: Long?,
        sqlClient: SqlClient
    ): String {
        val token = CSRFTokenGenerator.nextToken() + CSRFTokenGenerator.nextToken()
        val payload = JsonObject()
            .put("intent", intent)
            .put("provider", provider.key)
        if (codeVerifier != null) payload.put("codeVerifier", codeVerifier)
        if (userId != null) payload.put("userId", userId)

        socialLoginStateDao.deleteExpired(sqlClient)
        socialLoginStateDao.add(
            SocialLoginState(
                token = token,
                kind = SocialLoginState.Kind.OAUTH_STATE.name,
                payload = payload.encode(),
                expiresAt = System.currentTimeMillis() + OAUTH_STATE_TTL_MS
            ),
            sqlClient
        )
        return token
    }

    /** Looks up, deletes (one-time) and validates an OAuth state row. Returns its payload or null. */
    suspend fun consumeOAuthState(token: String, sqlClient: SqlClient): JsonObject? {
        val row = socialLoginStateDao.getByToken(token, sqlClient) ?: return null
        socialLoginStateDao.deleteByToken(token, sqlClient)
        if (row.kind != SocialLoginState.Kind.OAUTH_STATE.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        return JsonObject(row.payload)
    }

    // --- Pending registration: a verified social profile awaiting a username/password form ---

    suspend fun createPendingRegistration(profile: SocialProfile, sqlClient: SqlClient): String {
        val token = CSRFTokenGenerator.nextToken() + CSRFTokenGenerator.nextToken()
        val payload = JsonObject()
            .put("provider", profile.provider.key)
            .put("providerUserId", profile.providerUserId)
            .put("email", profile.email)
            .put("emailVerified", profile.emailVerified)
            .put("displayName", profile.displayName)
            .put("avatarUrl", profile.avatarUrl)

        socialLoginStateDao.add(
            SocialLoginState(
                token = token,
                kind = SocialLoginState.Kind.PENDING_REGISTRATION.name,
                payload = payload.encode(),
                expiresAt = System.currentTimeMillis() + PENDING_REGISTRATION_TTL_MS
            ),
            sqlClient
        )
        return token
    }

    suspend fun consumePendingRegistration(token: String, sqlClient: SqlClient): JsonObject? {
        val row = socialLoginStateDao.getByToken(token, sqlClient) ?: return null
        socialLoginStateDao.deleteByToken(token, sqlClient)
        if (row.kind != SocialLoginState.Kind.PENDING_REGISTRATION.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        return JsonObject(row.payload)
    }

    // --- Session issuance ---

    /**
     * Issues a login session for [userId]: rejects banned users, generates the auth token,
     * sets cookies, updates last-login and fires onAfterLogin hooks. Returns the CSRF token.
     */
    suspend fun loginUser(userId: Long, context: RoutingContext, sqlClient: SqlClient): String {
        val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw NotFound()

        if (BanUtil.isBanned(user)) {
            throw LoginUserIsBanned(extras = mapOf("reason" to user.banMessage, "until" to user.bannedUntil))
        }

        val authToken = authProvider.login(user.username, context, sqlClient)
        val csrfToken = CSRFTokenGenerator.nextToken()
        authProvider.setCookies(context, authToken, csrfToken)
        databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

        val listeners = PluginEventManager.getPanoEventListeners<AuthEventListener>()
        for (listener in listeners) {
            listener.onAfterLogin(user, context, sqlClient)
        }

        return csrfToken
    }
}
