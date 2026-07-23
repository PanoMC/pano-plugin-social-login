package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait

class FacebookOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.FACEBOOK

    companion object {
        private const val GRAPH_VERSION = "v19.0"
        private const val AUTH_URL = "https://www.facebook.com/$GRAPH_VERSION/dialog/oauth"
        private const val TOKEN_URL = "https://graph.facebook.com/$GRAPH_VERSION/oauth/access_token"
        private const val USER_URL = "https://graph.facebook.com/$GRAPH_VERSION/me"
        private const val SCOPE = "email,public_profile"
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
        // Facebook's token endpoint is a GET request.
        val tokenQuery = buildQuery(
            listOf(
                "client_id" to clientId(),
                "client_secret" to clientSecret(),
                "redirect_uri" to redirectUri,
                "code" to code
            )
        )
        val tokenResponse = webClient.getAbs("$TOKEN_URL?$tokenQuery")
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        val tokenBody = tokenResponse.bodyAsJsonObject()
        if (tokenBody.containsKey("error")) {
            val err = tokenBody.getJsonObject("error")
            throw RuntimeException("Facebook token exchange failed: ${err?.getString("message") ?: "unknown error"}")
        }
        val accessToken = tokenBody.getString("access_token")
            ?: throw RuntimeException("Facebook token exchange returned no access_token")

        debugLog("Fetching user info...")
        val userQuery = buildQuery(
            listOf(
                "fields" to "id,name,email,picture.type(large)",
                "access_token" to accessToken
            )
        )
        val userResponse = webClient.getAbs("$USER_URL?$userQuery")
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("Facebook user request failed: ${userResponse.statusCode()}")
        }

        val user = userResponse.bodyAsJsonObject()
        val id = user.getString("id")
            ?: throw RuntimeException("Facebook user response had no id")
        val name = user.getString("name")
        val email = user.getString("email")
        // Facebook only returns an email when it is confirmed on the account.
        val emailVerified = email != null
        val avatarUrl = user.getJsonObject("picture")
            ?.getJsonObject("data")
            ?.getString("url")

        return SocialProfile(provider, id, email, emailVerified, name, avatarUrl)
    }
}
