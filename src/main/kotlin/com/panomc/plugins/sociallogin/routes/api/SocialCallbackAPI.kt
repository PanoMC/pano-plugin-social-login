package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
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
    override val paths = listOf(Path("/api/social-login/:provider/callback", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("provider", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val providerKey = getParameters(context).pathParameter("provider").string
        val request = context.request()
        val code = request.getParam("code")
        val state = request.getParam("state")
        val error = request.getParam("error")

        val redirectUrl = buildString {
            append("/social-login/callback?provider=").append(enc(providerKey))
            if (error != null) {
                append("&error=").append(enc(error))
            } else {
                append("&code=").append(enc(code ?: ""))
                append("&state=").append(enc(state ?: ""))
            }
        }

        context.response()
            .setStatusCode(302)
            .putHeader("Location", redirectUrl)
            .end()

        return Successful()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
