package com.panomc.plugins.sociallogin.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.sociallogin.db.model.SocialLoginState
import io.vertx.sqlclient.SqlClient

abstract class SocialLoginStateDao : Dao<SocialLoginState>(SocialLoginState::class.java) {
    abstract suspend fun add(state: SocialLoginState, sqlClient: SqlClient)

    abstract suspend fun getByToken(token: String, sqlClient: SqlClient): SocialLoginState?

    abstract suspend fun deleteByToken(token: String, sqlClient: SqlClient)

    abstract suspend fun deleteExpired(sqlClient: SqlClient)
}
