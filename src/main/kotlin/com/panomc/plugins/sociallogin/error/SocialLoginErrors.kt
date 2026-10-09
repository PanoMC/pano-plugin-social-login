package com.panomc.plugins.sociallogin.error

import com.panomc.platform.model.Error

/** `error.message` of the answer: the English text a caller may show; omitted when null. */
private fun messageExtras(message: String?): Map<String, Any?> =
    if (message == null) mapOf() else mapOf("message" to message)

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

/** The provider refused the code or answered something unusable; [message] is the English reason, when there is one. */
class SocialLoginFailed(message: String? = null) :
    Error("SOCIAL_LOGIN_FAILED", 400, extras = messageExtras(message))
