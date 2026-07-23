package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.LoginIsInvalid
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
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
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
    override val paths = listOf(Path("/api/social-login/link-existing", RouteType.POST))

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
                        .requiredProperty("usernameOrEmail", stringSchema())
                        .requiredProperty("password", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val body = context.body().asJsonObject()
        val pendingToken = body.getString("pendingToken")
        val usernameOrEmail = body.getString("usernameOrEmail")
        val password = body.getString("password")

        val sqlClient = getSqlClient()

        val payload = socialLoginService.consumePendingRegistration(pendingToken, sqlClient)
            ?: throw InvalidFlowState()

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

        val existingByAccount =
            socialLoginLinkDao.getByProviderAndProviderUserId(provider.key, providerUserId, sqlClient)
        if (existingByAccount != null && existingByAccount.userId != userId) {
            throw SocialAccountAlreadyLinked()
        }

        socialLoginLinkDao.deleteByUserIdAndProvider(userId, provider.key, sqlClient)
        socialLoginLinkDao.add(
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

        val csrfToken = socialLoginService.loginUser(userId, context, sqlClient)

        return Successful(mapOf("action" to "login", "csrfToken" to csrfToken))
    }
}
