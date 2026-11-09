package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.schema.EndpointDoc
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
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Starts a login-or-register OAuth flow for a provider. Returns the provider authorization URL
 * the frontend redirects the browser to.
 */
@Endpoint
class SocialAuthInitAPI(
    private val plugin: SocialLoginPlugin
) : Api() {
    override val paths = listOf(Path("/providers/:provider/auth", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Starts a sign-in or registration with a provider and answers the address of the provider's consent page.",
        tag = "auth",
        response = objectSchema().requiredProperty("redirectUrl", stringSchema()),
        errors = listOf(NotFound::class, ProviderNotEnabled::class)
    )

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
        val providerKey = getParameters(context).pathParameter("provider").string
        val provider = SocialProvider.fromKey(providerKey) ?: throw NotFound()

        val oauthProvider = oauthRegistry.get(provider)
        if (!oauthProvider.isEnabled()) {
            throw ProviderNotEnabled()
        }

        val sqlClient = getSqlClient()

        val codeVerifier = if (oauthProvider.usesPkce) PkceUtil.generateVerifier() else null
        val codeChallenge = codeVerifier?.let { PkceUtil.challengeFor(it) }

        val state = socialLoginService.createOAuthState("auth", provider, codeVerifier, null, context, sqlClient)
        val redirectUri = socialLoginService.resolveRedirectUri(provider)
        val authUrl = oauthProvider.buildAuthorizationUrl(redirectUri, state, codeChallenge)

        return Successful(mapOf("redirectUrl" to authUrl))
    }
}
