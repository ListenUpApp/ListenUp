package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.server.db.sqldelight.Hardcover_api_token
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * The admin's API token as a catalogue read uses it: the token, and whose Hardcover account it is.
 * [toString] never prints the token.
 */
class UsableApiToken(
    val token: String,
    val username: String,
) {
    override fun toString(): String = "UsableApiToken(username=$username, token=<redacted>)"
}

/**
 * `hardcover_api_token` (#1542): the admin's Hardcover API token, sealed by [cipher] at rest. It is
 * write-only: [status] describes it by its owner, and only [usable] — for catalogue reads — opens it.
 * A row [cipher] can't open (a backup restored under another JWT secret) reads as rejected, so the
 * admin is asked to replace it rather than the server failing quietly.
 */
class HardcoverApiTokenStore(
    private val sql: ListenUpDatabase,
    private val cipher: HardcoverTokenCipher,
    private val clock: Clock = Clock.System,
) {
    private val queries get() = sql.hardcoverApiTokenQueries

    /** What the admin is shown: no token, whose token, or that Hardcover rejected it. Never the token. */
    suspend fun status(): HardcoverApiTokenStatus {
        val row = storedRow() ?: return HardcoverApiTokenStatus.NotSet
        return if (row.rejected_at != null || cipher.decrypt(row.token_enc) == null) {
            HardcoverApiTokenStatus.Rejected(row.hc_username)
        } else {
            HardcoverApiTokenStatus.Saved(row.hc_username, row.set_at)
        }
    }

    /** The token for a catalogue read, or null when none is stored, it was rejected, or it can't be opened. */
    suspend fun usable(): UsableApiToken? {
        val row = storedRow() ?: return null
        if (row.rejected_at != null) return null
        return cipher.decrypt(row.token_enc)?.let { UsableApiToken(it, row.hc_username) }
    }

    /** Stores [token] (already checked with Hardcover) as [username]'s, replacing any earlier one. */
    suspend fun save(
        token: String,
        username: String,
    ) {
        val sealed = cipher.encrypt(token)
        val at = clock.now().toEpochMilliseconds()
        suspendTransaction(sql) { queries.saveToken(token_enc = sealed, hc_username = username, set_at = at) }
    }

    /**
     * Marks [token] rejected — only while it is still the stored, unrejected token, so a token the admin
     * has replaced since a read began is never blamed. True when this call marked it.
     */
    suspend fun markRejected(token: String): Boolean {
        val at = clock.now().toEpochMilliseconds()
        return suspendTransaction<Boolean>(sql) {
            val row = queries.selectToken().executeAsOneOrNull() ?: return@suspendTransaction false
            if (row.rejected_at != null || cipher.decrypt(row.token_enc) != token) return@suspendTransaction false
            queries.markRejected(rejected_at = at)
            true
        }
    }

    private suspend fun storedRow(): Hardcover_api_token? =
        suspendTransaction(sql) { queries.selectToken().executeAsOneOrNull() }

    /** Removes the token. Catalogue reads borrow a connected account again. */
    suspend fun clear() {
        suspendTransaction(sql) { queries.deleteToken() }
    }
}
