package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait

class InstagramOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.INSTAGRAM

    companion object {
        private const val AUTH_URL = "https://www.instagram.com/oauth/authorize"
        private const val TOKEN_URL = "https://api.instagram.com/oauth/access_token"
        private const val USER_URL = "https://graph.instagram.com/v21.0/me"
        private const val SCOPE = "instagram_business_basic"
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
        // Instagram appends a "#_" fragment to the code on web redirects — strip it before use.
        val cleanCode = code.removeSuffix("#_")
        val formBody = buildQuery(
            listOf(
                "client_id" to clientId(),
                "client_secret" to clientSecret(),
                "grant_type" to "authorization_code",
                "redirect_uri" to redirectUri,
                "code" to cleanCode
            )
        )
        val tokenResponse = webClient.postAbs(TOKEN_URL)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .putHeader("Accept", "application/json")
            .sendBuffer(Buffer.buffer(formBody))
            .coAwait()

        val tokenBody = tokenResponse.bodyAsJsonObject()
        if (tokenBody.containsKey("error_type") || tokenBody.containsKey("error")) {
            throw RuntimeException(
                "Instagram token exchange failed: ${tokenBody.getString("error_message", tokenBody.getString("error_description", "unknown error"))}"
            )
        }
        val accessToken = tokenBody.getString("access_token")
            ?: throw RuntimeException("Instagram token exchange returned no access_token")

        debugLog("Fetching user info...")
        val userQuery = buildQuery(
            listOf(
                "fields" to "id,username",
                "access_token" to accessToken
            )
        )
        val userResponse = webClient.getAbs("$USER_URL?$userQuery")
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("Instagram user request failed: ${userResponse.statusCode()}")
        }

        val user = userResponse.bodyAsJsonObject()
        val id = user.getString("id")
            ?: tokenBody.getValue("user_id")?.toString()
            ?: throw RuntimeException("Instagram user response had no id")
        val username = user.getString("username")

        // Instagram's OAuth never exposes an email address.
        return SocialProfile(provider, id, null, false, username, null)
    }
}
