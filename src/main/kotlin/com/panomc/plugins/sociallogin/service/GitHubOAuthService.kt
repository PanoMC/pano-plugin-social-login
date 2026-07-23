package com.panomc.plugins.sociallogin.service

import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.config.SocialProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait

class GitHubOAuthService(
    configManager: PluginConfigManager<SocialLoginConfig>,
    vertx: Vertx
) : OAuthProvider(configManager, vertx) {
    override val provider = SocialProvider.GITHUB

    companion object {
        private const val AUTH_URL = "https://github.com/login/oauth/authorize"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_URL = "https://api.github.com/user"
        private const val EMAILS_URL = "https://api.github.com/user/emails"
        private const val SCOPE = "read:user user:email"

        // GitHub's API rejects requests without a User-Agent header.
        private const val USER_AGENT = "pano-plugin-social-login"
    }

    override fun buildAuthorizationUrl(redirectUri: String, state: String, codeChallenge: String?): String {
        val query = buildQuery(
            listOf(
                "client_id" to clientId(),
                "redirect_uri" to redirectUri,
                "scope" to SCOPE,
                "state" to state,
                "allow_signup" to "true"
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
                "redirect_uri" to redirectUri
            )
        )
        val tokenResponse = webClient.postAbs(TOKEN_URL)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .putHeader("Accept", "application/json")
            .putHeader("User-Agent", USER_AGENT)
            .sendBuffer(Buffer.buffer(formBody))
            .coAwait()

        val tokenBody = tokenResponse.bodyAsJsonObject()
        if (tokenBody.containsKey("error")) {
            throw RuntimeException(
                "GitHub token exchange failed: ${tokenBody.getString("error_description", tokenBody.getString("error"))}"
            )
        }
        val accessToken = tokenBody.getString("access_token")
            ?: throw RuntimeException("GitHub token exchange returned no access_token")

        debugLog("Fetching user info...")
        val userResponse = webClient.getAbs(USER_URL)
            .putHeader("Authorization", "Bearer $accessToken")
            .putHeader("Accept", "application/json")
            .putHeader("User-Agent", USER_AGENT)
            .send()
            .coAwait()

        if (userResponse.statusCode() != 200) {
            throw RuntimeException("GitHub user request failed: ${userResponse.statusCode()}")
        }

        val user = userResponse.bodyAsJsonObject()
        val id = user.getValue("id")?.toString()
            ?: throw RuntimeException("GitHub user response had no id")
        val login = user.getString("login")
        val name = user.getString("name")
        val avatarUrl = user.getString("avatar_url")

        // The /user endpoint omits the email when the account keeps it private —
        // fall back to the verified primary address from /user/emails.
        var email = user.getString("email")
        var emailVerified = false
        val emailsResponse = webClient.getAbs(EMAILS_URL)
            .putHeader("Authorization", "Bearer $accessToken")
            .putHeader("Accept", "application/json")
            .putHeader("User-Agent", USER_AGENT)
            .send()
            .coAwait()
        if (emailsResponse.statusCode() == 200) {
            val emails = emailsResponse.bodyAsJsonArray()
            val primary = (0 until emails.size())
                .map { emails.getJsonObject(it) }
                .firstOrNull { it.getBoolean("primary", false) && it.getBoolean("verified", false) }
            if (primary != null) {
                email = primary.getString("email")
                emailVerified = true
            }
        }

        return SocialProfile(provider, id, email, emailVerified, name ?: login, avatarUrl)
    }
}
