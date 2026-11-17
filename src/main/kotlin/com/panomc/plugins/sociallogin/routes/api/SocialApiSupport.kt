package com.panomc.plugins.sociallogin.routes.api

import com.panomc.platform.schema.dsl.Parameters
import com.panomc.plugins.sociallogin.config.SocialProvider
import com.panomc.plugins.sociallogin.db.dao.SocialLoginLinkDao
import com.panomc.plugins.sociallogin.db.model.SocialLoginLink
import com.panomc.plugins.sociallogin.error.SocialAccountAlreadyLinked
import com.panomc.plugins.sociallogin.service.SocialLoginService
import io.vertx.ext.web.validation.impl.parser.ValueParser
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.StringSchemaBuilder
import io.vertx.mysqlclient.MySQLException

/** Length caps of the request fields of the site routes. */
internal object SocialLimits {
    /** OAuth `state`, provider `code`, pending tokens and binding secrets. */
    const val TOKEN = 512
    const val PASSWORD = 128
    const val USERNAME_OR_EMAIL = 255
    const val EMAIL = 255

    /**
     * RegisterUtil allows 16 characters after surrounding whitespace is stripped; the cap leaves room for that
     * whitespace so a padded name still gets RegisterUtil's own error instead of a schema error.
     */
    const val USERNAME = 32

    /** Callback query values, as the provider sends them. */
    const val CALLBACK_CODE = 2048
    const val CALLBACK_STATE = 512
    const val CALLBACK_ERROR = 128
}

/** A string schema with a `maxLength`. */
internal fun cappedString(maxLength: Int): StringSchemaBuilder = stringSchema().withKeyword("maxLength", maxLength)

/** The `:provider` path parameter: one of the known provider keys. */
internal fun providerPathParam() = Parameters.param(
    "provider",
    enumSchema(*SocialProvider.entries.map { it.key }.toTypedArray()),
    ValueParser.NOOP_PARSER
)

/** The provider body field: one of the known provider keys. */
internal fun providerSchema() = enumSchema(*SocialProvider.entries.map { it.key }.toTypedArray())

private const val ER_DUP_ENTRY = 1062
private const val ER_LOCK_WAIT_TIMEOUT = 1205
private const val ER_LOCK_DEADLOCK = 1213

private fun Throwable.mysqlErrorCodes(): Sequence<Int> =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(8)
        .mapNotNull { (it as? MySQLException)?.errorCode }

/** A unique-key violation (`1062`) anywhere in the cause chain. */
internal fun Throwable.isDuplicateKey(): Boolean =
    mysqlErrorCodes().any { it == ER_DUP_ENTRY } ||
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(8)
            .any { it.message?.contains("Duplicate entry", ignoreCase = true) == true }

/** The text of the first duplicate-key message in the cause chain (names the key, e.g. `for key 'email'`). */
internal fun Throwable.duplicateKeyMessage(): String =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(8)
        .mapNotNull { it.message }
        .firstOrNull { it.contains("Duplicate entry", ignoreCase = true) }
        .orEmpty()

/** A deadlock (`1213`) or lock wait timeout (`1205`): the transaction was rolled back and may simply be run again. */
internal fun Throwable.isRetryableLockError(): Boolean =
    mysqlErrorCodes().any { it == ER_LOCK_DEADLOCK || it == ER_LOCK_WAIT_TIMEOUT }

/** Runs [block] (one whole transaction) and runs it once more when the database rolled it back for a lock conflict. */
internal suspend fun <T> retryOnLockConflict(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        // Declared errors are Throwables, not Exceptions, so they are never retried.
        if (!e.isRetryableLockError()) throw e
        block()
    }

/**
 * Replaces the user's link for [SocialLoginLink.provider] with [link], inside the caller's transaction [tx]. The old
 * row is only deleted when there is one: a DELETE that matches nothing takes a gap lock, and two such transactions
 * then deadlock on their INSERTs.
 */
internal suspend fun replaceLink(
    socialLoginLinkDao: SocialLoginLinkDao,
    socialLoginService: SocialLoginService,
    link: SocialLoginLink,
    tx: io.vertx.sqlclient.SqlClient
) {
    if (socialLoginLinkDao.getByUserIdAndProvider(link.userId, link.provider, tx) != null) {
        socialLoginLinkDao.deleteByUserIdAndProvider(link.userId, link.provider, tx)
    }
    socialLoginService.addLinkUnique(link, tx)
}

/** Maps a duplicate key that survived [retryOnLockConflict] (a lost race on the same link) to [SocialAccountAlreadyLinked]. */
internal suspend fun <T> linkDuplicateAsAlreadyLinked(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        // Declared errors (com.panomc.platform.model.Error) are Throwables, not Exceptions: they pass through.
        if (e.isDuplicateKey()) throw SocialAccountAlreadyLinked()
        throw e
    }
