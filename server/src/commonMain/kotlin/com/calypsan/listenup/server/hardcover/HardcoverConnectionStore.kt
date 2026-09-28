package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
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

    /** Usable: connected as [hardcoverUsername] since [connectedAt] (epoch ms). */
    data class Healthy(
        val hardcoverUsername: String,
        val connectedAt: Long,
        override val credentials: StoredCredentials,
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

/**
 * The `hardcover_connections` table: one row per connected user, both tokens sealed by
 * [HardcoverTokenCipher] so the database or a backup alone never exposes a Hardcover account.
 *
 * A row whose tokens don't decrypt (restored onto a server with a different JWT secret) reads as
 * [StoredConnection.Broken] with [HardcoverBrokenReason.CANNOT_DECRYPT]. That is derived on every
 * read rather than written back, so restoring the original secret heals it with no migration.
 */
class HardcoverConnectionStore(
    private val sql: ListenUpDatabase,
    private val cipher: HardcoverTokenCipher,
    private val clock: Clock = Clock.System,
) {
    private val queries get() = sql.hardcoverConnectionsQueries

    /** [userId]'s connection, decrypted, or null when they have never connected (or disconnected). */
    suspend fun connectionFor(userId: String): StoredConnection? =
        suspendTransaction(sql) { queries.selectByUser(userId).executeAsOneOrNull() }?.toStored()

    /** What [userId]'s client should show, straight from the row. */
    suspend fun connectionState(userId: String): HardcoverConnection =
        when (val stored = connectionFor(userId)) {
            null -> HardcoverConnection.NotConnected()
            is StoredConnection.Healthy -> HardcoverConnection.Connected(stored.hardcoverUsername, stored.connectedAt)
            is StoredConnection.Broken -> HardcoverConnection.Broken(stored.reason, stored.hardcoverUsername)
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
        return HardcoverConnection.Connected(me.username, now)
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

    /** Marks [userId]'s connection as needing a reconnect for [reason]. */
    suspend fun markBroken(
        userId: String,
        reason: HardcoverBrokenReason,
    ) {
        suspendTransaction(sql) { queries.markBroken(broken_reason = reason.name, user_id = userId) }
    }

    /** Forgets [userId]'s connection. A no-op when there is none. */
    suspend fun delete(userId: String) {
        suspendTransaction(sql) { queries.deleteByUser(userId) }
    }

    private fun Hardcover_connections.toStored(): StoredConnection {
        val access = cipher.decrypt(access_token_enc)
        val refresh = cipher.decrypt(refresh_token_enc)
        val credentials =
            if (access != null && refresh != null) StoredCredentials(access, access_expires_at, refresh) else null
        val reason = broken_reason?.let(::brokenReasonNamed)
        return when {
            reason != null -> StoredConnection.Broken(reason, hc_username, credentials)
            credentials == null -> StoredConnection.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, hc_username, null)
            else -> StoredConnection.Healthy(hc_username, connected_at, credentials)
        }
    }

    /** A stored reason this build doesn't know still means "reconnect" — never "healthy". */
    private fun brokenReasonNamed(name: String): HardcoverBrokenReason =
        HardcoverBrokenReason.entries.firstOrNull { it.name == name } ?: HardcoverBrokenReason.REVOKED
}
