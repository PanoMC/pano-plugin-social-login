package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.model.*
import com.panomc.plugins.license.LicenseGuard
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Returns the logged-in user's linked social accounts plus the currently enabled providers,
 * so the theme profile/settings UI can render the link/unlink controls.
 */
@Endpoint
class GetMySocialLinksAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : LoggedInApi() {
    override val paths = listOf(Path("/links", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Lists the signed-in user's linked social accounts and the providers that are switched on.",
        tag = "links",
        response = CoreSchemas.list(
            objectSchema()
                .requiredProperty("provider", stringSchema())
                .requiredProperty("displayName", stringSchema().nullable())
                .requiredProperty("email", stringSchema().nullable())
                .requiredProperty("avatarUrl", stringSchema().nullable())
                .requiredProperty("linkedAt", intSchema())
        ).requiredProperty("enabledProviders", arraySchema().items(stringSchema())),
        errors = listOf(NotLoggedIn::class)
    )

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        LicenseGuard.assert(plugin)

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

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
                },
                "enabledProviders" to oauthRegistry.enabledProviders().map { it.key }
            )
        )
    }
}
