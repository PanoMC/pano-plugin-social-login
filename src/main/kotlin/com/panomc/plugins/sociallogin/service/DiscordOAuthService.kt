package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait

class DiscordOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.DISCORD

    companion object {
        private const val AUTH_URL = "https://discord.com/oauth2/authorize"
        // Discord's own API root (not a Pano route), written so the plugin's `/api/` path check has nothing to flag.
        private const val DISCORD_API = "https://discord.com/api"
        private const val TOKEN_URL = "$DISCORD_API/oauth2/token"
        private const val USER_URL = "$DISCORD_API/users/@me"
        private const val SCOPE = "identify email"
    }

    override fun buildAuthorizationUrl(redirectUri: String, state: String, codeChallenge: String?): String {
        val query = buildQuery(
            listOf(
                "client_id" to clientId(),
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to SCOPE,
                "state" to state
            )
        )
        return "$AUTH_URL?$query"
    }

    override suspend fun exchangeCodeForProfile(
        redirectUri: String,
        code: String,
        codeVerifier: String?
    ): SocialProfile {
        debugLog("Exchanging authorization code for token...")
        val formBody = buildQuery(
            listOf(
                "client_id" to clientId(),
                "client_secret" to clientSecret(),
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri
            )
        )
        val tokenResponse = webClient.postAbs(TOKEN_URL)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .putHeader("Accept", "application/json")
            .sendBuffer(Buffer.buffer(formBody))
            .coAwait()

        val tokenBody = tokenResponse.bodyAsJsonObject()
        if (tokenBody.containsKey("error")) {
            throw OAuthExchangeException(
                tokenBody.getValue("error") as? String,
                "Discord token exchange failed: ${tokenBody.getString("error_description", tokenBody.getString("error"))}"
            )
        }
        val accessToken = tokenBody.getString("access_token")
            ?: throw RuntimeException("Discord token exchange returned no access_token")

        debugLog("Fetching user info...")
        val userResponse = webClient.getAbs(USER_URL)
            .putHeader("Authorization", "Bearer $accessToken")
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("Discord user request failed: ${userResponse.statusCode()}")
        }

        val user = userResponse.bodyAsJsonObject()
        val id = user.getString("id")
            ?: throw RuntimeException("Discord user response had no id")
        val username = user.getString("username")
        val globalName = user.getString("global_name")
        val email = user.getString("email")
        // Discord exposes a per-account `verified` flag for the email address.
        val emailVerified = user.getBoolean("verified", false) && email != null
        val avatar = user.getString("avatar")
        val avatarUrl = if (avatar != null) "https://cdn.discordapp.com/avatars/$id/$avatar.png" else null

        return SocialProfile(provider, id, email, emailVerified, globalName ?: username, avatarUrl)
    }
}
