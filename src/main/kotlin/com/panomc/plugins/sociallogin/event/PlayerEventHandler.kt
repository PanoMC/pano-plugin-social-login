package com.panomc.plugins.sociallogin.event

import com.panomc.platform.api.annotation.EventListener
import com.panomc.platform.api.event.PlayerEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao

/**
 * Cleans up a user's social-login links when their Pano account is deleted. The platform fires
 * [PlayerEventListener.onDelete] before the user row is removed, so leaving the links behind would
 * orphan them and keep the `(provider, providerUserId)` unique slot occupied — blocking that social
 * account from ever being linked to a future user.
 */
@EventListener
class PlayerEventHandler(
    private val plugin: SocialLoginPlugin,
    private val socialLoginLinkDao: SocialLoginLinkDao
) : PlayerEventListener {
    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override suspend fun onDelete(user: User) {
        val sqlClient = databaseManager.getSqlClient()
        socialLoginLinkDao.deleteByUserId(user.id, sqlClient)
    }
}
