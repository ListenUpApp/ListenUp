package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.StateFlow

/**
 * A live playhead and the book it belongs to, set together: a position is never read against another book's id.
 *
 * @property bookId The book the engine holds and reported [positionMs] for.
 * @property positionMs Book-relative playhead, in ms.
 */
data class BookPlayhead(
    val bookId: BookId,
    val positionMs: Long,
)

/**
 * The loaded book's playhead, live: what the mini player and Now Playing show, ahead of the saved position (which is
 * written on a timer). Book Detail reads it for its own book only, so its current chapter keeps up with the player.
 */
interface LoadedBookPlayhead {
    /**
     * The engine's playhead while it holds the session's book, or null: before it does, between books, and until the
     * engine reports a position for the book it just took. Never one book's position under another book's id.
     */
    val playhead: StateFlow<BookPlayhead?>
}
