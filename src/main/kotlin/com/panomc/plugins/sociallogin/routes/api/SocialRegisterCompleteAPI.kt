package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.IpIsBanned
import com.panomc.platform.error.PasswordEmpty
import com.panomc.platform.error.PasswordTooLong
import com.panomc.platform.error.PasswordTooShort
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.error.RateLimited
import com.panomc.platform.error.RegisterEmailEmpty
import com.panomc.platform.error.RegisterEmailNotAvailable
import com.panomc.platform.error.RegisterInvalidEmail
import com.panomc.platform.error.RegisterInvalidUsername
import com.panomc.platform.error.RegisterNotAcceptedAgreement
import com.panomc.platform.error.RegisterPasswordAndPasswordRepeatNotSame
import com.panomc.platform.error.RegisterUsernameEmpty
import com.panomc.platform.error.RegisterUsernameNotAvailable
import com.panomc.platform.error.RegisterUsernameTooLong
import com.panomc.platform.error.RegisterUsernameTooShort
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.*
import com.panomc.platform.util.RegisterUtil
import com.panomc.platform.util.TextUtil
import com.panomc.plugins.license.LicenseGuard
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import com.panomc.plugins.sociallogin.error.InvalidFlowState
import com.panomc.plugins.sociallogin.error.SocialAccountAlreadyLinked
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Completes a "register with X" flow: the user picked a username/password/email on the social
 * register form, and a pending token carries the verified social profile. Creates the Pano
 * account, links the social account, and logs the user in.
 */
@Endpoint
class SocialRegisterCompleteAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : Api() {
    override val paths = listOf(Path("/register", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Creates the account for a new social sign-in (user name, e-mail and password from the register form), links the provider account and signs in.",
        tag = "auth",
        response = CoreSchemas.sessionFields(objectSchema().requiredProperty("action", stringSchema())),
        errors = listOf(
            RateLimited::class, PluginDeniedLogin::class, InvalidFlowState::class, SocialAccountAlreadyLinked::class,
            IpIsBanned::class, RegisterUsernameEmpty::class, RegisterEmailEmpty::class, PasswordEmpty::class,
            RegisterUsernameTooShort::class, RegisterUsernameTooLong::class, PasswordTooShort::class, PasswordTooLong::class,
            RegisterInvalidUsername::class, RegisterInvalidEmail::class, RegisterPasswordAndPasswordRepeatNotSame::class,
            RegisterNotAcceptedAgreement::class, RegisterUsernameNotAvailable::class, RegisterEmailNotAvailable::class
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
                        .requiredProperty("pendingToken", stringSchema())
                        .requiredProperty("username", stringSchema())
                        .requiredProperty("email", stringSchema())
                        .requiredProperty("password", stringSchema())
                        .requiredProperty("passwordRepeat", stringSchema())
                        .requiredProperty("agreement", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        socialLoginService.enforceAuthRateLimit(context)
        LicenseGuard.assert(plugin)

        val body = context.body().asJsonObject()
        val pendingToken = body.getString("pendingToken")
        val username = TextUtil.stripWhitespace(body.getString("username") ?: "")
        val email = TextUtil.stripWhitespace(body.getString("email") ?: "")
        val password = body.getString("password")
        val passwordRepeat = body.getString("passwordRepeat")
        val agreement = body.getBoolean("agreement")

        val sqlClient = getSqlClient()

        // Run the core pre-auth hooks (e.g. captcha) before consuming the pending token or creating
        // the account, so a failed check leaves the flow retryable. We tell listeners this is a
        // "register" phase so they apply the right policy without sniffing the URL.
        context.put("pano-auth-phase", "register")
        (authProvider.runOnBeforeAuthenticate(context, sqlClient) as? AuthEventListener.LoginDecision.Deny)?.let {
            throw PluginDeniedLogin(it.errorKey, it.extras)
        }

        val payload = socialLoginService.consumePendingRegistration(pendingToken, sqlClient)
            ?: throw InvalidFlowState()

        val provider = SocialProvider.fromKey(payload.getString("provider")) ?: throw InvalidFlowState()
        val providerUserId = payload.getString("providerUserId")
        val socialEmail = payload.getString("email")
        val socialEmailVerified = payload.getBoolean("emailVerified", false)
        val displayName = payload.getString("displayName")
        val avatarUrl = payload.getString("avatarUrl")

        RegisterUtil.validateForm(username, email, password, passwordRepeat, agreement)

        // Guard against the social account having been linked between `process` and now.
        val alreadyLinked =
            socialLoginLinkDao.getByProviderAndProviderUserId(provider.key, providerUserId, sqlClient)
        if (alreadyLinked != null) {
            throw SocialAccountAlreadyLinked()
        }

        val remoteIP = authProvider.getRemoteIP(context)
        val userId = RegisterUtil.register(databaseManager, sqlClient, username, email, password, remoteIP)

        // Trust the email only when the provider verified it AND it is the one being registered.
        if (socialEmailVerified && socialEmail != null && socialEmail.equals(email, ignoreCase = true)) {
            databaseManager.userDao.makeEmailVerifiedById(userId, sqlClient)
        }

        socialLoginService.addLinkUnique(
            SocialLoginLink(
                userId = userId,
                provider = provider.key,
                providerUserId = providerUserId,
                email = socialEmail,
                displayName = displayName,
                avatarUrl = avatarUrl
            ),
            sqlClient
        )

        // A cookie session answers `csrfToken`; a front-end with a stored key gets `sessionToken` + `expiresAt`.
        val session = socialLoginService.loginUser(userId, context, sqlClient)

        return Successful(mapOf("action" to "login") + session)
    }
}
