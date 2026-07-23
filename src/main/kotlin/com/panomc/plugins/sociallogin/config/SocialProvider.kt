package com.panomc.plugins.sociallogin.config

/**
 * Supported social login providers. [key] is the lowercase identifier used in URLs
 * (route path variable, redirect URI) and config; [displayName] is shown in the UI.
 */
enum class SocialProvider(val key: String, val displayName: String) {
    GOOGLE("google", "Google"),
    DISCORD("discord", "Discord"),
    FACEBOOK("facebook", "Facebook"),
    TWITTER("twitter", "Twitter");

    companion object {
        fun fromKey(key: String): SocialProvider? = entries.find { it.key == key.lowercase() }
    }
}
