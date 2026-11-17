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
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SocialLoginPlugin : PanoPlugin() {
    companion object {
        /** How often expired OAuth states and pending registrations are deleted. */
        private const val PURGE_INTERVAL_MS = 10 * 60 * 1000L

        /** The plugin's tables without the platform prefix (the snake-case entity names the DAOs use). */
        private val TABLES = listOf("social_login_link", "social_login_state")
    }

    private val pluginDatabaseManager by lazy {
        applicationContext.getBean(PluginDatabaseManager::class.java)
    }

    private val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    private var isInitialized = false

    private var purgeTimerId: Long? = null
    private var purgeScope: CoroutineScope? = null
    private val purgeRunning = AtomicBoolean(false)

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

        purgeExpiredStates()
        startPurgeTimer()

        logger.info("Started!")
    }

    /**
     * Deletes expired flow rows: OAuth states and pending registrations (which hold a provider e-mail, name and
     * avatar). Expired rows are already refused when read; this only keeps the table small. Failures only log.
     */
    private suspend fun purgeExpiredStates() {
        if (!purgeRunning.compareAndSet(false, true)) return

        try {
            val sqlClient = applicationContext.getBean(DatabaseManager::class.java).getSqlClient()
            val removed = pluginBeanContext.getBean(SocialLoginService::class.java).purgeExpired(sqlClient)
            if (removed > 0) logger.debug("Removed $removed expired social login state(s).")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not remove expired social login states: ${e.message}")
        } finally {
            purgeRunning.set(false)
        }
    }

    @Synchronized
    private fun startPurgeTimer() {
        if (purgeTimerId != null) return

        val vertx = applicationContext.getBean(Vertx::class.java)
        val scope = CoroutineScope(SupervisorJob() + vertx.dispatcher())

        purgeScope = scope
        purgeTimerId = vertx.setPeriodic(PURGE_INTERVAL_MS) {
            scope.launch { purgeExpiredStates() }
        }
    }

    /** Cancels the purge timer and a purge still running (idempotent). */
    @Synchronized
    private fun stopPurgeTimer() {
        purgeTimerId?.let { timerId ->
            try {
                applicationContext.getBean(Vertx::class.java).cancelTimer(timerId)
            } catch (e: Exception) {
                logger.warn("Could not cancel the social login purge timer: ${e.message}")
            }
        }
        purgeTimerId = null
        purgeScope?.cancel()
        purgeScope = null
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
        stopPurgeTimer()
        closeProviderClients()
    }

    override suspend fun onDisable() {
        isInitialized = false
        stopPurgeTimer()
        closeProviderClients()
    }

    override suspend fun onUninstall() {
        logger.info("Uninstalling...")

        stopPurgeTimer()

        try {
            pluginDatabaseManager.uninstall(this)
        } catch (e: Exception) {
            logger.warn("Platform uninstall of the social login database failed: ${e.message}")
        }

        dropOwnTables()
    }

    /**
     * [PluginDatabaseManager.uninstall] does nothing when it holds no DAOs for this plugin (e.g. the plugin was
     * disabled before the delete, or its database was never initialised), which would leave the tables behind.
     * Drops them by name under the platform's table prefix as well (idempotent), and the plugin's scheme version.
     * Without a configured database (set-up not past its database step) there is nothing to drop.
     */
    private suspend fun dropOwnTables() {
        val databaseManager = try {
            applicationContext.getBean(DatabaseManager::class.java)
        } catch (e: Exception) {
            logger.warn("Could not drop the social login tables: ${e.message}")
            return
        }

        val sqlClient = try {
            databaseManager.getSqlClient()
        } catch (e: Exception) {
            logger.info("No database to clean up for social login: ${e.message}")
            return
        }

        try {
            val prefix = databaseManager.getTablePrefix()
            TABLES.forEach { table ->
                sqlClient.query("DROP TABLE IF EXISTS `$prefix$table`").execute().coAwait()
            }
            databaseManager.schemeVersionDao.deleteByPluginId(pluginId, sqlClient)
        } catch (e: Exception) {
            logger.warn("Could not drop the social login tables: ${e.message}")
        }
    }
}
