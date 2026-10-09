package com.panomc.plugins.sociallogin.routes

import com.panomc.platform.model.Api
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.db.impl.SocialLoginLinkDaoImpl
import com.panomc.plugins.sociallogin.routes.api.GetMySocialLinksAPI
import com.panomc.plugins.sociallogin.routes.api.GetSocialLoginConfigPublicAPI
import com.panomc.plugins.sociallogin.routes.api.SocialAuthInitAPI
import com.panomc.plugins.sociallogin.routes.api.SocialCallbackAPI
import com.panomc.plugins.sociallogin.routes.api.SocialLinkExistingAPI
import com.panomc.plugins.sociallogin.routes.api.SocialLinkInitAPI
import com.panomc.plugins.sociallogin.routes.api.SocialProcessAPI
import com.panomc.plugins.sociallogin.routes.api.SocialRegisterCompleteAPI
import com.panomc.plugins.sociallogin.routes.api.SocialUnlinkAPI
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The public endpoints describe their answers (doc 04 section 5), and the sign-in answers match the host's session shape. */
class EndpointDocsTest {
    private val plugin = SocialLoginPlugin()
    private val dao = SocialLoginLinkDaoImpl()

    private val publicEndpoints: List<Api> = listOf(
        GetMySocialLinksAPI(plugin, dao),
        GetSocialLoginConfigPublicAPI(plugin),
        SocialAuthInitAPI(plugin),
        SocialCallbackAPI(plugin),
        SocialLinkExistingAPI(plugin, dao),
        SocialLinkInitAPI(plugin),
        SocialProcessAPI(plugin, dao),
        SocialRegisterCompleteAPI(plugin, dao),
        SocialUnlinkAPI(plugin, dao)
    )

    @Test
    fun `every public endpoint has a usable doc`() {
        publicEndpoints.forEach { api ->
            val doc = api.doc

            assertNotNull(doc, "${api.javaClass.simpleName} has no doc")
            assertNull(doc!!.problem(), api.javaClass.simpleName)
        }
    }

    @Test
    fun `the process answer carries the action and a cookie session or a site session`() {
        val doc = SocialProcessAPI(plugin, dao).doc!!

        assertEquals(emptyList<String>(), doc.check(JsonObject().put("action", "login").put("provider", "google").put("csrfToken", "c")))
        assertEquals(
            emptyList<String>(),
            doc.check(JsonObject().put("action", "login").put("provider", "google").put("sessionToken", "eyJ").put("expiresAt", 1791400000000L))
        )
        assertEquals(emptyList<String>(), doc.check(JsonObject().put("action", "link").put("provider", "discord")))
        assertEquals(emptyList<String>(), doc.check(JsonObject().put("action", "register").put("provider", "discord").put("pendingToken", "t").put("suggestedUsername", "").put("email", "")))
        assertTrue(doc.check(JsonObject().put("csrfToken", "c")).isNotEmpty())
        assertTrue(doc.check(JsonObject().put("error", JsonObject().put("code", "X"))).isNotEmpty())
    }

    @Test
    fun `a list answers items and the links answer is checked against that`() {
        val doc = GetMySocialLinksAPI(plugin, dao).doc!!
        val link = JsonObject().put("provider", "github").put("displayName", "Steve").put("email", null as String?).put("avatarUrl", null as String?).put("linkedAt", 1L)

        assertEquals(
            emptyList<String>(),
            doc.check(JsonObject().put("items", io.vertx.core.json.JsonArray().add(link)).put("enabledProviders", io.vertx.core.json.JsonArray().add("github")))
        )
        assertTrue(doc.check(JsonObject().put("links", io.vertx.core.json.JsonArray())).isNotEmpty())
    }

    @Test
    fun `the register and link-existing answers match the session shape`() {
        assertEquals(emptyList<String>(), SocialRegisterCompleteAPI(plugin, dao).doc!!.check(JsonObject().put("action", "login").put("csrfToken", "c")))
        assertEquals(
            emptyList<String>(),
            SocialLinkExistingAPI(plugin, dao).doc!!.check(JsonObject().put("action", "complete-required").put("pendingToken", "t").put("provider", "google"))
        )
    }
}
