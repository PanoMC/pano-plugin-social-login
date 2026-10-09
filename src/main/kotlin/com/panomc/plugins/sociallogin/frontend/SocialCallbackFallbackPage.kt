package com.panomc.plugins.sociallogin.frontend

import com.panomc.platform.annotation.FallbackPageDefinition
import com.panomc.platform.frontend.FallbackPage
import com.panomc.platform.frontend.FallbackPageEnvironment
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import io.vertx.ext.web.RoutingContext

/**
 * Where the provider callback sends the browser (`provider`, `code` and `state` in the query, or `error` when the
 * visitor refused), for a site whose front-end has not taken the target `social-login.callback` over (doc 05
 * section 10.3). The page posts the three values to the plugin, which opens a cookie session on Pano's host (or
 * links the account when the visitor started from their settings).
 */
@FallbackPageDefinition
class SocialCallbackFallbackPage(
    private val plugin: SocialLoginPlugin
) : FallbackPage("callback", "fallback/callback.hbs") {
    private val environment by lazy { plugin.applicationContext.getBean(FallbackPageEnvironment::class.java) }

    private val urls by lazy { plugin.applicationContext.getBean(FrontendUrlMap::class.java) }

    override suspend fun model(context: RoutingContext): Map<String, Any?> {
        val request = context.request()
        val code = request.getParam("code") ?: ""
        val state = request.getParam("state") ?: ""

        return mapOf(
            "p" to FallbackTexts.of(javaClass.classLoader, "callback", environment.locale()),
            "provider" to (request.getParam("provider") ?: ""),
            "code" to code,
            "state" to state,
            "valid" to (request.getParam("error") == null && code.isNotBlank() && state.isNotBlank()),
            "endpoint" to "plugin:/process",
            "homeUrl" to environment.homeUrl(),
            "loginUrl" to runCatching { urls.url("auth.login") }.getOrNull()
        )
    }
}
