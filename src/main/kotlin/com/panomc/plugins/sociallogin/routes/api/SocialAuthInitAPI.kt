package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.license.LicenseGuard
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
 * Starts a login-or-register OAuth flow for a provider. Returns the provider authorization URL
 * the frontend redirects the browser to.
 */
@Endpoint
class SocialAuthInitAPI(
    private val plugin: SocialLoginPlugin
) : Api() {
    override val paths = listOf(Path("/api/social-login/:provider/auth", RouteType.GET))

    private val oauthRegistry by lazy {
        plugin.pluginBeanContext.getBean(OAuthProviderRegistry::class.java)
    }

    private val socialLoginService by lazy {
        plugin.pluginBeanContext.getBean(SocialLoginService::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("provider", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        LicenseGuard.assert(plugin)

        val providerKey = getParameters(context).pathParameter("provider").string
        val provider = SocialProvider.fromKey(providerKey) ?: throw NotFound()

        val oauthProvider = oauthRegistry.get(provider)
        if (!oauthProvider.isEnabled()) {
            throw ProviderNotEnabled()
        }

        val sqlClient = getSqlClient()

        val codeVerifier = if (oauthProvider.usesPkce) PkceUtil.generateVerifier() else null
        val codeChallenge = codeVerifier?.let { PkceUtil.challengeFor(it) }

        val state = socialLoginService.createOAuthState("auth", provider, codeVerifier, null, sqlClient)
        val redirectUri = socialLoginService.resolveRedirectUri(provider)
        val authUrl = oauthProvider.buildAuthorizationUrl(redirectUri, state, codeChallenge)

        return Successful(mapOf("redirectUrl" to authUrl))
    }
}
