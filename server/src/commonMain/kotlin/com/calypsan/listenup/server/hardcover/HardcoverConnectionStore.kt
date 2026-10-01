package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.server.db.sqldelight.Hardcover_connections
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * How long a Hardcover refresh token lives: six months, per Hardcover's documented token lifetimes.
 * The token response carries only the ACCESS token's `expires_in`, so the refresh token's expiry is
 * recorded as this much after it was issued.
 */
val HARDCOVER_REFRESH_TOKEN_LIFETIME: Duration = 182.days

/**
 * A decrypted token pair and when the access token stops working (epoch ms). Plaintext secrets:
 * [toString] never prints them, so a stray log line can't leak someone's Hardcover account.
 */
class StoredCredentials(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
) {
    override fun toString(): String = "StoredCredentials(accessExpiresAt=$accessExpiresAt, tokens=<redacted>)"
}

/** A user's stored Hardcover connection, as [HardcoverConnectionStore.connectionFor] reads it. */
sealed interface StoredConnection {
    /** The decrypted tokens, or null when they can't be decrypted. */
    val credentials: StoredCredentials?

    /**
     * Usable: connected as [hardcoverUsername] since [connectedAt] (epoch ms). [lastSyncedAt] is the
     * last push that landed or pull that caught up; [pushStalled] / [pullStalled] say an error has
     * outlasted that direction's retry cap.
     */
    data class Healthy(
        val hardcoverUsername: String,
        val connectedAt: Long,
        override val credentials: StoredCredentials,
        val lastSyncedAt: Long? = null,
        val pushStalled: Boolean = false,
        val pullStalled: Boolean = false,
    ) : StoredConnection

    /**
     * Was connected as [hardcoverUsername], and needs a reconnect for [reason]. [credentials] survive
     * when they still decrypt, so a disconnect can still ask Hardcover to revoke them. The username is
     * never sealed, so it survives even when the tokens don't.
     */
    data class Broken(
        val reason: HardcoverBrokenReason,
        val hardcoverUsername: String,
        override val credentials: StoredCredentials?,
    ) : StoredConnection
}

/** When [HardcoverPushWorker] last landed a push, and the error that outlasted its retry cap, if any. */
data class HardcoverPushHealth(
    val lastSyncedAt: Long?,
    val pushError: String?,
)

/**
 * The `hardcover_connections` table: one row per connected user, both tokens sealed by
 * [HardcoverTokenCipher] so the database or a backup alone never exposes a Hardcover account.
 *
 * A row whose tokens don't decrypt (restored onto a server with a different JWT secret) reads as
 * [StoredConnection.Broken] with [HardcoverBrokenReason.CANNOT_DECRYPT]. That is derived on every
 * read rather than written back, so restoring the original secret heals it with no migration.
 *
 * It also owns push and pull health, and forgets a user's book links, pending pushes and pulled reads
 * when the connection ends or changes account. The listener's share mode is not sync state: it lives in
 * [HardcoverPreferences] and survives both. Every change to sync health ([markSynced],
 * [recordPushError], [markPulled], [recordPullError]) is announced on
 * [HardcoverSyncActivity.healthChanged], so a watching client sees it.
 */
class HardcoverConnectionStore(
    private val sql: ListenUpDatabase,
    private val cipher: HardcoverTokenCipher,
    private val clock: Clock = Clock.System,
    private val activity: HardcoverSyncActivity? = null,
) {
    private val queries get() = sql.hardcoverConnectionsQueries

    /** [userId]'s connection, decrypted, or null when they have never connected (or disconnected). */
    suspend fun connectionFor(userId: String): StoredConnection? =
        suspendTransaction(sql) { queries.selectByUser(userId).executeAsOneOrNull() }?.toStored()

    /** What [userId]'s client should show, straight from the row. */
    suspend fun connectionState(userId: String): HardcoverConnection =
        when (val stored = connectionFor(userId)) {
            null -> {
                HardcoverConnection.NotConnected()
            }

            is StoredConnection.Healthy -> {
                HardcoverConnection.Connected(
                    hardcoverUsername = stored.hardcoverUsername,
                    since = stored.connectedAt,
                    lastSyncedAt = stored.lastSyncedAt,
                    syncProblem =
                        when {
                            stored.pushStalled -> HardcoverSyncProblem.PUSH_STALLED
                            stored.pullStalled -> HardcoverSyncProblem.PULL_STALLED
                            else -> null
                        },
                    shareMode = sql.hardcoverShareMode(userId),
                )
            }

            is StoredConnection.Broken -> {
                HardcoverConnection.Broken(stored.reason, stored.hardcoverUsername)
            }
        }

    /**
     * Stores a freshly granted connection as [me], replacing any previous (typically broken) one for
     * [userId] and clearing its broken reason. Returns the state the client should now see.
     */
    suspend fun save(
        userId: String,
        me: HardcoverMe,
        tokens: HardcoverTokens,
    ): HardcoverConnection.Connected {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(sql) {
            // Links, pending pushes and pulled reads belong to ONE Hardcover account: connecting a different
            // one must neither write to the old account's records nor show its reads. The same account
            // keeps them all. upsertConnection replaces the row, so its pull cursor restarts either way
            // (the pull is idempotent). The pushed-read ledger stays either way.
            val previousAccount = queries.selectHcUserId(userId).executeAsOneOrNull()
            if (previousAccount != null && previousAccount != me.id) forgetSyncState(userId)
            queries.upsertConnection(
                user_id = userId,
                hc_user_id = me.id,
                hc_username = me.username,
                access_token_enc = cipher.encrypt(tokens.accessToken),
                access_expires_at = now + tokens.expiresInSeconds.seconds.inWholeMilliseconds,
                refresh_token_enc = cipher.encrypt(tokens.refreshToken),
                refresh_expires_at = now + HARDCOVER_REFRESH_TOKEN_LIFETIME.inWholeMilliseconds,
                scopes = tokens.scope,
                connected_at = now,
            )
        }
        // The share mode outlives the connection row this just replaced, so a reconnect reports it.
        return HardcoverConnection.Connected(me.username, now, shareMode = sql.hardcoverShareMode(userId))
    }

    /**
     * Replaces [userId]'s tokens with a refresh's rotated pair. The caller commits this BEFORE using
     * the new access token: the old refresh token is already spent, so losing the new one would strand
     * the connection.
     */
    suspend fun saveRotated(
        userId: String,
        tokens: HardcoverTokens,
    ) {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(sql) {
            queries.updateTokens(
                access_token_enc = cipher.encrypt(tokens.accessToken),
                access_expires_at = now + tokens.expiresInSeconds.seconds.inWholeMilliseconds,
                refresh_token_enc = cipher.encrypt(tokens.refreshToken),
                refresh_expires_at = now + HARDCOVER_REFRESH_TOKEN_LIFETIME.inWholeMilliseconds,
                scopes = tokens.scope,
                user_id = userId,
            )
        }
    }

    /**
     * Users whose connection isn't marked broken, ROOT first, then ADMIN, then MEMBER, oldest connection
     * first within a role. A row that no longer decrypts still counts: only [connectionFor] can tell.
     */
    suspend fun healthyUserIds(): List<String> =
        suspendTransaction(sql) { queries.selectHealthyUserIds().executeAsList() }

    /** Marks [userId]'s connection as needing a reconnect for [reason]. */
    suspend fun markBroken(
        userId: String,
        reason: HardcoverBrokenReason,
    ) {
        suspendTransaction(sql) { queries.markBroken(broken_reason = reason.name, user_id = userId) }
    }

    /**
     * Forgets [userId]'s connection, their book links, their pending pushes and their pulled Hardcover
     * reads. The pushed-read ledger
     * survives, so a later reconnect never pulls ListenUp's own reads back. A no-op when there is none.
     */
    suspend fun delete(userId: String) {
        suspendTransaction(sql) {
            queries.deleteByUser(userId)
            forgetSyncState(userId)
        }
    }

    /** Whether [userId] has a connection row at all, healthy or broken — whether pushes should queue. */
    suspend fun hasConnection(userId: String): Boolean =
        suspendTransaction(sql) { queries.existsForUser(userId).executeAsOne() }

    /** A push landed at [at]: remember it and clear any recorded error. */
    suspend fun markSynced(
        userId: String,
        at: Long,
    ) {
        suspendTransaction(sql) { queries.markSynced(last_synced_at = at, user_id = userId) }
        activity?.healthChanged(userId)
    }

    /** A push failed past the retry cap with [detail]; the row stays and keeps retrying slowly. */
    suspend fun recordPushError(
        userId: String,
        detail: String,
    ) {
        suspendTransaction(sql) { queries.recordPushError(push_error = detail, user_id = userId) }
        activity?.healthChanged(userId)
    }

    /** A pull caught up at [at]: remember it as the last sync, and clear any recorded pull error. */
    suspend fun markPulled(
        userId: String,
        at: Long,
    ) {
        suspendTransaction(sql) { queries.markPulled(last_synced_at = at, user_id = userId) }
        activity?.healthChanged(userId)
    }

    /** A pull failed past its retry cap with [detail]; it keeps retrying on schedule. */
    suspend fun recordPullError(
        userId: String,
        detail: String,
    ) {
        suspendTransaction(sql) { queries.recordPullError(pull_error = detail, user_id = userId) }
        activity?.healthChanged(userId)
    }

    /** [userId]'s push health, or null without a connection. */
    suspend fun pushHealth(userId: String): HardcoverPushHealth? =
        suspendTransaction(sql) {
            queries
                .selectPushHealth(
                    userId,
                ).executeAsOneOrNull()
                ?.let { HardcoverPushHealth(it.last_synced_at, it.push_error) }
        }

    /**
     * Everything tied to one Hardcover account: the book links, the pending pushes and the pulled reads.
     * The pushed-read ledger stays, so a reconnect never pulls ListenUp's own reads back.
     */
    private fun forgetSyncState(userId: String) {
        sql.hardcoverBookLinksQueries.deleteForUser(userId)
        sql.hardcoverOutboxQueries.deleteForUser(userId)
        sql.bookReadsQueries.deletePulledForUser(userId)
    }

    private fun Hardcover_connections.toStored(): StoredConnection {
        val access = cipher.decrypt(access_token_enc)
        val refresh = cipher.decrypt(refresh_token_enc)
        val credentials =
            if (access != null && refresh != null) StoredCredentials(access, access_expires_at, refresh) else null
        val reason = broken_reason?.let(::brokenReasonNamed)
        return when {
            reason != null -> {
                StoredConnection.Broken(reason, hc_username, credentials)
            }

            credentials == null -> {
                StoredConnection.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, hc_username, null)
            }

            else -> {
                StoredConnection.Healthy(
                    hardcoverUsername = hc_username,
                    connectedAt = connected_at,
                    credentials = credentials,
                    lastSyncedAt = last_synced_at,
                    pushStalled = push_error != null,
                    pullStalled = pull_error != null,
                )
            }
        }
    }

    /** A stored reason this build doesn't know still means "reconnect" — never "healthy". */
    private fun brokenReasonNamed(name: String): HardcoverBrokenReason =
        HardcoverBrokenReason.entries.firstOrNull { it.name == name } ?: HardcoverBrokenReason.REVOKED
}
