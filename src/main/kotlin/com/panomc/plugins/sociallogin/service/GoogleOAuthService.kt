package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait

class GoogleOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.GOOGLE

    companion object {
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        private const val SCOPE = "openid email profile"
    }

    override fun buildAuthorizationUrl(redirectUri: String, state: String, codeChallenge: String?): String {
        val query = buildQuery(
            listOf(
                "client_id" to clientId(),
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to SCOPE,
                "state" to state,
                "access_type" to "online",
                "prompt" to "select_account"
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
        if (tokenBody.containsKey("error")) {
            throw OAuthExchangeException(
                tokenBody.getValue("error") as? String,
                "Google token exchange failed: ${tokenBody.getString("error_description", tokenBody.getString("error"))}"
            )
        }
        val accessToken = tokenBody.getString("access_token")
            ?: throw RuntimeException("Google token exchange returned no access_token")

        debugLog("Fetching user info...")
        val userResponse = webClient.getAbs(USERINFO_URL)
            .putHeader("Authorization", "Bearer $accessToken")
            .putHeader("Accept", "application/json")
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("Google userinfo request failed: ${userResponse.statusCode()}")
        }

        val user = userResponse.bodyAsJsonObject()
        val sub = user.getString("sub")
            ?: throw RuntimeException("Google userinfo returned no subject")
        val email = user.getString("email")
        val emailVerified = user.getBoolean("email_verified", false) && email != null
        val name = user.getString("name")
        val picture = user.getString("picture")

        return SocialProfile(provider, sub, email, emailVerified, name, picture)
    }
}
