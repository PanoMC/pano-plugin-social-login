package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.error.ProviderNotEnabled
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import com.panomc.plugins.sociallogin.service.PkceUtil
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Starts an account-linking OAuth flow for a logged-in user. The state carries the `link`
 * intent so the callback links the social account to the current user instead of logging in.
 */
@Endpoint
class SocialLinkInitAPI(
    private val plugin: SocialLoginPlugin
) : LoggedInApi() {
    override val paths = listOf(Path("/api/social-login/:provider/link", RouteType.GET))

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    private val socialLoginService by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginService::class.java)
    }

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("provider", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val providerKey = getParameters(context).pathParameter("provider").string
        val provider = SocialProvider.fromKey(providerKey) ?: throw NotFound()

        val oauthProvider = oauthRegistry.get(provider)
        if (!oauthProvider.isEnabled()) {
            throw ProviderNotEnabled()
        }

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val codeVerifier = if (oauthProvider.usesPkce) PkceUtil.generateVerifier() else null
        val codeChallenge = codeVerifier?.let { PkceUtil.challengeFor(it) }

        val state = socialLoginService.createOAuthState("link", provider, codeVerifier, userId, sqlClient)
        val redirectUri = socialLoginService.resolveRedirectUri(provider)
        val authUrl = oauthProvider.buildAuthorizationUrl(redirectUri, state, codeChallenge)

        return Successful(mapOf("redirectUrl" to authUrl))
    }
}
