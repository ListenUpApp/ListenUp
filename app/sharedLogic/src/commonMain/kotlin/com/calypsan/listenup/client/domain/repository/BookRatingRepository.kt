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

    /** Each book's combined outside score, over enabled known-source rows only, keyed by book id. */
    fun observeCombinedScores(): Flow<Map<String, CombinedScore>>

    /**
     * Re-fetch every enabled outside source for [bookId] now — admin only. An online RPC, not the
     * outbox: there is nothing to queue offline, since only the server can reach an outside catalog.
     */
    suspend fun refreshExternal(bookId: String): AppResult<Unit>
}
