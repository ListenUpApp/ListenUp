@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import kotlinx.coroutines.flow.Flow

/** Listener ratings: read from Room, written offline-first. */
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
}
