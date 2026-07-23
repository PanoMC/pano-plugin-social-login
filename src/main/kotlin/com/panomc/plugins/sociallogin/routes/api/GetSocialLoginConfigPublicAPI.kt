package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Public config — only the list of enabled provider keys, so the theme knows which
 * login/register buttons to render. Never exposes client ids or secrets.
 */
@Endpoint
class GetSocialLoginConfigPublicAPI(
    private val plugin: SocialLoginPlugin
) : Api() {
    override val paths = listOf(Path("/api/social-login/config", RouteType.GET))

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        return Successful(
            mapOf(
                "enabledProviders" to oauthRegistry.enabledProviders().map { it.key }
            )
        )
    }
}
