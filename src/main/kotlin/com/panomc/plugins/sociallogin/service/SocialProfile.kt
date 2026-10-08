package com.panomc.plugins.sociallogin.service

import com.panomc.plugins.sociallogin.config.SocialProvider

/**
 * Normalized identity returned by every provider after a successful OAuth exchange.
 *
 * [providerUserId] is the provider's stable account id — the only value matched against
 * existing links. [email]/[emailVerified] are best-effort: some providers (e.g. X, Instagram)
 * never return an email, in which case [email] is null and [emailVerified] is false.
 */
data class SocialProfile(
    val provider: SocialProvider,
    val providerUserId: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val avatarUrl: String?
)
