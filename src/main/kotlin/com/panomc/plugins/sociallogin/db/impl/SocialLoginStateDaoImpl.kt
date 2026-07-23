package com.panomc.plugins.sociallogin.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.sociallogin.db.dao.SocialLoginStateDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginState
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class SocialLoginStateDaoImpl : SocialLoginStateDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                    CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                      `id` bigint NOT NULL AUTO_INCREMENT,
                      `token` VARCHAR(128) NOT NULL,
                      `kind` VARCHAR(32) NOT NULL,
                      `payload` TEXT NOT NULL,
                      `expiresAt` BIGINT(20) NOT NULL,
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `unique_token` (`token`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transient OAuth flow state.';
                """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(state: SocialLoginState, sqlClient: SqlClient) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`token`, `kind`, `payload`, `expiresAt`) VALUES (?, ?, ?, ?)"
        sqlClient.preparedQuery(query)
            .execute(Tuple.of(state.token, state.kind, state.payload, state.expiresAt))
            .coAwait()
    }

    override suspend fun getByToken(token: String, sqlClient: SqlClient): SocialLoginState? {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `token` = ?"
        val rows = sqlClient.preparedQuery(query).execute(Tuple.of(token)).coAwait()
        return rows.toEntities().getOrNull(0)
    }

    override suspend fun deleteByToken(token: String, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `token` = ?"
        sqlClient.preparedQuery(query).execute(Tuple.of(token)).coAwait()
    }

    override suspend fun deleteExpired(sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `expiresAt` < ?"
        sqlClient.preparedQuery(query).execute(Tuple.of(System.currentTimeMillis())).coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`").execute().coAwait()
    }
}
