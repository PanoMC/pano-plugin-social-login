package com.panomc.plugins.sociallogin.error

import com.panomc.platform.model.Error

/** The requested provider is not enabled in the plugin settings. */
class ProviderNotEnabled : Error()

/** The OAuth exchange with the provider failed (network error, denied consent, bad credentials). */
class SocialLoginFailed : Error()

/** This social account is already linked to a different Pano user. */
class SocialAccountAlreadyLinked : Error()

/** The user has no link for this provider to unlink. */
class SocialAccountNotLinked : Error()

/** The OAuth state / pending-registration token is missing, expired or already consumed. */
class InvalidFlowState : Error()

/**
 * On the "account exists" path, the account the user authenticated as does not own the
 * verified email the social provider returned.
 */
class EmailMismatch : Error()
