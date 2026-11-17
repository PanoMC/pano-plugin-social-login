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
import com.panomc.plugins.sociallogin.routes.panel.PanelGetPlayerSocialLinksAPI
import com.panomc.plugins.sociallogin.routes.panel.PanelGetSocialLoginConfigAPI
import com.panomc.plugins.sociallogin.routes.panel.PanelRevealSecretAPI
import com.panomc.plugins.sociallogin.routes.panel.PanelUnlinkPlayerSocialAPI
import com.panomc.plugins.sociallogin.routes.panel.PanelUpdateSocialLoginConfigAPI
import com.panomc.plugins.sociallogin.routes.panel.isValidRedirectBaseUrl
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private val panelEndpoints: List<Api> = listOf(
        PanelGetPlayerSocialLinksAPI(plugin, dao),
        PanelGetSocialLoginConfigAPI(plugin),
        PanelRevealSecretAPI(plugin),
        PanelUnlinkPlayerSocialAPI(plugin, dao),
        PanelUpdateSocialLoginConfigAPI(plugin)
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
    fun `every panel endpoint has a usable doc that declares its errors`() {
        panelEndpoints.forEach { api ->
            val doc = api.doc

            assertNotNull(doc, "${api.javaClass.simpleName} has no doc")
            assertNull(doc!!.problem(), api.javaClass.simpleName)
            assertTrue(doc.errors.isNotEmpty(), "${api.javaClass.simpleName} declares no errors")
        }
    }

    @Test
    fun `the panel config answers its providers under items and keeps providers`() {
        val doc = PanelGetSocialLoginConfigAPI(plugin).doc!!
        val provider = JsonObject().put("key", "google").put("displayName", "Google").put("enabled", true)
            .put("clientId", "id").put("hasSecret", true).put("redirectUri", "https://a.com/cb")
        val answer = JsonObject().put("redirectBaseUrl", "").put("debugLogging", false)
            .put("items", JsonArray().add(provider)).put("providers", JsonArray().add(provider))

        assertEquals(emptyList<String>(), doc.check(answer))
        assertTrue(doc.check(answer.copy().apply { remove("items") }).isNotEmpty())
    }

    @Test
    fun `the panel player links answer has no avatar url`() {
        val doc = PanelGetPlayerSocialLinksAPI(plugin, dao).doc!!
        val link = JsonObject().put("provider", "github").put("displayName", "Steve").put("email", "s@a.com").put("linkedAt", 1L)

        assertEquals(emptyList<String>(), doc.check(JsonObject().put("items", JsonArray().add(link))))
    }

    @Test
    fun `the redirect base url is an absolute http url without query, fragment or user info`() {
        listOf("https://a.com", "http://a.com:8080", "https://a.com/pano").forEach {
            assertTrue(isValidRedirectBaseUrl(it), it)
        }

        listOf("https://a.com?x", "https://a.com/#a", "https://u:p@a.com", "ftp://a.com", "https://", "a.com", "https://a .com")
            .forEach { assertFalse(isValidRedirectBaseUrl(it), it) }
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
            doc.check(JsonObject().put("items", JsonArray().add(link)).put("enabledProviders", JsonArray().add("github")))
        )
        assertTrue(doc.check(JsonObject().put("links", JsonArray())).isNotEmpty())
    }

    @Test
    fun `the register and link-existing answers match the session shape`() {
        assertEquals(emptyList<String>(), SocialRegisterCompleteAPI(plugin, dao).doc!!.check(JsonObject().put("action", "login").put("csrfToken", "c")))
        assertEquals(emptyList<String>(), SocialRegisterCompleteAPI(plugin, dao).doc!!.check(JsonObject().put("action", "verify-email").put("emailVerificationRequired", true).put("email", "a***@b.c")))
        assertEquals(
            emptyList<String>(),
            SocialLinkExistingAPI(plugin, dao).doc!!.check(JsonObject().put("action", "complete-required").put("pendingToken", "t").put("provider", "google"))
        )
    }
}
