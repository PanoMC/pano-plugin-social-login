package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.IpIsBanned
import com.panomc.platform.error.LoginEmailNotVerified
import com.panomc.platform.error.LoginIsInvalid
import com.panomc.platform.error.RegisterEmailRequired
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.error.RateLimited
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import com.panomc.plugins.sociallogin.error.EmailMismatch
import com.panomc.plugins.sociallogin.error.InvalidFlowState
import com.panomc.plugins.sociallogin.error.SocialAccountAlreadyLinked
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Handles the "an account with this email already exists" path: the user authenticates with
 * their existing Pano credentials, the verified social email is checked against that account,
 * the social account is linked, and the user is logged in.
 */
@Endpoint
class SocialLinkExistingAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : Api() {
    override val paths = listOf(Path("/link-existing", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Links a provider account to an existing account after the user proved it with the account's own credentials, then signs in (or asks for the step-up the sign-in needs).",
        tag = "auth",
        response = CoreSchemas.sessionFields(
            objectSchema()
                .requiredProperty("action", enumSchema("login", "complete-required"))
                .optionalProperty("pendingToken", stringSchema())
                .optionalProperty("provider", stringSchema())
        ),
        errors = listOf(
            RateLimited::class, PluginDeniedLogin::class, InvalidFlowState::class, LoginIsInvalid::class,
            EmailMismatch::class, SocialAccountAlreadyLinked::class, LoginUserIsBanned::class, IpIsBanned::class,
            LoginEmailNotVerified::class, RegisterEmailRequired::class
        )
    )

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
                        .requiredProperty("pendingToken", cappedString(SocialLimits.TOKEN))
                        .requiredProperty("usernameOrEmail", cappedString(SocialLimits.USERNAME_OR_EMAIL))
                        .requiredProperty("password", cappedString(SocialLimits.PASSWORD))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        socialLoginService.enforceAuthRateLimit(context)
        val body = context.body().asJsonObject()
        val pendingToken = body.getString("pendingToken")
        val usernameOrEmail = body.getString("usernameOrEmail")
        val password = body.getString("password")

        val sqlClient = getSqlClient()

        // Run the core pre-auth hooks (e.g. captcha) before consuming the pending token or checking
        // the password — this is a password "login" phase.
        context.put("pano-auth-phase", "login")
        (authProvider.runOnBeforeAuthenticate(context, sqlClient) as? AuthEventListener.LoginDecision.Deny)?.let {
            throw PluginDeniedLogin(it.errorKey, it.extras)
        }

        // Only read here: a wrong password must not use up the flow. It is consumed once the link is made.
        val payload = socialLoginService.peekPendingRegistration(pendingToken, sqlClient)
            ?: throw InvalidFlowState()

        // A token handed out for the register form is not a link token (tokens without an intent stay usable).
        if (payload.getString("intent") == SocialLoginService.PENDING_INTENT_REGISTER) {
            throw InvalidFlowState()
        }

        val provider = SocialProvider.fromKey(payload.getString("provider")) ?: throw InvalidFlowState()
        val providerUserId = payload.getString("providerUserId")
        val socialEmail = payload.getString("email")
        val socialEmailVerified = payload.getBoolean("emailVerified", false)
        val displayName = payload.getString("displayName")
        val avatarUrl = payload.getString("avatarUrl")

        // This route is only reachable for verified-email matches; re-assert that invariant.
        if (!socialEmailVerified || socialEmail.isNullOrEmpty()) {
            throw InvalidFlowState()
        }

        // Validate the user's existing Pano credentials.
        authProvider.validateInput(usernameOrEmail, password)
        authProvider.authenticate(usernameOrEmail, password, sqlClient = sqlClient)

        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)
            ?: throw LoginIsInvalid()

        // The authenticated account must be the one that owns the verified social email.
        val userEmail = databaseManager.userDao.getEmailFromUserId(userId, sqlClient)
        if (userEmail == null || !userEmail.equals(socialEmail, ignoreCase = true)) {
            throw EmailMismatch()
        }

        // A link whose user was deleted while the plugin was off is dropped here and counts as no link.
        val existingByAccount = socialLoginService.getLiveLink(provider, providerUserId, sqlClient)
        if (existingByAccount != null && existingByAccount.userId != userId) {
            throw SocialAccountAlreadyLinked()
        }

        // Token use, old link removal and the new link together: a lost race leaves the token and the old link.
        // A lock conflict between two concurrent links is run once more; a duplicate that is left is a lost race.
        val link = SocialLoginLink(
            userId = userId,
            provider = provider.key,
            providerUserId = providerUserId,
            email = socialEmail,
            displayName = displayName,
            avatarUrl = avatarUrl
        )
        linkDuplicateAsAlreadyLinked {
            retryOnLockConflict {
                socialLoginService.inTransaction(sqlClient) { tx ->
                    socialLoginService.consumePendingRegistration(pendingToken, tx) ?: throw InvalidFlowState()
                    replaceLink(socialLoginLinkDao, socialLoginService, link, tx)
                }
            }
        }

        // Step-up auth hooks: 2FA must still gate the session even though the password matched.
        // Symmetric to SocialProcessAPI — any Deny escalates to the platform's pending-session flow
        // so social-login stays agnostic of the challenge. The link is already persisted (the user
        // proved password ownership); the user can complete or abandon — abandoning leaves them
        // linked but unauthenticated, which they can resolve via a normal /login attempt.
        val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw LoginIsInvalid()
        val decision = socialLoginService.runOnBeforeLogin(user, context, sqlClient)
        if (decision != null) {
            val pendingToken = authProvider.createPendingSession(
                userId,
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
        val session = socialLoginService.loginUser(userId, context, sqlClient)

        return Successful(mapOf("action" to "login") + session)
    }
}
