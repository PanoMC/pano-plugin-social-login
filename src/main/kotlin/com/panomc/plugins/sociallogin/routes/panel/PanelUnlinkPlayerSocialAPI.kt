package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.schema.EndpointDoc
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.error.SocialAccountNotLinked
import com.panomc.plugins.sociallogin.log.UnlinkedSocialAccountLog
import com.panomc.plugins.sociallogin.permission.ManageSocialLinksPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: unlinks one of a player's social accounts.
 */
@Endpoint
class PanelUnlinkPlayerSocialAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : PanelApi() {
    override val paths = listOf(Path("/player/:username/unlink", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Removes one of a player's social account links.",
        tag = "links",
        response = objectSchema(),
        errors = listOf(NoPermission::class, NotFound::class, SocialAccountNotLinked::class)
    )

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", stringSchema().withKeyword("maxLength", 64)))
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("provider", enumSchema(*SocialProvider.entries.map { it.key }.toTypedArray()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageSocialLinksPermission(), context)

        val username = getParameters(context).pathParameter("username").string
        val body = context.body().asJsonObject()
        val provider = SocialProvider.fromKey(body.getString("provider")) ?: throw NotFound()

        val sqlClient = getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw NotFound()

        socialLoginLinkDao.getByUserIdAndProvider(userId, provider.key, sqlClient) ?: throw SocialAccountNotLinked()

        socialLoginLinkDao.deleteByUserIdAndProvider(userId, provider.key, sqlClient)

        val adminUserId = authProvider.getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UnlinkedSocialAccountLog(
                adminUserId,
                adminUsername,
                username,
                provider.key,
                plugin.pluginId
            ),
            sqlClient
        )

        return Successful()
    }
}
