package com.panomc.plugins.sociallogin

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.setup.SetupManager
import com.panomc.plugins.license.PluginLicenseClient
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import com.panomc.plugins.sociallogin.service.OAuthProviderRegistry
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.core.Vertx

class SocialLoginPlugin : PanoPlugin() {
    /**
     * Premium plugin: enforce a panomc.com license at startup. No-op for free builds
     * (empty panoLicensePublicKey in gradle.properties).
     */
    private val licenseClient by lazy { PluginLicenseClient(this) }

    private val pluginDatabaseManager by lazy {
        applicationContext.getBean(PluginDatabaseManager::class.java)
    }

    private val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    private var isInitialized = false

    override suspend fun onStart() {
        licenseClient.requireValidLicense()

        logger.info("Starting...")

        startPlugin()
    }

    override suspend fun verifyLicense() {
        licenseClient.requireValidLicense()
    }

    internal suspend fun startPlugin() {
        if (isInitialized) return

        if (!setupManager.isSetupDone()) {
            logger.info("Setup is not finished, waiting for setup completion...")
            return
        }

        isInitialized = true

        val configManager = PluginConfigManager(this, SocialLoginConfig::class.java)
        pluginBeanContext.beanFactory.registerSingleton(PluginConfigManager::class.java.name, configManager)

        val vertx = applicationContext.getBean(Vertx::class.java)
        val providerRegistry = OAuthProviderRegistry(configManager, vertx)
        pluginBeanContext.beanFactory.registerSingleton(OAuthProviderRegistry::class.java.name, providerRegistry)

        val socialLoginService = SocialLoginService(this)
        pluginBeanContext.beanFactory.registerSingleton(SocialLoginService::class.java.name, socialLoginService)

        pluginDatabaseManager.initialize(this)

        logger.info("Started!")
    }

    override suspend fun onEnable() {
        logger.info("Enabled!")
    }

    override suspend fun onDisable() {
        isInitialized = false
    }

    override suspend fun onUninstall() {
        logger.info("Uninstalling...")

        pluginDatabaseManager.uninstall(this)
    }
}
