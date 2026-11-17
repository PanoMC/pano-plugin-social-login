package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.permission.ManageSocialLinksPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
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

    override val doc = EndpointDoc(
        summary = "Lists a player's linked social accounts for the player detail page.",
        tag = "links",
        response = CoreSchemas.list(
            objectSchema()
                .requiredProperty("provider", stringSchema())
                .requiredProperty("displayName", stringSchema().nullable())
                .requiredProperty("email", stringSchema().nullable())
                .requiredProperty("linkedAt", intSchema())
        ),
        errors = listOf(NoPermission::class, NotFound::class)
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
                        "linkedAt" to it.linkedAt
                    )
                }
            )
        )
    }
}
