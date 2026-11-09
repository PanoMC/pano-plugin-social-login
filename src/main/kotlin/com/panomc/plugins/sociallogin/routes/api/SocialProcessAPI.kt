package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.error.RateLimited
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import com.panomc.plugins.sociallogin.error.InvalidFlowState
import com.panomc.plugins.sociallogin.error.ProviderNotEnabled
import com.panomc.plugins.sociallogin.error.SocialLoginFailed
import com.panomc.plugins.sociallogin.error.SocialAccountAlreadyLinked
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import com.panomc.plugins.sociallogin.service.SocialLoginService
import com.panomc.plugins.sociallogin.service.SocialProfile
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
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
    override val paths = listOf(Path("/process", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Finishes a provider round trip: exchanges the code for a profile and signs in, links the account, or hands back a token for the register or account-exists step.",
        tag = "auth",
        response = CoreSchemas.sessionFields(
            objectSchema()
                .requiredProperty("action", enumSchema("login", "link", "complete-required", "account-exists", "register"))
                .requiredProperty("provider", stringSchema())
                .optionalProperty("pendingToken", stringSchema())
                .optionalProperty("email", stringSchema())
                .optionalProperty("suggestedUsername", stringSchema())
        ),
        errors = listOf(
            NotFound::class, NotLoggedIn::class, RateLimited::class, ProviderNotEnabled::class, InvalidFlowState::class,
            SocialLoginFailed::class, SocialAccountAlreadyLinked::class, LoginUserIsBanned::class
        )
    )

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
        socialLoginService.enforceAuthRateLimit(context)
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

        val statePayload = socialLoginService.consumeOAuthState(stateToken, context, sqlClient)
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
            throw SocialLoginFailed(e.message ?: "Social login failed.")
        }

        return when (intent) {
            "link" -> handleLink(context, provider, profile, statePayload.getLong("userId"), sqlClient)
            else -> handleAuth(context, provider, profile, sqlClient)
        }
    }

    private suspend fun handleLink(
        context: RoutingContext,
        provider: SocialProvider,
        profile: SocialProfile,
        stateUserId: Long?,
        sqlClient: SqlClient
    ): Result {
        if (!authProvider.isLoggedIn(context)) {
            throw NotLoggedIn()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        // A link only lands on the account that started it.
        if (stateUserId == null || stateUserId != userId) {
            throw InvalidFlowState()
        }

        val existingByAccount =
            socialLoginLinkDao.getByProviderAndProviderUserId(provider.key, profile.providerUserId, sqlClient)
        if (existingByAccount != null && existingByAccount.userId != userId) {
            throw SocialAccountAlreadyLinked()
        }

        // Replace any previous link for this provider on this account with the just-authorized one.
        socialLoginLinkDao.deleteByUserIdAndProvider(userId, provider.key, sqlClient)
        socialLoginService.addLinkUnique(
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

        // Linked account → run the core pre-login hooks before issuing a session.
        if (existing != null) {
            val user = databaseManager.userDao.getById(existing.userId, sqlClient) ?: throw NotFound()
            val deny = socialLoginService.runOnBeforeLogin(user, context, sqlClient)
            if (deny != null) {
                // ANY deny (2FA, future step-ups, …) escalates to the platform's pending-session
                // mechanism. social-login stays agnostic of what the challenge is: the theme's
                // login form + installed login-content plugins drive the UI, and
                // POST /api/v1/auth/complete-pending re-runs the same hooks with the user's input.
                val pendingToken = authProvider.createPendingSession(
                    user.id,
                    "social-login:${provider.key}",
                    sqlClient
                )
                return Successful(
                    mapOf(
                        "action" to "complete-required",
                        "pendingToken" to pendingToken,
                        "provider" to provider.key
                    )
                )
            }
            // A cookie session answers `csrfToken`; a front-end with a stored key gets `sessionToken` + `expiresAt`.
            val session = socialLoginService.loginUser(existing.userId, context, sqlClient)
            return Successful(mapOf("action" to "login", "provider" to provider.key) + session)
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
