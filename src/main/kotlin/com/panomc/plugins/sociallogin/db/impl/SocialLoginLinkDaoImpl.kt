package com.panomc.plugins.sociallogin.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class SocialLoginLinkDaoImpl : SocialLoginLinkDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                    CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                      `id` bigint NOT NULL AUTO_INCREMENT,
                      `userId` bigint NOT NULL,
                      `provider` VARCHAR(32) NOT NULL,
                      `providerUserId` VARCHAR(128) NOT NULL,
                      `email` VARCHAR(255) DEFAULT NULL,
                      `displayName` VARCHAR(255) DEFAULT NULL,
                      `avatarUrl` VARCHAR(512) DEFAULT NULL,
                      `linkedAt` BIGINT(20) NOT NULL,
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `unique_provider_account` (`provider`, `providerUserId`),
                      UNIQUE KEY `unique_user_provider` (`userId`, `provider`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Linked social login accounts.';
                """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(link: SocialLoginLink, sqlClient: SqlClient) {
        val query = """
            INSERT INTO `${getTablePrefix() + tableName}` (`userId`, `provider`, `providerUserId`, `email`, `displayName`, `avatarUrl`, `linkedAt`)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        sqlClient.preparedQuery(query)
            .execute(
                Tuple.of(
                    link.userId,
                    link.provider,
                    link.providerUserId,
                    link.email,
                    link.displayName,
                    link.avatarUrl,
                    link.linkedAt
                )
            )
            .coAwait()
    }

    override suspend fun getByProviderAndProviderUserId(
        provider: String,
        providerUserId: String,
        sqlClient: SqlClient
    ): SocialLoginLink? {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `provider` = ? AND `providerUserId` = ?"
        val rows = sqlClient.preparedQuery(query).execute(Tuple.of(provider, providerUserId)).coAwait()
        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByUserId(userId: Long, sqlClient: SqlClient): List<SocialLoginLink> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? ORDER BY `linkedAt` DESC"
        val rows = sqlClient.preparedQuery(query).execute(Tuple.of(userId)).coAwait()
        return rows.toEntities()
    }

    override suspend fun getByUserIdAndProvider(
        userId: Long,
        provider: String,
        sqlClient: SqlClient
    ): SocialLoginLink? {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? AND `provider` = ?"
        val rows = sqlClient.preparedQuery(query).execute(Tuple.of(userId, provider)).coAwait()
        return rows.toEntities().getOrNull(0)
    }

    override suspend fun deleteByUserIdAndProvider(userId: Long, provider: String, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? AND `provider` = ?"
        sqlClient.preparedQuery(query).execute(Tuple.of(userId, provider)).coAwait()
    }

    override suspend fun deleteByUserId(userId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `userId` = ?"
        sqlClient.preparedQuery(query).execute(Tuple.of(userId)).coAwait()
    }

    override suspend fun deleteOrphans(sqlClient: SqlClient): Int {
        // The core user table: entity `User` under the same table prefix.
        val query = "DELETE FROM `${getTablePrefix() + tableName}` " +
            "WHERE `userId` NOT IN (SELECT `id` FROM `${getTablePrefix()}user`)"
        return sqlClient.query(query).execute().coAwait().rowCount()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`").execute().coAwait()
    }
}
