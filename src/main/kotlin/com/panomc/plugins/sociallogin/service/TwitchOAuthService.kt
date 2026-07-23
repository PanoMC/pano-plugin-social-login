package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait

class TwitchOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.TWITCH

    companion object {
        private const val AUTH_URL = "https://id.twitch.tv/oauth2/authorize"
        private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        private const val USER_URL = "https://api.twitch.tv/helix/users"
        private const val SCOPE = "user:read:email"
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
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to redirectUri
            )
        )
        val tokenResponse = webClient.postAbs(TOKEN_URL)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .putHeader("Accept", "application/json")
            .sendBuffer(Buffer.buffer(formBody))
            .coAwait()

        val tokenBody = tokenResponse.bodyAsJsonObject()
        if (tokenBody.getString("access_token") == null) {
            throw RuntimeException(
                "Twitch token exchange failed: ${tokenBody.getString("message", tokenBody.getString("error", "unknown error"))}"
            )
        }
        val accessToken = tokenBody.getString("access_token")

        debugLog("Fetching user info...")
        // Helix requires the app client id alongside the bearer token.
        val userResponse = webClient.getAbs(USER_URL)
            .putHeader("Authorization", "Bearer $accessToken")
            .putHeader("Client-Id", clientId())
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("Twitch user request failed: ${userResponse.statusCode()}")
        }

        val data = userResponse.bodyAsJsonObject().getJsonArray("data")
        if (data == null || data.isEmpty) {
            throw RuntimeException("Twitch user response had no data")
        }
        val user = data.getJsonObject(0)
        val id = user.getString("id")
            ?: throw RuntimeException("Twitch user response had no id")
        val login = user.getString("login")
        val displayName = user.getString("display_name")
        val email = user.getString("email")
        // Twitch only returns an email for accounts that have verified it.
        val emailVerified = email != null
        val avatarUrl = user.getString("profile_image_url")

        return SocialProfile(provider, id, email, emailVerified, displayName ?: login, avatarUrl)
    }
}
