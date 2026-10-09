package com.panomc.plugins.sociallogin.config

import com.panomc.platform.api.config.PluginConfig

/**
 * Plugin configuration. Kept flat (one field per provider setting) to match the HOCON/Gson
 * mapping used by Pano's [PluginConfig] — see pano-plugin-premium-login's config for the pattern.
 *
 * [redirectBaseUrl] is the public origin the OAuth callback is reachable at
 * (e.g. https://example.com). When empty, the platform `websiteUrl` is used as a fallback.
 * Per-provider redirect URI is then `{base}/api/plugins/pano-plugin-social-login/providers/{provider}/callback`.
 */
class SocialLoginConfig(
    val redirectBaseUrl: String = "",
    val debugLogging: Boolean = false,

    val googleEnabled: Boolean = false,
    val googleClientId: String = "",
    val googleClientSecret: String = "",

    val discordEnabled: Boolean = false,
    val discordClientId: String = "",
    val discordClientSecret: String = "",

    val facebookEnabled: Boolean = false,
    val facebookClientId: String = "",
    val facebookClientSecret: String = "",

    val twitterEnabled: Boolean = false,
    val twitterClientId: String = "",
    val twitterClientSecret: String = "",

    val githubEnabled: Boolean = false,
    val githubClientId: String = "",
    val githubClientSecret: String = "",

    val twitchEnabled: Boolean = false,
    val twitchClientId: String = "",
    val twitchClientSecret: String = "",

    val instagramEnabled: Boolean = false,
    val instagramClientId: String = "",
    val instagramClientSecret: String = "",

    version: Int = 1
) : PluginConfig(version) {
    fun isEnabled(provider: SocialProvider): Boolean = when (provider) {
        SocialProvider.GOOGLE -> googleEnabled
        SocialProvider.DISCORD -> discordEnabled
        SocialProvider.FACEBOOK -> facebookEnabled
        SocialProvider.TWITTER -> twitterEnabled
        SocialProvider.GITHUB -> githubEnabled
        SocialProvider.TWITCH -> twitchEnabled
        SocialProvider.INSTAGRAM -> instagramEnabled
    }

    fun clientId(provider: SocialProvider): String = when (provider) {
        SocialProvider.GOOGLE -> googleClientId
        SocialProvider.DISCORD -> discordClientId
        SocialProvider.FACEBOOK -> facebookClientId
        SocialProvider.TWITTER -> twitterClientId
        SocialProvider.GITHUB -> githubClientId
        SocialProvider.TWITCH -> twitchClientId
        SocialProvider.INSTAGRAM -> instagramClientId
    }

    fun clientSecret(provider: SocialProvider): String = when (provider) {
        SocialProvider.GOOGLE -> googleClientSecret
        SocialProvider.DISCORD -> discordClientSecret
        SocialProvider.FACEBOOK -> facebookClientSecret
        SocialProvider.TWITTER -> twitterClientSecret
        SocialProvider.GITHUB -> githubClientSecret
        SocialProvider.TWITCH -> twitchClientSecret
        SocialProvider.INSTAGRAM -> instagramClientSecret
    }
}
