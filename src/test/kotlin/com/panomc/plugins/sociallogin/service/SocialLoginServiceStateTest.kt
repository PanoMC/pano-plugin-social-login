package com.panomc.plugins.sociallogin.service

import com.panomc.plugins.sociallogin.db.model.SocialLoginState
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SocialLoginServiceStateTest {
    private val now = 1_000_000L

    private fun row(
        kind: String = SocialLoginState.Kind.OAUTH_STATE.name,
        expiresAt: Long = now + 60_000,
        payload: JsonObject = JsonObject().put("intent", "login").put("provider", "github").put("browser", "hash")
    ) = SocialLoginState(token = "token", kind = kind, payload = payload.encode(), expiresAt = expiresAt)

    @Test
    fun `pending payload without intent counts as register`() {
        assertEquals(SocialLoginService.PENDING_INTENT_REGISTER, SocialLoginService.pendingIntent(JsonObject()))
    }

    @Test
    fun `pending payload keeps its link intent`() {
        val payload = JsonObject().put("intent", SocialLoginService.PENDING_INTENT_LINK)
        assertEquals(SocialLoginService.PENDING_INTENT_LINK, SocialLoginService.pendingIntent(payload))
    }

    @Test
    fun `pending payload keeps its register intent`() {
        val payload = JsonObject().put("intent", SocialLoginService.PENDING_INTENT_REGISTER)
        assertEquals(SocialLoginService.PENDING_INTENT_REGISTER, SocialLoginService.pendingIntent(payload))
    }

    @Test
    fun `unknown state is refused without checking the binding`() {
        var bindingChecked = false
        assertNull(SocialLoginService.validOAuthStatePayload(null, now) { bindingChecked = true; true })
        assertFalse(bindingChecked)
    }

    @Test
    fun `pending registration row is not an oauth state`() {
        var bindingChecked = false
        val result = SocialLoginService.validOAuthStatePayload(
            row(kind = SocialLoginState.Kind.PENDING_REGISTRATION.name), now
        ) { bindingChecked = true; true }
        assertNull(result)
        assertFalse(bindingChecked)
    }

    @Test
    fun `expired state is refused`() {
        assertNull(SocialLoginService.validOAuthStatePayload(row(expiresAt = now - 1), now) { true })
    }

    @Test
    fun `state expiring exactly now is still accepted`() {
        assertNotNull(SocialLoginService.validOAuthStatePayload(row(expiresAt = now), now) { true })
    }

    @Test
    fun `binding mismatch is refused`() {
        assertNull(SocialLoginService.validOAuthStatePayload(row(), now) { false })
    }

    @Test
    fun `binding check sees the stored payload`() {
        var seenBrowser: String? = null
        SocialLoginService.validOAuthStatePayload(row(), now) { seenBrowser = it.getString("browser"); true }
        assertEquals("hash", seenBrowser)
    }

    @Test
    fun `valid state returns its payload`() {
        val payload = SocialLoginService.validOAuthStatePayload(row(), now) { true }
        assertNotNull(payload)
        assertEquals("login", payload!!.getString("intent"))
        assertEquals("github", payload.getString("provider"))
    }
}
