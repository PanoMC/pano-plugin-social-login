package com.panomc.plugins.sociallogin.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * Indexes `social_login_state.expiresAt`, so the periodic purge of expired rows no longer scans (and locks) the
 * whole table. New installs get the index from the table definition and start at version 2.
 */
@Migration
class SocialLoginMigration1to2 : DatabaseMigration(1, 2, "Index social_login_state.expiresAt") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addExpiresAtIndex()
    )

    private fun addExpiresAtIndex(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        val table = getTablePrefix() + "social_login_state"

        // Re-runnable without the platform's addIndexIfMissing helper, which the pinned Pano build lacks.
        val exists = sqlClient
            .preparedQuery(
                "SELECT COUNT(*) AS c FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?"
            )
            .execute(Tuple.of(table, "idx_expiresAt"))
            .coAwait()
            .first()
            .getLong("c") > 0

        if (!exists) {
            sqlClient.query("ALTER TABLE `$table` ADD KEY `idx_expiresAt` (`expiresAt`)").execute().coAwait()
        }
    }
}
