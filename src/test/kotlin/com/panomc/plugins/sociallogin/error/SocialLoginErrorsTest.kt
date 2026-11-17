package com.panomc.plugins.sociallogin.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A caller's mistake or a switched-off provider answers 4xx, never 500 (doc 04 section 3). */
class SocialLoginErrorsTest {
    @Test
    fun `declared errors answer 4xx with their code`() {
        assertEquals(409, ProviderNotEnabled().getStatusCode())
        assertEquals("PROVIDER_NOT_ENABLED", ProviderNotEnabled().code)
        assertEquals(403, InvalidPassword().getStatusCode())
        assertEquals("INVALID_PASSWORD", InvalidPassword().code)
        assertEquals(409, SocialAccountAlreadyLinked().getStatusCode())
        assertEquals("SOCIAL_ACCOUNT_ALREADY_LINKED", SocialAccountAlreadyLinked().code)
        assertEquals(409, SocialAccountNotLinked().getStatusCode())
        assertEquals("SOCIAL_ACCOUNT_NOT_LINKED", SocialAccountNotLinked().code)
        assertEquals(400, InvalidFlowState().getStatusCode())
        assertEquals("INVALID_FLOW_STATE", InvalidFlowState().code)
        assertEquals(403, EmailMismatch().getStatusCode())
        assertEquals("EMAIL_MISMATCH", EmailMismatch().code)
        assertEquals(400, SocialLoginFailed().getStatusCode())
        assertEquals("SOCIAL_LOGIN_FAILED", SocialLoginFailed().code)
    }

    @Test
    fun `no declared error answers 500`() {
        listOf(
            ProviderNotEnabled(), InvalidPassword(), SocialAccountAlreadyLinked(), SocialAccountNotLinked(),
            InvalidFlowState(), EmailMismatch(), SocialLoginFailed("x")
        ).forEach { assertEquals(true, it.getStatusCode() in 400..499, it.code) }
    }

    @Test
    fun `a failed exchange answers a standard OAuth code, never the provider's text`() {
        val body = SocialLoginFailed("invalid_grant").encode(mapOf())

        assertEquals(true, body.contains("\"code\":\"SOCIAL_LOGIN_FAILED\""))
        assertEquals(true, body.contains("\"oauthError\":\"invalid_grant\""))
        assertEquals(false, body.contains("\"message\""))
    }

    @Test
    fun `a non-standard provider value is not answered`() {
        val body = SocialLoginFailed("Bad code from 10.0.0.5: connection refused").encode(mapOf())

        assertEquals(true, body.contains("\"code\":\"SOCIAL_LOGIN_FAILED\""))
        assertEquals(false, body.contains("10.0.0.5"))
        assertEquals(false, body.contains("oauthError"))
        assertEquals(false, body.contains("\"message\""))
    }
}
