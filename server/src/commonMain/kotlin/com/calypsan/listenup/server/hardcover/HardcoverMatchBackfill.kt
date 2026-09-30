package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val log = loggerFor<HardcoverMatchBackfill>()

/**
 * The slow background match pass (spec B4): after a user connects, every book they have started gets
 * a link — a confident match, or NEEDS_MATCH — before its first push needs one, and before the pull
 * (B3) needs to resolve it. Paced by the shared [HardcoverRateLimiter] inside [HardcoverBookMatcher],
 * one book at a time. The first failure ends the pass: the lazy match on the next push, or the next
 * connect, continues from wherever it stopped. Never re-tries a NEEDS_MATCH book — only the user can
 * settle one of those.
 */
class HardcoverMatchBackfill(
    private val links: HardcoverBookLinkStore,
    private val matcher: HardcoverBookMatcher,
    private val tokens: HardcoverTokenProvider,
    private val identities: HardcoverBookIdentities,
    private val scope: CoroutineScope,
) {
    /** Walks [userId]'s started, unmatched books in id order until none are left or Hardcover falters. */
    suspend fun run(userId: String) {
        var after = ""
        while (true) {
            val page = links.unlinkedStartedBooks(userId, after, BATCH_LIMIT)
            if (page.isEmpty()) return
            for (bookId in page) {
                val token = (tokens.accessToken(userId) as? TokenLookup.Valid)?.accessToken ?: return
                // A book gone from the library stays unlinked; the keyset cursor moves past it.
                val identity = identities.identityOf(bookId) ?: continue
                val match = matcher.match(token, identity).valueOr { return }
                links.recordAutomaticMatch(userId, bookId, match)
            }
            after = page.last()
        }
    }

    /** Launches [run] for [userId] and returns at once. */
    fun trigger(userId: String): Job =
        scope.launch {
            runCatchingCancellable {
                run(
                    userId,
                )
            }.onFailure { log.warn(it) { "hardcover match pass failed user=$userId" } }
        }

    private companion object {
        const val BATCH_LIMIT = 50L
    }
}
