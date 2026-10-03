@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import kotlinx.coroutines.flow.Flow

/** Listener ratings (read from Room, written offline-first) and outside ratings (server-written, read-only). */
interface BookRatingRepository {
    /** Every live rating of [bookId]. */
    fun observeForBook(bookId: String): Flow<List<ListenerRating>>

    /** Each rated book's listener average, keyed by book id. */
    fun observeAverages(): Flow<Map<String, ListenerAverage>>

    /** Rate [bookId] as the signed-in listener. [halfStars] must be 2..10; [note] is trimmed, and blank means none. */
    suspend fun rate(
        bookId: String,
        halfStars: Int,
        note: String?,
    ): AppResult<Unit>

    /** Remove the signed-in listener's rating of [bookId]. */
    suspend fun clear(bookId: String): AppResult<Unit>

    /** Every enabled, known-source outside rating of [bookId], highest rating count first. */
    fun observeExternalForBook(bookId: String): Flow<List<ExternalRating>>

    /**
     * Each book's ListenUp score, keyed by book id: every enabled known-source outside rating plus
     * this server's listeners, calibrated over the whole library. Server-wide — the same for every
     * member. The library's Rating sort reads this.
     */
    fun observeCombinedScores(): Flow<Map<String, CombinedScore>>

    /** [bookId]'s entry in [observeCombinedScores] — the headline, so it always agrees with the sort. */
    fun observeCombinedScore(bookId: String): Flow<CombinedScore?>

    /**
     * Re-fetch every enabled outside source for [bookId] now — admin only. An online RPC, not the
     * outbox: there is nothing to queue offline, since only the server can reach an outside catalog.
     */
    suspend fun refreshExternal(bookId: String): AppResult<Unit>

    /**
     * Tell the server Book Detail opened [bookId], so it fetches a missing or stale Hardcover rating in
     * the background (#1542), and follow that fetch: true while it runs, then false when it ends,
     * fails, or the stream drops. Emits nothing when no fetch is needed. The rating itself arrives
     * through sync; this only says whether one may be on its way.
     */
    fun observeExternalCheck(bookId: String): Flow<Boolean>
}
