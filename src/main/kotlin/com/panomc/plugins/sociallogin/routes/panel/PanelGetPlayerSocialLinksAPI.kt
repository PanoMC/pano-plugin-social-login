package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.permission.ManageSocialLinksPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: lists a player's linked social accounts for the player detail page.
 */
@Endpoint
class PanelGetPlayerSocialLinksAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : PanelApi() {
    override val paths = listOf(Path("/player/:username/links", RouteType.GET))

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageSocialLinksPermission(), context)

        val username = getParameters(context).pathParameter("username").string
        val sqlClient = getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw NotFound()

        val links = socialLoginLinkDao.getByUserId(userId, sqlClient)

        return Successful(
            mapOf(
                "items" to links.map {
                    mapOf(
                        "provider" to it.provider,
                        "displayName" to it.displayName,
                        "email" to it.email,
                        "avatarUrl" to it.avatarUrl,
                        "linkedAt" to it.linkedAt
                    )
                }
            )
        )
    }
}
