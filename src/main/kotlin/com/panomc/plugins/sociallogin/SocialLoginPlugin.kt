package com.panomc.plugins.sociallogin

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.setup.SetupManager
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.core.Vertx

class SocialLoginPlugin : PanoPlugin() {
    private val pluginDatabaseManager by lazy {
        applicationContext.getBean(PluginDatabaseManager::class.java)
    }

    private val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    private var isInitialized = false

    override suspend fun onStart() {
        logger.info("Starting...")

        startPlugin()
    }

    internal suspend fun startPlugin() {
        if (isInitialized) return

        if (!setupManager.isSetupDone()) {
            logger.info("Setup is not finished, waiting for setup completion...")
            return
        }

        isInitialized = true

        try {
            val configManager = registerIfAbsent(PluginConfigManager::class.java.name) {
                PluginConfigManager(this, SocialLoginConfig::class.java)
            }

            val vertx = applicationContext.getBean(Vertx::class.java)
            registerIfAbsent(OAuthProviderRegistry::class.java.name) { OAuthProviderRegistry(configManager, vertx) }

            registerIfAbsent(SocialLoginService::class.java.name) { SocialLoginService(this) }

            pluginDatabaseManager.initialize(this)
        } catch (e: Throwable) {
            // Leave the plugin startable again: a later start retries instead of returning early.
            isInitialized = false
            throw e
        }

        removeOrphanLinks()

        logger.info("Started!")
    }

    /**
     * Registers [create]'s bean under [name] unless a previous (possibly failed) start already did, so a retry
     * does not fail on an existing singleton.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> registerIfAbsent(name: String, create: () -> T): T {
        val beanFactory = pluginBeanContext.beanFactory
        if (beanFactory.containsSingleton(name)) return beanFactory.getSingleton(name) as T

        return create().also { beanFactory.registerSingleton(name, it) }
    }

    /**
     * Links of users deleted while this plugin was off never saw the delete event; they would keep the provider
     * account from being linked again. One cheap sweep on start; failures only log.
     */
    private suspend fun removeOrphanLinks() {
        try {
            val sqlClient = applicationContext.getBean(DatabaseManager::class.java).getSqlClient()
            val removed = pluginBeanContext.getBean(SocialLoginLinkDao::class.java).deleteOrphans(sqlClient)
            if (removed > 0) logger.info("Removed $removed social link(s) of deleted users.")
        } catch (e: Exception) {
            logger.warn("Could not remove social links of deleted users: ${e.message}")
        }
    }

    /** Closes the provider web clients so a disabled or stopped plugin keeps no client (or class loader) alive. */
    private fun closeProviderClients() {
        val name = OAuthProviderRegistry::class.java.name
        val beanFactory = try {
            pluginBeanContext.beanFactory
        } catch (_: Exception) {
            // Not set up (never started) or already closed: nothing was created.
            return
        }
        if (!beanFactory.containsSingleton(name)) return

        (beanFactory.getSingleton(name) as? OAuthProviderRegistry)?.close()
    }

    override suspend fun onEnable() {
        logger.info("Enabled!")
    }

    override suspend fun onStop() {
        closeProviderClients()
    }

    override suspend fun onDisable() {
        isInitialized = false
        closeProviderClients()
    }

    override suspend fun onUninstall() {
        logger.info("Uninstalling...")

        pluginDatabaseManager.uninstall(this)
    }
}
