package com.panomc.plugins.sociallogin.error

import com.panomc.platform.model.Error

/** The requested provider is not enabled in the plugin settings. */
class ProviderNotEnabled : Error()

/** The admin's re-authentication password (for revealing a stored client secret) was wrong. */
class InvalidPassword : Error(403)

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
