package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.BadRequest
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import java.net.URLEncoder

/**
 * OAuth redirect target. Providers send the browser here with `code`+`state` (or `error`);
 * this just forwards to the frontend callback page, which does the heavy exchange via
 * [SocialProcessAPI]. `code`/`state` are read raw because a denied consent omits them.
 */
@Endpoint
class SocialCallbackAPI(
    private val plugin: SocialLoginPlugin
) : Api() {
    override val paths = listOf(Path("/providers/:provider/callback", RouteType.GET))

    // The provider's browser arrives from a foreign origin. Only GET is registered: a provider set to
    // `response_mode=form_post` would POST here, which this route does not accept.
    override val browserAccess: BrowserAccess = BrowserAccess.ANY_ORIGIN

    override val doc = EndpointDoc(
        summary = "The address registered at the provider: receives the authorization code and redirects the browser to the callback page of the front-end.",
        tag = "auth",
        binary = true,
        errors = listOf(BadRequest::class)
    )

    private val frontendUrlMap by lazy {
        plugin.applicationContext.getBean(FrontendUrlMap::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(providerPathParam())
            // A denied consent sends `error` without `code` / `state`, so all three are optional here.
            .queryParameter(Parameters.optionalParam("code", cappedString(SocialLimits.CALLBACK_CODE)))
            .queryParameter(Parameters.optionalParam("state", cappedString(SocialLimits.CALLBACK_STATE)))
            .queryParameter(Parameters.optionalParam("error", cappedString(SocialLimits.CALLBACK_ERROR)))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val providerKey = getParameters(context).pathParameter("provider").string
        val request = context.request()
        val code = request.getParam("code")
        val state = request.getParam("state")
        val error = request.getParam("error")

        // A provider always sends `code` and `state`, or `error`. Anything else is not a callback, and it is not
        // forwarded to the front-end either (the redirect target is only ever a real provider answer).
        if (error == null && (code.isNullOrBlank() || state.isNullOrBlank())) throw BadRequest("Missing code or state")

        // Target `social-login.callback` of the front-end URL map (frontend-targets.json): the theme's page, the
        // front-end's own, or Pano's page at /_pano/social-login.callback until one takes it over.
        val params = linkedMapOf("provider" to providerKey)
        if (error != null) {
            params["error"] = error
        } else {
            params["code"] = code ?: ""
            params["state"] = state ?: ""
        }

        val redirectUrl = frontendUrlMap.url("social-login.callback", params) ?: buildString {
            append("/social-login/callback?provider=").append(enc(providerKey))
            if (error != null) {
                append("&error=").append(enc(error))
            } else {
                append("&code=").append(enc(code ?: ""))
                append("&state=").append(enc(state ?: ""))
            }
        }

        // The address carries the one-time code: never cached, never sent on as a Referer.
        context.response()
            .setStatusCode(302)
            .putHeader("Location", redirectUrl)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Referrer-Policy", "no-referrer")
            .end()

        return Successful()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
