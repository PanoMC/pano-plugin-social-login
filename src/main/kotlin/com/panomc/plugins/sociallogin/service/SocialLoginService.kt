package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.RateLimited
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.RateLimitManager
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.dao.SocialLoginStateDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import com.panomc.plugins.sociallogin.db.model.SocialLoginState
import com.panomc.plugins.sociallogin.error.SocialAccountAlreadyLinked
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

    private val socialLoginLinkDao by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginLinkDao::class.java)
    }

    private val rateLimitManager by lazy {
        plugin.applicationContext.getBean(RateLimitManager::class.java)
    }

    /**
     * Throttles sensitive social-login endpoints (credential check / registration / code
     * exchange) on the platform's strict AUTH bucket. The platform's global rate-limit handler
     * only assigns the lax PUBLIC_API tier to social-login paths, so we reuse the AUTH limiter
     * here to get the same brute-force protection as the platform's own login. Throws [RateLimited] (429).
     */
    fun enforceAuthRateLimit(context: RoutingContext) {
        val ip = rateLimitManager.getClientIp(context)
        if (!rateLimitManager.isAllowed(ip, RateLimitManager.Tier.AUTH)) {
            throw RateLimited(
                extras = mapOf("retryAfter" to rateLimitManager.retryAfter(ip, RateLimitManager.Tier.AUTH))
            )
        }
    }

    /**
     * Inserts a social link, translating the DB unique-key violation (a lost race against a
     * concurrent link of the same `(provider, providerUserId)`) into a friendly
     * [SocialAccountAlreadyLinked] instead of a raw 500.
     */
    suspend fun addLinkUnique(link: SocialLoginLink, sqlClient: SqlClient) {
        try {
            socialLoginLinkDao.add(link, sqlClient)
        } catch (e: Exception) {
            val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
            if (msg.contains("Duplicate", ignoreCase = true)) throw SocialAccountAlreadyLinked()
            throw e
        }
    }

    /**
     * The OAuth redirect URI for [provider]. Uses the configured `redirectBaseUrl`, falling back
     * to the platform `websiteUrl`. Must exactly match what is registered in the provider console.
     */
    fun resolveRedirectUri(provider: SocialProvider): String {
        val configuredBase = configManager.config.redirectBaseUrl.trim()
        val base = configuredBase.ifBlank { platformConfigManager.config.websiteUrl }.trimEnd('/')
        return base + ApiPaths.plugin(plugin.pluginId, "/providers/${provider.key}/callback")
    }

    // --- OAuth state: random, server-stored, one-time — CSRF protection for the round-trip ---

    suspend fun createOAuthState(
        intent: String,
        provider: SocialProvider,
        codeVerifier: String?,
        userId: Long?,
        context: RoutingContext,
        sqlClient: SqlClient
    ): String {
        val token = CSRFTokenGenerator.nextToken() + CSRFTokenGenerator.nextToken()
        val payload = JsonObject()
            .put("intent", intent)
            .put("provider", provider.key)
        if (codeVerifier != null) payload.put("codeVerifier", codeVerifier)
        if (userId != null) payload.put("userId", userId)
        // Bound to the browser that starts the round trip (see OAuthBrowserBinding).
        OAuthBrowserBinding.issue(context, OAUTH_STATE_TTL_MS)?.let { payload.put("browser", it) }

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

    /**
     * Looks up, deletes (one-time) and validates an OAuth state row. Returns its payload, or null when it is
     * unknown, used, expired, or this request is not from the browser that started the round trip.
     */
    suspend fun consumeOAuthState(token: String, context: RoutingContext, sqlClient: SqlClient): JsonObject? {
        val row = socialLoginStateDao.getByToken(token, sqlClient) ?: return null
        // Atomic single-use: only the caller whose DELETE actually removed the row proceeds.
        // A concurrent request for the same token loses the race and gets null.
        if (!socialLoginStateDao.deleteByToken(token, sqlClient)) return null
        if (row.kind != SocialLoginState.Kind.OAUTH_STATE.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        val payload = JsonObject(row.payload)
        if (!OAuthBrowserBinding.matches(context, payload.getString("browser"))) return null
        if (payload.getString("browser") != null) OAuthBrowserBinding.clear(context)
        return payload
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

        socialLoginStateDao.deleteExpired(sqlClient)
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
        if (!socialLoginStateDao.deleteByToken(token, sqlClient)) return null
        if (row.kind != SocialLoginState.Kind.PENDING_REGISTRATION.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        return JsonObject(row.payload)
    }

    // --- Step-up auth: delegate to the core AuthEventListener hooks ---

    /**
     * Runs the platform's [AuthEventListener.onBeforeLogin] hooks — the same ones the core LoginAPI
     * fires — to detect whether the session must pause for a step-up (2FA, …). Returns the first
     * Deny, or null to proceed. Callers that get a Deny should escalate to the platform's
     * pending-session mechanism (`AuthProvider.createPendingSession` + `/api/v1/auth/complete-pending`)
     * rather than handling the challenge themselves; social-login stays agnostic of the challenge.
     */
    suspend fun runOnBeforeLogin(
        user: User,
        context: RoutingContext,
        sqlClient: SqlClient
    ): AuthEventListener.LoginDecision.Deny? {
        return authProvider.runOnBeforeLogin(user, context, sqlClient) as? AuthEventListener.LoginDecision.Deny
    }

    // --- Session issuance ---

    /**
     * Issues a login session for [userId]: rejects banned users, opens the session through the host's
     * `issueSession` and updates last-login. A cookie session answers `csrfToken`; a request with a stored
     * front-end key gets `sessionToken` and `expiresAt` and no cookies. The caller merges the map into its answer.
     */
    suspend fun loginUser(userId: Long, context: RoutingContext, sqlClient: SqlClient): Map<String, Any?> {
        val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw NotFound()

        if (BanUtil.isBanned(user)) {
            throw LoginUserIsBanned(extras = mapOf("reason" to user.banMessage, "until" to user.bannedUntil))
        }

        val session = authProvider.issueSession(user.username, context, sqlClient)
        databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

        return session
    }
}
