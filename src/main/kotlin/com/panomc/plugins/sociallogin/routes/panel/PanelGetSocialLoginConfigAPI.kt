package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.permission.ManageSocialLoginSettingsPermission
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: returns the full plugin config for the settings page. Client secrets are
 * never returned — only a `hasSecret` flag — and the derived redirect URI is included so the
 * admin can copy it into each provider console.
 */
@Endpoint
class PanelGetSocialLoginConfigAPI(
    private val plugin: SocialLoginPlugin
) : PanelApi() {
    override val paths = listOf(Path("/config", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Returns the plugin settings: the redirect base URL, debug logging and every provider (never its secret).",
        tag = "config",
        response = CoreSchemas.list(PROVIDER_SCHEMA)
            .requiredProperty("providers", arraySchema().items(PROVIDER_SCHEMA))
            .requiredProperty("redirectBaseUrl", stringSchema())
            .requiredProperty("debugLogging", booleanSchema()),
        errors = listOf(NoPermission::class)
    )

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<SocialLoginConfig>
    }

    private val socialLoginService by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginService::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageSocialLoginSettingsPermission(), context)

        val config = configManager.config

        val providers = SocialProvider.entries.map { provider ->
            mapOf(
                "key" to provider.key,
                "displayName" to provider.displayName,
                "enabled" to config.isEnabled(provider),
                "clientId" to config.clientId(provider),
                "hasSecret" to config.clientSecret(provider).isNotEmpty(),
                "redirectUri" to socialLoginService.resolveRedirectUri(provider)
            )
        }

        // `items` is the list key the agent guide asks for; `providers` stays for older panel builds.
        return Successful(
            mapOf(
                "redirectBaseUrl" to config.redirectBaseUrl,
                "debugLogging" to config.debugLogging,
                "items" to providers,
                "providers" to providers
            )
        )
    }

    private companion object {
        val PROVIDER_SCHEMA = objectSchema()
            .requiredProperty("key", stringSchema())
            .requiredProperty("displayName", stringSchema())
            .requiredProperty("enabled", booleanSchema())
            .requiredProperty("clientId", stringSchema())
            .requiredProperty("hasSecret", booleanSchema())
            .requiredProperty("redirectUri", stringSchema())
    }
}
