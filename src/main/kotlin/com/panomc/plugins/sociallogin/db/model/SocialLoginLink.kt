package com.panomc.plugins.sociallogin.db.model

import com.panomc.platform.db.DBEntity

/**
 * A persistent link between a Pano user and an external social account.
 * Matching for login is done on ([provider], [providerUserId]) — never on email.
 */
open class SocialLoginLink(
    var id: Long = -1,
    val userId: Long = -1,
    val provider: String = "",
    val providerUserId: String = "",
    val email: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val linkedAt: Long = System.currentTimeMillis()
) : DBEntity()
