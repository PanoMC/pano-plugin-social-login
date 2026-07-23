package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Returns the logged-in user's linked social accounts plus the currently enabled providers,
 * so the theme profile/settings UI can render the link/unlink controls.
 */
@Endpoint
class GetMySocialLinksAPI(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : LoggedInApi() {
    override val paths = listOf(Path("/api/social-login/links", RouteType.GET))

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val links = socialLoginLinkDao.getByUserId(userId, sqlClient)

        return Successful(
            mapOf(
                "links" to links.map {
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
