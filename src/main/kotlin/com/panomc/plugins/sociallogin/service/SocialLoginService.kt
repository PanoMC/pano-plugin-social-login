package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.platform.error.IpIsBanned
import com.panomc.platform.error.LoginEmailNotVerified
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.util.TextUtil
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
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared backend logic for the social login flows: redirect-URI resolution, the short-lived
 * server-side flow state (CSRF protection + pending registration), and session issuance.
 * Registered as a singleton bean by [SocialLoginPlugin].
 */
class SocialLoginService(private val plugin: SocialLoginPlugin) {

    companion object {
        private const val OAUTH_STATE_TTL_MS = 10 * 60 * 1000L
        private const val PENDING_REGISTRATION_TTL_MS = 15 * 60 * 1000L

        const val PENDING_INTENT_REGISTER = "register"
        const val PENDING_INTENT_LINK = "link"

        /** The intent of a pending payload; tokens made before intents existed count as register tokens. */
        fun pendingIntent(payload: JsonObject): String = payload.getString("intent") ?: PENDING_INTENT_REGISTER
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
     * The link of `(provider, providerUserId)` whose user still exists. A link left behind by a user deleted while
     * the plugin was off is removed here and treated as no link, so the provider account can be used again.
     */
    suspend fun getLiveLink(provider: SocialProvider, providerUserId: String, sqlClient: SqlClient): SocialLoginLink? {
        val link = socialLoginLinkDao.getByProviderAndProviderUserId(provider.key, providerUserId, sqlClient)
            ?: return null
        if (databaseManager.userDao.getById(link.userId, sqlClient) != null) return link

        socialLoginLinkDao.deleteByUserIdAndProvider(link.userId, provider.key, sqlClient)
        return null
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

    /** A new OAuth state: its [token], and the binding secret only a stored-key caller gets (null for a browser). */
    data class CreatedOAuthState(val token: String, val bindingSecret: String?)

    /** The platform's trusted proxies (`server.trusted-proxies`); empty when the config is not readable. */
    private fun trustedProxies(): List<String> = try {
        platformConfigManager.config.server.trustedProxies
    } catch (_: Throwable) {
        emptyList()
    }

    suspend fun createOAuthState(
        intent: String,
        provider: SocialProvider,
        codeVerifier: String?,
        userId: Long?,
        context: RoutingContext,
        sqlClient: SqlClient
    ): CreatedOAuthState {
        val token = CSRFTokenGenerator.nextToken() + CSRFTokenGenerator.nextToken()
        val payload = JsonObject()
            .put("intent", intent)
            .put("provider", provider.key)
        if (codeVerifier != null) payload.put("codeVerifier", codeVerifier)
        if (userId != null) payload.put("userId", userId)
        // Bound to the caller that starts the round trip (see OAuthBrowserBinding).
        val binding = OAuthBrowserBinding.issue(context, token, OAUTH_STATE_TTL_MS, trustedProxies())
        binding.browserHash?.let { payload.put("browser", it) }
        binding.secretHash?.let { payload.put("bindingSecretHash", it) }

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
        return CreatedOAuthState(token, binding.bindingSecret)
    }

    /**
     * Looks up, validates and deletes (one-time) an OAuth state row. Returns its payload, or null when it is
     * unknown, used, expired, or this request is not from the caller that started the round trip
     * ([bindingSecret] is the secret a stored-key caller got from the init answer). The row is deleted only
     * after the checks, so someone who merely saw the state cannot burn it.
     */
    suspend fun consumeOAuthState(
        token: String,
        bindingSecret: String?,
        context: RoutingContext,
        sqlClient: SqlClient
    ): JsonObject? {
        val row = socialLoginStateDao.getByToken(token, sqlClient) ?: return null
        if (row.kind != SocialLoginState.Kind.OAUTH_STATE.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        val payload = JsonObject(row.payload)
        val browserHash = payload.getString("browser")
        if (!OAuthBrowserBinding.matches(context, token, browserHash, payload.getString("bindingSecretHash"), bindingSecret)) {
            return null
        }
        // Atomic single-use: only the caller whose DELETE actually removed the row proceeds.
        // A concurrent request for the same token loses the race and gets null.
        if (!socialLoginStateDao.deleteByToken(token, sqlClient)) return null
        if (browserHash != null) OAuthBrowserBinding.clear(context, token, trustedProxies())
        return payload
    }

    // --- Pending registration: a verified social profile awaiting a username/password form ---

    /**
     * [intent] says what the token may be used for: [PENDING_INTENT_REGISTER] (the register form) or
     * [PENDING_INTENT_LINK] (proving an existing account with its password).
     */
    suspend fun createPendingRegistration(profile: SocialProfile, intent: String, sqlClient: SqlClient): String {
        val token = CSRFTokenGenerator.nextToken() + CSRFTokenGenerator.nextToken()
        val payload = JsonObject()
            .put("intent", intent)
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

    /** Reads a pending registration without using it up, so a form with a mistake can be sent again. */
    suspend fun peekPendingRegistration(token: String, sqlClient: SqlClient): JsonObject? {
        val row = socialLoginStateDao.getByToken(token, sqlClient) ?: return null
        if (row.kind != SocialLoginState.Kind.PENDING_REGISTRATION.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        return JsonObject(row.payload)
    }

    suspend fun consumePendingRegistration(token: String, sqlClient: SqlClient): JsonObject? {
        val row = socialLoginStateDao.getByToken(token, sqlClient) ?: return null
        if (!socialLoginStateDao.deleteByToken(token, sqlClient)) return null
        if (row.kind != SocialLoginState.Kind.PENDING_REGISTRATION.name) return null
        if (row.expiresAt < System.currentTimeMillis()) return null
        return JsonObject(row.payload)
    }

    /**
     * Runs [block] in one database transaction when [sqlClient] is a pool (rolled back if it throws), so the
     * steps inside either all happen or none do. Any other client runs the block as is.
     */
    suspend fun <T> inTransaction(sqlClient: SqlClient, block: suspend (SqlClient) -> T): T {
        val pool = sqlClient as? Pool ?: return block(sqlClient)
        val connection = pool.connection.coAwait()

        try {
            val transaction = connection.begin().coAwait()

            try {
                val result = block(connection)
                transaction.commit().coAwait()
                return result
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { transaction.rollback().coAwait() } }
                throw e
            }
        } finally {
            withContext(NonCancellable) { runCatching { connection.close().coAwait() } }
        }
    }

    // --- Step-up auth: delegate to the core AuthEventListener hooks ---

    /**
     * Runs the platform's [AuthEventListener.onBeforeLogin] hooks — the same ones the core LoginAPI
     * fires — to detect whether the session must pause for a step-up (2FA, …). Returns the first
     * decision that is not Allow (Deny, RequireUsername, …), or null to proceed. Callers that get one
     * escalate to the platform's pending-session mechanism (`AuthProvider.createPendingSession` +
     * `/api/v1/auth/complete-pending`), which re-runs the same hooks and answers each decision the way
     * the core login does; social-login stays agnostic of the challenge.
     */
    suspend fun runOnBeforeLogin(
        user: User,
        context: RoutingContext,
        sqlClient: SqlClient
    ): AuthEventListener.LoginDecision? {
        checkCanSignIn(user, context, sqlClient)

        return authProvider.runOnBeforeLogin(user, context, sqlClient)
            ?.takeIf { it !is AuthEventListener.LoginDecision.Allow }
    }

    // --- Session issuance ---

    /**
     * The rules of the password sign-in that do not depend on the password: no banned user, no banned address,
     * and a confirmed e-mail when the site requires one. Runs before the step-up hooks and before a session.
     */
    suspend fun checkCanSignIn(user: User, context: RoutingContext, sqlClient: SqlClient) {
        if (BanUtil.isBanned(user)) {
            throw LoginUserIsBanned(extras = mapOf("reason" to user.banMessage, "until" to user.bannedUntil))
        }

        val activeIpBan = databaseManager.bannedIpDao.getActiveByIp(authProvider.getRemoteIP(context), sqlClient)
        if (activeIpBan != null) {
            throw IpIsBanned(extras = mapOf("reason" to activeIpBan.reason, "bannedUntil" to activeIpBan.bannedUntil))
        }

        if (platformConfigManager.config.auth.requireEmailVerification && !user.emailVerified) {
            throw LoginEmailNotVerified(extras = mapOf("email" to (user.email?.let { TextUtil.maskEmail(it) } ?: "")))
        }
    }

    /**
     * Issues a login session for [userId]: rejects banned users, opens the session through the host's
     * `issueSession` and updates last-login. A cookie session answers `csrfToken`; a request with a stored
     * front-end key gets `sessionToken` and `expiresAt` and no cookies. The caller merges the map into its answer.
     */
    suspend fun loginUser(userId: Long, context: RoutingContext, sqlClient: SqlClient): Map<String, Any?> {
        val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw NotFound()

        checkCanSignIn(user, context, sqlClient)

        val session = authProvider.issueSession(user.username, context, sqlClient)
        databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

        // The same after-login hooks as the core login.
        val updatedUser = databaseManager.userDao.getById(userId, sqlClient) ?: user
        authProvider.runOnAfterLogin(updatedUser, context, sqlClient)

        return session
    }
}
