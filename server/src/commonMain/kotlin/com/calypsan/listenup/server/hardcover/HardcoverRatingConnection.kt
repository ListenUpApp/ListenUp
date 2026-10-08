package com.calypsan.listenup.server.hardcover

/** The Hardcover account a rating lookup borrows: who on this server, and their name on Hardcover. */
data class PickedHardcoverConnection(
    val userId: String,
    val hardcoverUsername: String,
)

/**
 * Chooses whose Hardcover connection answers a book's rating lookup. Ratings are server-wide, not
 * per listener, so any connected account will do; the server's ROOT and ADMIN accounts are preferred
 * over members' because they are the ones accountable for the server's outbound traffic.
 */
class HardcoverRatingConnection(
    private val store: HardcoverConnectionStore,
    private val tokens: HardcoverTokenProvider,
) {
    /** The first connection in preference order, or null when nobody is connected. */
    suspend fun pick(): PickedHardcoverConnection? {
        val userId = store.healthyUserIds().firstOrNull() ?: return null
        val username =
            when (val stored = store.connectionFor(userId)) {
                is StoredConnection.Healthy -> stored.hardcoverUsername
                is StoredConnection.Broken -> stored.hardcoverUsername
                null -> return null
            }
        return PickedHardcoverConnection(userId, username)
    }

    /**
     * The first usable token, trying each connection in preference order. When none is usable, the
     * most informative lookup: a broken connection outranks an unreachable Hardcover, which outranks
     * nobody being connected.
     */
    suspend fun token(): TokenLookup {
        var best: TokenLookup = TokenLookup.NotConnected
        for (userId in store.healthyUserIds()) {
            val lookup = tokens.accessToken(userId)
            if (lookup is TokenLookup.Valid) return lookup
            if (lookup.informativeness() > best.informativeness()) best = lookup
        }
        return best
    }

    private fun TokenLookup.informativeness(): Int =
        when (this) {
            is TokenLookup.Broken -> 2
            TokenLookup.Unavailable -> 1
            is TokenLookup.Valid, TokenLookup.NotConnected -> 0
        }
}
