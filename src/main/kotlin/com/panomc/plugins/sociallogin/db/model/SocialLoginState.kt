package com.panomc.plugins.sociallogin.db.model

import com.panomc.platform.db.DBEntity

/**
 * Short-lived server-side flow state, keyed by an unguessable [token].
 *
 * [kind] is one of [Kind]; [payload] is a JSON blob whose shape depends on the kind:
 *  - OAUTH_STATE: `{ intent, provider, codeVerifier? }` — CSRF protection for the OAuth round-trip.
 *  - PENDING_REGISTRATION: `{ provider, providerUserId, email, emailVerified, displayName, avatarUrl }`
 *    — the verified social profile awaiting a username/password registration form submission.
 *
 * Step-up auth (2FA, …) does NOT live here — it goes through the platform's generic
 * `pending_auth_session` table, written via `AuthProvider.createPendingSession` and consumed by
 * `POST /api/auth/complete-pending`. social-login stays agnostic of any specific challenge.
 *
 * Rows are one-time-use (deleted on consumption) and pruned when [expiresAt] passes.
 */
open class SocialLoginState(
    var id: Long = -1,
    val token: String = "",
    val kind: String = "",
    val payload: String = "",
    val expiresAt: Long = 0
) : DBEntity() {
    enum class Kind {
        OAUTH_STATE,
        PENDING_REGISTRATION
    }
}
