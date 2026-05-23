package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.history.BookListeningHistory
import kotlinx.coroutines.flow.Flow

/**
 * Reactive per-book listening history, day-grouped and scoped to the currently
 * authenticated user. Emits [BookListeningHistory] with an empty [BookListeningHistory.daily]
 * list when no user is signed in or no events exist for the given book.
 */
interface BookListeningHistoryRepository {
    /**
     * Observe the day-grouped listening history for [bookId], newest day first.
     *
     * Scoped to the current user; switching accounts causes an immediate re-emission
     * with only that user's events. Re-emits whenever new events are recorded or
     * tombstoned for this book.
     */
    fun observeFor(bookId: String): Flow<BookListeningHistory>
}
