package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Public config — only the list of enabled provider keys, so the theme knows which
 * login/register buttons to render. Never exposes client ids or secrets.
 */
@Endpoint
class GetSocialLoginConfigPublicAPI(
    private val plugin: SocialLoginPlugin
) : Api() {
    override val paths = listOf(Path("/config", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Lists the sign-in providers that are switched on.",
        tag = "config",
        response = objectSchema()
            .requiredProperty("items", arraySchema().items(stringSchema()))
    )

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        return Successful(
            mapOf(
                "items" to oauthRegistry.enabledProviders().map { it.key }
            )
        )
    }
}
