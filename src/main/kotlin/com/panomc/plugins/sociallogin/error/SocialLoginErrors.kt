package com.panomc.plugins.sociallogin.error

import com.panomc.platform.model.Error


/** The requested provider is not enabled in the plugin settings (the caller asked for something switched off). */
class ProviderNotEnabled : Error("PROVIDER_NOT_ENABLED", 409)

/** The admin's re-authentication password (for revealing a stored client secret) was wrong. */
class InvalidPassword : Error("INVALID_PASSWORD", 403)

/** This social account is already linked to a different Pano user. */
class SocialAccountAlreadyLinked : Error("SOCIAL_ACCOUNT_ALREADY_LINKED", 409)

/** The user has no link for this provider to unlink. */
class SocialAccountNotLinked : Error("SOCIAL_ACCOUNT_NOT_LINKED", 409)

/** The OAuth state / pending-registration token is missing, expired or already consumed. */
class InvalidFlowState : Error("INVALID_FLOW_STATE", 400)

/**
 * On the "account exists" path, the account the user authenticated as does not own the
 * verified email the social provider returned.
 */
class EmailMismatch : Error("EMAIL_MISMATCH", 403)

/**
 * The provider refused the code or answered something unusable. The answer never carries the provider's text (it can
 * hold hosts, internal errors or echoed input); the details go to the server log. [oauthError] is a standard
 * RFC 6749 §5.2 code (`invalid_grant`, …) and is only answered when it is one of those.
 */
class SocialLoginFailed(oauthError: String? = null) :
    Error("SOCIAL_LOGIN_FAILED", 400, extras = oauthErrorExtras(oauthError))

/** RFC 6749 §5.2 token-endpoint error codes: the only provider values a caller may see. */
private val PUBLIC_OAUTH_ERRORS = setOf(
    "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client", "unsupported_grant_type", "invalid_scope"
)

private fun oauthErrorExtras(oauthError: String?): Map<String, Any?> =
    if (oauthError != null && oauthError in PUBLIC_OAUTH_ERRORS) mapOf("oauthError" to oauthError) else mapOf()
