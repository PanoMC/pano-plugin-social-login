package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.error.InvalidFlowState
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
 * Completes a social login that was paused for a second factor (e.g. 2FA). It holds only a
 * short-lived PENDING_2FA token — the provider and linked account were already verified in
 * [SocialProcessAPI] — and re-runs the core `onBeforeLogin` hooks with the submitted code. On
 * success it issues the session; a deny (wrong code) keeps the token so the user can retry.
 *
 * social-login knows nothing about "2FA" specifically: it just runs the platform's pre-login hooks
 * and any auth plugin (here pano-plugin-auth-guard) enforces its own factor.
 */
@Endpoint
class Social2faVerifyAPI(
    private val plugin: SocialLoginPlugin
) : Api() {
    override val paths = listOf(Path("/api/social-login/2fa", RouteType.POST))

    private val socialLoginService by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginService::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("pendingToken", stringSchema())
                        .requiredProperty("totpCode", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        socialLoginService.enforceAuthRateLimit(context)

        val body = context.body().asJsonObject()
        val pendingToken = body.getString("pendingToken")

        val sqlClient = getSqlClient()

        val userId = socialLoginService.getPending2faUserId(pendingToken, sqlClient) ?: throw InvalidFlowState()
        val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw InvalidFlowState()

        // Body carries the submitted factor (e.g. totpCode), which the auth plugin reads itself.
        val deny = socialLoginService.runOnBeforeLogin(user, context, sqlClient)
        if (deny != null) {
            return Successful(mapOf("error" to deny.errorKey))
        }

        socialLoginService.deletePending2fa(pendingToken, sqlClient)

        val csrfToken = socialLoginService.loginUser(userId, context, sqlClient)
        return Successful(mapOf("action" to "login", "csrfToken" to csrfToken))
    }
}
