package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.error.SocialAccountNotLinked
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Unlinks the logged-in user's account for a given provider. Social-registered users always
 * have a password (collected on the register form), so unlinking can never lock them out.
 */
@Endpoint
class SocialUnlinkAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : LoggedInApi() {
    override val paths = listOf(Path("/api/social-login/unlink", RouteType.POST))

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("provider", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val body = context.body().asJsonObject()
        val provider = SocialProvider.fromKey(body.getString("provider")) ?: throw NotFound()

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        socialLoginLinkDao.getByUserIdAndProvider(userId, provider.key, sqlClient)
            ?: throw SocialAccountNotLinked()

        socialLoginLinkDao.deleteByUserIdAndProvider(userId, provider.key, sqlClient)

        return Successful()
    }
}
