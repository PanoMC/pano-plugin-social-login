package com.panomc.plugins.sociallogin.config

/**
 * Supported social login providers. [key] is the lowercase identifier used in URLs
 * (route path variable, redirect URI) and config; [displayName] is shown in the UI.
 */
enum class SocialProvider(val key: String, val displayName: String) {
    GOOGLE("google", "Google"),
    DISCORD("discord", "Discord"),
    FACEBOOK("facebook", "Facebook"),
    // Key stays "twitter" (stable identifier in URLs/config/DB); only the brand name is "X".
    TWITTER("twitter", "X"),
    GITHUB("github", "GitHub"),
    TWITCH("twitch", "Twitch"),
    INSTAGRAM("instagram", "Instagram");

    companion object {
        fun fromKey(key: String): SocialProvider? = entries.find { it.key == key.lowercase() }
    }
}
