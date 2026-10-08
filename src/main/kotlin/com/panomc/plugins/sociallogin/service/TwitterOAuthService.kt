package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait
import java.util.Base64

class TwitterOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.TWITTER

    /** X (formerly Twitter) OAuth 2.0 mandates PKCE even for confidential clients. */
    override val usesPkce = true

    companion object {
        private const val AUTH_URL = "https://x.com/i/oauth2/authorize"
        private const val TOKEN_URL = "https://api.x.com/2/oauth2/token"
        private const val USER_URL = "https://api.x.com/2/users/me?user.fields=profile_image_url"
        private const val SCOPE = "users.read tweet.read"
    }

    override fun buildAuthorizationUrl(redirectUri: String, state: String, codeChallenge: String?): String {
        val query = buildQuery(
            listOf(
                "response_type" to "code",
                "client_id" to clientId(),
                "redirect_uri" to redirectUri,
                "scope" to SCOPE,
                "state" to state,
                "code_challenge" to (codeChallenge ?: ""),
                "code_challenge_method" to "S256"
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
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to (codeVerifier ?: ""),
                "client_id" to clientId()
            )
        )
        // Confidential client: authenticate the token request with HTTP Basic.
        val basicAuth = Base64.getEncoder()
            .encodeToString("${clientId()}:${clientSecret()}".toByteArray(Charsets.UTF_8))

        val tokenResponse = webClient.postAbs(TOKEN_URL)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .putHeader("Accept", "application/json")
            .putHeader("Authorization", "Basic $basicAuth")
            .sendBuffer(Buffer.buffer(formBody))
            .coAwait()

        val tokenBody = tokenResponse.bodyAsJsonObject()
        if (tokenBody.containsKey("error")) {
            throw RuntimeException(
                "X token exchange failed: ${tokenBody.getString("error_description", tokenBody.getString("error"))}"
            )
        }
        val accessToken = tokenBody.getString("access_token")
            ?: throw RuntimeException("X token exchange returned no access_token")

        debugLog("Fetching user info...")
        val userResponse = webClient.getAbs(USER_URL)
            .putHeader("Authorization", "Bearer $accessToken")
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("X user request failed: ${userResponse.statusCode()}")
        }

        val data = userResponse.bodyAsJsonObject().getJsonObject("data")
            ?: throw RuntimeException("X user response had no data")
        val id = data.getString("id")
            ?: throw RuntimeException("X user response had no id")
        val name = data.getString("name")
        val avatarUrl = data.getString("profile_image_url")

        // X (formerly Twitter) OAuth 2.0 does not expose the account email.
        return SocialProfile(provider, id, null, false, name, avatarUrl)
    }
}
