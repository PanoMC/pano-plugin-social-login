package com.panomc.plugins.sociallogin.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import io.vertx.sqlclient.SqlClient

abstract class SocialLoginLinkDao : Dao<SocialLoginLink>(SocialLoginLink::class.java) {
    abstract suspend fun add(link: SocialLoginLink, sqlClient: SqlClient)

    abstract suspend fun getByProviderAndProviderUserId(
        provider: String,
        providerUserId: String,
        sqlClient: SqlClient
    ): SocialLoginLink?

    abstract suspend fun getByUserId(userId: Long, sqlClient: SqlClient): List<SocialLoginLink>

    abstract suspend fun getByUserIdAndProvider(
        userId: Long,
        provider: String,
        sqlClient: SqlClient
    ): SocialLoginLink?

    abstract suspend fun deleteByUserIdAndProvider(userId: Long, provider: String, sqlClient: SqlClient)

    abstract suspend fun deleteByUserId(userId: Long, sqlClient: SqlClient)
}
