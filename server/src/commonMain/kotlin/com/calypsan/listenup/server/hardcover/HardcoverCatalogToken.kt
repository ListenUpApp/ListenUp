package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.logging.loggerFor

private val logger = loggerFor<HardcoverCatalogToken>()

/**
 * Which token reads Hardcover's catalogue (#1542), for metadata and ratings alike: the admin's API token
 * while it is stored and Hardcover hasn't rejected it, otherwise a borrowed connected account, as
 * ratings always did ([HardcoverRatingConnection]: ROOT, then ADMIN, then members).
 *
 * Only catalogue reads come through here. Nobody's shelves or reads ever use the API token: those use
 * each person's own connection.
 */
class HardcoverCatalogToken(
    private val apiTokens: HardcoverApiTokenStore,
    private val connections: HardcoverRatingConnection,
) {
    /** The admin's API token when it is usable, else null. */
    suspend fun apiToken(): String? = apiTokens.usable()?.token

    /** Hardcover answered 401 for [token]: mark it, so reads fall back until the admin replaces it. */
    suspend fun markApiTokenRejected(token: String) {
        if (apiTokens.markRejected(token)) {
            logger.warn { "Hardcover rejected the admin API token; catalogue reads use connected accounts until it is replaced" }
        }
    }

    /** A borrowed connected account's token, exactly as ratings have always looked one up. */
    suspend fun connectionToken(): TokenLookup = connections.token()

    /** Whose Hardcover account catalogue reads use right now, or null when none can. */
    suspend fun accountName(): String? = apiTokens.usable()?.username ?: connections.pick()?.hardcoverUsername

    /** Whether any token can read the catalogue: a usable API token, or a healthy connected account. */
    suspend fun isAvailable(): Boolean = apiTokens.usable() != null || connections.pick() != null

    /**
     * Runs [call] with the best token. A 401 on the API token marks it rejected and the same call runs
     * once more with a connected account. Null when no token can read at all; a broken or unreachable
     * connection is a [HardcoverCall.Failed] the caller reports.
     */
    suspend fun <T> read(call: suspend (accessToken: String) -> HardcoverCall<T>): HardcoverCall<T>? {
        apiToken()?.let { admin ->
            val answer = call(admin)
            if (answer != HardcoverCall.Unauthorized) return answer
            markApiTokenRejected(admin)
        }
        return when (val lookup = connectionToken()) {
            is TokenLookup.Valid -> call(lookup.accessToken)
            TokenLookup.NotConnected -> null
            is TokenLookup.Broken -> HardcoverCall.Failed("catalogue: connection broken (${lookup.reason})")
            TokenLookup.Unavailable -> HardcoverCall.Failed("catalogue: token refresh unavailable")
        }
    }
}
