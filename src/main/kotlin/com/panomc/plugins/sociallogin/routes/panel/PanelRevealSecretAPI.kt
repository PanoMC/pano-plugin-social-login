package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.error.InvalidPassword
import com.panomc.plugins.sociallogin.permission.ManageSocialLoginSettingsPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: re-authenticates the admin with their password and returns the stored client
 * secret for a single provider, so the settings UI can reveal it. The normal config GET never
 * returns secrets — this password-gated route is the only way to read one back.
 */
@Endpoint
class PanelRevealSecretAPI(
    private val plugin: SocialLoginPlugin
) : PanelApi() {
    override val paths = listOf(Path("/config/reveal-secret", RouteType.POST))

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
                        .requiredProperty("provider", stringSchema())
                        .requiredProperty("password", stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageSocialLoginSettingsPermission(), context)

        val body = context.body().asJsonObject()
        val provider = SocialProvider.fromKey(body.getString("provider")) ?: throw NotFound()
        val password = body.getString("password")

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        if (!databaseManager.userDao.isLoginCorrect(username, password, sqlClient)) {
            throw InvalidPassword()
        }

        return Successful(mapOf("clientSecret" to configManager.config.clientSecret(provider)))
    }
}
