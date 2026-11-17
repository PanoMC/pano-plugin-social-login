package com.panomc.plugins.sociallogin.routes.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.Error
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.log.UpdatedSocialLoginSettingsLog
import com.panomc.plugins.sociallogin.permission.ManageSocialLoginSettingsPermission
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.net.URI

/** `redirectBaseUrl` is set but is not an absolute http(s):// URL. */
class InvalidRedirectBaseUrl : Error("INVALID_REDIRECT_BASE_URL", 400)

/** A provider is enabled but its client ID or client secret (stored or sent) is blank; `details.provider` names it. */
class ProviderCredentialsRequired(provider: String) :
    Error("PROVIDER_CREDENTIALS_REQUIRED", 400, extras = mapOf("provider" to provider))

/**
 * Whether [value] is an absolute http(s) URL with a host and nothing but an optional path after it:
 * no user info, query or fragment, since the callback path is appended to it.
 */
internal fun isValidRedirectBaseUrl(value: String): Boolean {
    val uri = try {
        URI(value)
    } catch (e: Exception) {
        return false
    }

    val scheme = uri.scheme?.lowercase()

    return (scheme == "http" || scheme == "https") &&
        !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}

/**
 * Admin endpoint: persists the plugin config. The incoming body is merged onto the current
 * config so a field the UI omits (e.g. an unchanged client secret) is preserved.
 */
@Endpoint
class PanelUpdateSocialLoginConfigAPI(
    private val plugin: SocialLoginPlugin
) : PanelApi() {
    override val paths = listOf(Path("/config", RouteType.PUT))

    override val doc = EndpointDoc(
        summary = "Saves the plugin settings; omitted fields and blank secrets keep their stored values.",
        tag = "config",
        response = objectSchema(),
        errors = listOf(NoPermission::class, InvalidRedirectBaseUrl::class, ProviderCredentialsRequired::class)
    )

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
                        .optionalProperty("redirectBaseUrl", stringSchema().withKeyword("maxLength", 2048))
                        .optionalProperty("debugLogging", booleanSchema())
                        .optionalProperty("googleEnabled", booleanSchema())
                        .optionalProperty("googleClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("googleClientSecret", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("discordEnabled", booleanSchema())
                        .optionalProperty("discordClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("discordClientSecret", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("facebookEnabled", booleanSchema())
                        .optionalProperty("facebookClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("facebookClientSecret", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("twitterEnabled", booleanSchema())
                        .optionalProperty("twitterClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("twitterClientSecret", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("githubEnabled", booleanSchema())
                        .optionalProperty("githubClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("githubClientSecret", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("twitchEnabled", booleanSchema())
                        .optionalProperty("twitchClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("twitchClientSecret", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("instagramEnabled", booleanSchema())
                        .optionalProperty("instagramClientId", stringSchema().withKeyword("maxLength", 512))
                        .optionalProperty("instagramClientSecret", stringSchema().withKeyword("maxLength", 512))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageSocialLoginSettingsPermission(), context)

        val body = context.body().asJsonObject()
        val current = configManager.config

        // A blank or omitted secret means "unchanged" — never let an empty value wipe a stored
        // secret (the UI only sends a secret when the admin actually typed a new one).
        fun secret(key: String, currentValue: String) =
            body.getString(key)?.takeIf { it.isNotBlank() } ?: currentValue

        // Blank = fall back to the platform websiteUrl; otherwise an absolute http(s) origin,
        // stored without a trailing slash (the callback path is appended to it).
        val redirectBaseUrl = body.getString("redirectBaseUrl", current.redirectBaseUrl).trim().trimEnd('/')

        if (redirectBaseUrl.isNotEmpty() && !isValidRedirectBaseUrl(redirectBaseUrl)) {
            throw InvalidRedirectBaseUrl()
        }

        // Merge: every field falls back to its current value when the body omits it.
        val merged = JsonObject()
            .put("version", current.version)
            .put("redirectBaseUrl", redirectBaseUrl)
            .put("debugLogging", body.getBoolean("debugLogging", current.debugLogging))
            .put("googleEnabled", body.getBoolean("googleEnabled", current.googleEnabled))
            .put("googleClientId", body.getString("googleClientId", current.googleClientId))
            .put("googleClientSecret", secret("googleClientSecret", current.googleClientSecret))
            .put("discordEnabled", body.getBoolean("discordEnabled", current.discordEnabled))
            .put("discordClientId", body.getString("discordClientId", current.discordClientId))
            .put("discordClientSecret", secret("discordClientSecret", current.discordClientSecret))
            .put("facebookEnabled", body.getBoolean("facebookEnabled", current.facebookEnabled))
            .put("facebookClientId", body.getString("facebookClientId", current.facebookClientId))
            .put("facebookClientSecret", secret("facebookClientSecret", current.facebookClientSecret))
            .put("twitterEnabled", body.getBoolean("twitterEnabled", current.twitterEnabled))
            .put("twitterClientId", body.getString("twitterClientId", current.twitterClientId))
            .put("twitterClientSecret", secret("twitterClientSecret", current.twitterClientSecret))
            .put("githubEnabled", body.getBoolean("githubEnabled", current.githubEnabled))
            .put("githubClientId", body.getString("githubClientId", current.githubClientId))
            .put("githubClientSecret", secret("githubClientSecret", current.githubClientSecret))
            .put("twitchEnabled", body.getBoolean("twitchEnabled", current.twitchEnabled))
            .put("twitchClientId", body.getString("twitchClientId", current.twitchClientId))
            .put("twitchClientSecret", secret("twitchClientSecret", current.twitchClientSecret))
            .put("instagramEnabled", body.getBoolean("instagramEnabled", current.instagramEnabled))
            .put("instagramClientId", body.getString("instagramClientId", current.instagramClientId))
            .put("instagramClientSecret", secret("instagramClientSecret", current.instagramClientSecret))

        // An enabled provider needs both credentials, or its sign-in button would only fail.
        SocialProvider.entries.forEach { provider ->
            if (merged.getBoolean("${provider.key}Enabled") &&
                (merged.getString("${provider.key}ClientId").isNullOrBlank() ||
                    merged.getString("${provider.key}ClientSecret").isNullOrBlank())
            ) {
                throw ProviderCredentialsRequired(provider.key)
            }
        }

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
