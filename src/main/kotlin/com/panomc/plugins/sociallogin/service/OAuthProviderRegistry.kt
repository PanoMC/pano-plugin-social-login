package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx

/**
 * Holds one [OAuthProvider] instance per [SocialProvider]. Registered as a singleton bean in
 * the plugin bean context by [com.panomc.plugins.sociallogin.SocialLoginPlugin] so routes can
 * resolve a provider implementation without knowing the concrete class.
 */
class OAuthProviderRegistry(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) {
    private val providers: Map<SocialProvider, OAuthProvider> = mapOf(
        SocialProvider.GOOGLE to GoogleOAuthService(configManager, vertx),
        SocialProvider.DISCORD to DiscordOAuthService(configManager, vertx),
        SocialProvider.FACEBOOK to FacebookOAuthService(configManager, vertx),
        SocialProvider.TWITTER to TwitterOAuthService(configManager, vertx),
        SocialProvider.GITHUB to GitHubOAuthService(configManager, vertx),
        SocialProvider.TWITCH to TwitchOAuthService(configManager, vertx),
        SocialProvider.INSTAGRAM to InstagramOAuthService(configManager, vertx)
    )

    fun get(provider: SocialProvider): OAuthProvider = providers.getValue(provider)

    fun enabledProviders(): List<SocialProvider> =
        SocialProvider.entries.filter { providers.getValue(it).isEnabled() }

    /** Closes every provider's web client (plugin stop / disable). A later use creates a fresh one. */
    fun close() {
        providers.values.forEach { it.close() }
    }
}
