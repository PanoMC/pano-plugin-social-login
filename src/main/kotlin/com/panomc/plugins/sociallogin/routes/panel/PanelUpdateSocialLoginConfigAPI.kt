package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.log.UpdatedSocialLoginSettingsLog
import com.panomc.plugins.sociallogin.permission.ManageSocialLoginSettingsPermission
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: persists the plugin config. The incoming body is merged onto the current
 * config so a field the UI omits (e.g. an unchanged client secret) is preserved.
 */
@Endpoint
class PanelUpdateSocialLoginConfigAPI(
    private val plugin: SocialLoginPlugin
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/social-login/config", RouteType.PUT))

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<SocialLoginConfig>
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .optionalProperty("redirectBaseUrl", stringSchema())
                        .optionalProperty("debugLogging", booleanSchema())
                        .optionalProperty("googleEnabled", booleanSchema())
                        .optionalProperty("googleClientId", stringSchema())
                        .optionalProperty("googleClientSecret", stringSchema())
                        .optionalProperty("discordEnabled", booleanSchema())
                        .optionalProperty("discordClientId", stringSchema())
                        .optionalProperty("discordClientSecret", stringSchema())
                        .optionalProperty("facebookEnabled", booleanSchema())
                        .optionalProperty("facebookClientId", stringSchema())
                        .optionalProperty("facebookClientSecret", stringSchema())
                        .optionalProperty("twitterEnabled", booleanSchema())
                        .optionalProperty("twitterClientId", stringSchema())
                        .optionalProperty("twitterClientSecret", stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageSocialLoginSettingsPermission(), context)

        val body = context.body().asJsonObject()
        val current = configManager.config

        // Merge: every field falls back to its current value when the body omits it.
        val merged = JsonObject()
            .put("redirectBaseUrl", body.getString("redirectBaseUrl", current.redirectBaseUrl))
            .put("debugLogging", body.getBoolean("debugLogging", current.debugLogging))
            .put("googleEnabled", body.getBoolean("googleEnabled", current.googleEnabled))
            .put("googleClientId", body.getString("googleClientId", current.googleClientId))
            .put("googleClientSecret", body.getString("googleClientSecret", current.googleClientSecret))
            .put("discordEnabled", body.getBoolean("discordEnabled", current.discordEnabled))
            .put("discordClientId", body.getString("discordClientId", current.discordClientId))
            .put("discordClientSecret", body.getString("discordClientSecret", current.discordClientSecret))
            .put("facebookEnabled", body.getBoolean("facebookEnabled", current.facebookEnabled))
            .put("facebookClientId", body.getString("facebookClientId", current.facebookClientId))
            .put("facebookClientSecret", body.getString("facebookClientSecret", current.facebookClientSecret))
            .put("twitterEnabled", body.getBoolean("twitterEnabled", current.twitterEnabled))
            .put("twitterClientId", body.getString("twitterClientId", current.twitterClientId))
            .put("twitterClientSecret", body.getString("twitterClientSecret", current.twitterClientSecret))

        configManager.saveConfig(merged)

        val sqlClient = getSqlClient()
        val adminUserId = authProvider.getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedSocialLoginSettingsLog(
                adminUserId,
                adminUsername,
                plugin.pluginId
            ),
            sqlClient
        )

        return Successful()
    }
}
