package com.panomc.plugins.sociallogin.frontend

import com.panomc.platform.api.ExternalUrl
import com.panomc.platform.api.ExternalUrlProvider
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.config.ConfigManager
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.platform.route.ApiPaths
import org.springframework.stereotype.Component

/**
 * The address the admin has registered at each provider (the redirect URL of the OAuth app). It moved with the
 * API v1 cutover and the old one answers nothing, so the compatibility page of the panel lists one per enabled
 * provider (doc 04 section 7, decision 46: no alias, the admin changes it at the provider).
 */
@Component
class SocialExternalUrls(
    private val plugin: SocialLoginPlugin
) : ExternalUrlProvider {
    override fun urls(): List<ExternalUrl> {
        // The config manager is created when the setup is done; before that nothing is registered anywhere.
        val config = runCatching {
            @Suppress("UNCHECKED_CAST")
            (plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<SocialLoginConfig>).config
        }.getOrNull() ?: return emptyList()

        return urlsOf(config, plugin.pluginId) { plugin.applicationContext.getBean(ConfigManager::class.java).config.websiteUrl }
    }

    internal companion object {
        fun label(provider: SocialProvider) = "${provider.displayName} redirect URL"

        /** [websiteUrl] is only asked for when the config names no `redirectBaseUrl`. */
        fun urlsOf(config: SocialLoginConfig, pluginId: String, websiteUrl: () -> String): List<ExternalUrl> {
            val enabled = SocialProvider.entries.filter { config.isEnabled(it) }

            if (enabled.isEmpty()) {
                return emptyList()
            }

            // What the plugin sends as `redirect_uri` is what the provider compares with (SocialLoginService.resolveRedirectUri).
            val base = config.redirectBaseUrl.trim().ifBlank(websiteUrl).trimEnd('/')

            return enabled.map { ExternalUrl(label(it), base + ApiPaths.plugin(pluginId, "/providers/${it.key}/callback")) }
        }
    }
}
