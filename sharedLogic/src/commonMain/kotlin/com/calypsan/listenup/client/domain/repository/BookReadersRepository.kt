package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.readers.BookReaders
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the Book Detail Readers section.
 *
 * Provides a purely Room-observed, offline-first view of who is listening to or has
 * completed a book. There is no REST fallback — data arrives via SSE events that write
 * into `active_sessions` and `playback_positions`, and this repository combines those two
 * Room flows reactively.
 *
 * The 2-second debounce, REST refresh, and `reader_sessions_cache` layer from the
 * previous [SessionRepository] design are deliberately absent. Room handles change
 * coalescing; the server's completion cascade (deleting `active_sessions` rows when
 * `playback_positions.isFinished` flips) means transitions are immediate and
 * correct without manual refresh.
 */
interface BookReadersRepository {
    /**
     * Observe the readers state for a specific book.
     *
     * The returned [Flow] emits on every change to the underlying Room tables
     * (`active_sessions` and `playback_positions`). The current user is excluded
     * from [BookReaders.currentlyListening]; the current user's own completion
     * status drives [BookReaders.completedBy].
     *
     * @param bookId The book to observe readers for.
     * @return Flow emitting [BookReaders] on every relevant Room change.
     */
    fun observeReadersFor(bookId: String): Flow<BookReaders>
}
