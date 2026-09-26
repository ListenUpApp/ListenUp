package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Refresh when the access token has less than this left, so a sync never starts on a dying token. */
private val REFRESH_MARGIN_MS = 10.minutes.inWholeMilliseconds

/** What [HardcoverTokenProvider.accessToken] found for a user. */
sealed interface TokenLookup {
    /** A usable access token. [toString] never prints it. */
    data class Valid(
        val accessToken: String,
    ) : TokenLookup {
        override fun toString(): String = "Valid(accessToken=<redacted>)"
    }

    /** The user has no Hardcover connection. */
    data object NotConnected : TokenLookup

    /** The connection needs a reconnect for [reason]; the user must sign in again. */
    data class Broken(
        val reason: HardcoverBrokenReason,
    ) : TokenLookup

    /** Hardcover can't be reached and the current token has expired — try later; nothing is lost. */
    data object Unavailable : TokenLookup
}

/**
 * Hands out a valid Hardcover access token for a user, refreshing it when it is close to expiry.
 *
 * Hardcover ROTATES refresh tokens: each refresh returns a new one, and presenting a spent one again
 * revokes the whole chain. Two rules follow:
 * - **Single flight.** Everything runs under the user's connection lock ([HardcoverLinker.withUserLock]),
 *   so callers racing near expiry produce exactly one refresh; the ones that waited re-read the row and
 *   find the fresh token. A disconnect holds the same lock, so it can't delete the row mid-refresh and
 *   leave the rotated pair live on Hardcover.
 * - **Rotate, then commit, then use.** The rotated pair is saved BEFORE the new access token is
 *   returned. A crash after the refresh but before the save would lose the only live refresh token.
 *
 * A refused refresh marks the connection broken and tells [linker], so every open client sees it.
 */
class HardcoverTokenProvider(
    private val oauth: HardcoverOAuthClient,
    private val store: HardcoverConnectionStore,
    private val linker: HardcoverLinker,
    private val clock: Clock = Clock.System,
) {
    /** A valid access token for [userId], refreshing (once, however many callers) when it's near expiry. */
    suspend fun accessToken(userId: String): TokenLookup =
        linker.withUserLock(userId) {
            when (val stored = store.connectionFor(userId)) {
                null -> TokenLookup.NotConnected
                is StoredConnection.Broken -> TokenLookup.Broken(stored.reason)
                is StoredConnection.Healthy -> freshToken(userId, stored.credentials)
            }
        }

    private suspend fun freshToken(
        userId: String,
        credentials: StoredCredentials,
    ): TokenLookup {
        val now = clock.now().toEpochMilliseconds()
        if (credentials.accessExpiresAt - now > REFRESH_MARGIN_MS) return TokenLookup.Valid(credentials.accessToken)
        return when (val refreshed = oauth.refresh(credentials.refreshToken)) {
            is RefreshResult.Granted -> {
                store.saveRotated(userId, refreshed.tokens)
                TokenLookup.Valid(refreshed.tokens.accessToken)
            }

            RefreshResult.InvalidGrant -> {
                store.markBroken(userId, HardcoverBrokenReason.REVOKED)
                linker.onBroken(userId, HardcoverBrokenReason.REVOKED)
                TokenLookup.Broken(HardcoverBrokenReason.REVOKED)
            }

            is RefreshResult.Unavailable -> {
                if (credentials.accessExpiresAt > now) {
                    TokenLookup.Valid(credentials.accessToken)
                } else {
                    TokenLookup.Unavailable
                }
            }
        }
    }
}
