package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import com.panomc.plugins.sociallogin.error.InvalidFlowState
import com.panomc.plugins.sociallogin.error.ProviderNotEnabled
import com.panomc.plugins.sociallogin.error.SocialAccountAlreadyLinked
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import com.panomc.plugins.sociallogin.service.SocialLoginService
import com.panomc.plugins.sociallogin.service.SocialProfile
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.SqlClient

/**
 * The core of the OAuth flow. Called by the frontend callback page with `{provider, code, state}`.
 * Exchanges the code for a profile, then routes by the state's intent:
 *  - `link`  → links the social account to the currently logged-in user.
 *  - `auth`  → if the account is linked, logs the user in; otherwise either prompts to log in
 *              (verified email matches an existing account) or sends them to the register form.
 */
@Endpoint
class SocialProcessAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : Api() {
    override val paths = listOf(Path("/api/social-login/process", RouteType.POST))

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    private val socialLoginService by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginService::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("provider", stringSchema())
                        .requiredProperty("code", stringSchema())
                        .requiredProperty("state", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val body = context.body().asJsonObject()
        val providerKey = body.getString("provider")
        val code = body.getString("code")
        val stateToken = body.getString("state")

        val provider = SocialProvider.fromKey(providerKey) ?: throw NotFound()
        val oauthProvider = oauthRegistry.get(provider)
        if (!oauthProvider.isEnabled()) {
            throw ProviderNotEnabled()
        }

        val sqlClient = getSqlClient()

        val statePayload = socialLoginService.consumeOAuthState(stateToken, sqlClient)
            ?: throw InvalidFlowState()

        // The state must have been issued for this same provider.
        if (statePayload.getString("provider") != provider.key) {
            throw InvalidFlowState()
        }

        val intent = statePayload.getString("intent")
        val codeVerifier = statePayload.getString("codeVerifier")

        val redirectUri = socialLoginService.resolveRedirectUri(provider)

        val profile = try {
            oauthProvider.exchangeCodeForProfile(redirectUri, code, codeVerifier)
        } catch (e: Exception) {
            plugin.logger.error("Social OAuth exchange failed for ${provider.key}: ${e.message}", e)
            return Successful(mapOf("error" to (e.message ?: "Social login failed.")))
        }

        return when (intent) {
            "link" -> handleLink(context, provider, profile, sqlClient)
            else -> handleAuth(context, provider, profile, sqlClient)
        }
    }

    private suspend fun handleLink(
        context: RoutingContext,
        provider: SocialProvider,
        profile: SocialProfile,
        sqlClient: SqlClient
    ): Result {
        if (!authProvider.isLoggedIn(context)) {
            throw NotLoggedIn()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val existingByAccount =
            socialLoginLinkDao.getByProviderAndProviderUserId(provider.key, profile.providerUserId, sqlClient)
        if (existingByAccount != null && existingByAccount.userId != userId) {
            throw SocialAccountAlreadyLinked()
        }

        // Replace any previous link for this provider on this account with the just-authorized one.
        socialLoginLinkDao.deleteByUserIdAndProvider(userId, provider.key, sqlClient)
        socialLoginLinkDao.add(
            SocialLoginLink(
                userId = userId,
                provider = provider.key,
                providerUserId = profile.providerUserId,
                email = profile.email,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl
            ),
            sqlClient
        )

        return Successful(mapOf("action" to "link", "provider" to provider.key))
    }

    private suspend fun handleAuth(
        context: RoutingContext,
        provider: SocialProvider,
        profile: SocialProfile,
        sqlClient: SqlClient
    ): Result {
        val existing =
            socialLoginLinkDao.getByProviderAndProviderUserId(provider.key, profile.providerUserId, sqlClient)

        // Linked account → straight login.
        if (existing != null) {
            val csrfToken = socialLoginService.loginUser(existing.userId, context, sqlClient)
            return Successful(
                mapOf("action" to "login", "csrfToken" to csrfToken, "provider" to provider.key)
            )
        }

        // Not linked. If the provider returned a *verified* email that already belongs to a Pano
        // account, do NOT auto-login — ask the user to authenticate so they can link it themselves.
        if (profile.emailVerified && !profile.email.isNullOrEmpty() &&
            databaseManager.userDao.isEmailExists(profile.email, sqlClient)
        ) {
            val pendingToken = socialLoginService.createPendingRegistration(profile, sqlClient)
            return Successful(
                mapOf(
                    "action" to "account-exists",
                    "provider" to provider.key,
                    "email" to TextUtil.maskEmail(profile.email),
                    "pendingToken" to pendingToken
                )
            )
        }

        // Otherwise hand the user a pending token and send them to the registration form.
        val pendingToken = socialLoginService.createPendingRegistration(profile, sqlClient)
        return Successful(
            mapOf(
                "action" to "register",
                "provider" to provider.key,
                "pendingToken" to pendingToken,
                "suggestedUsername" to suggestUsername(profile.displayName),
                "email" to (profile.email ?: "")
            )
        )
    }

    /** Best-effort username pre-fill from a provider display name; empty when unusable. */
    private fun suggestUsername(displayName: String?): String {
        if (displayName == null) return ""
        val cleaned = displayName.filter { it.isLetterOrDigit() || it == '_' }.take(16)
        return if (cleaned.length >= 3) cleaned else ""
    }
}
