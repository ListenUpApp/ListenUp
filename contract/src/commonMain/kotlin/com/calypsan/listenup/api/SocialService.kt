package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * Read-only social presence: who is listening right now, and the full readership of a given book.
 *
 * Every result is filtered through the server's `BookAccessPolicy` for the calling viewer —
 * a session for a book the caller cannot access is never returned, and an inaccessible book's
 * readership returns [com.calypsan.listenup.api.error.SocialError.NotFound] (never revealing
 * the book exists).
 */
@Rpc
interface SocialService {
    /** Other users' live sessions on books the caller can access (caller excluded). */
    suspend fun currentlyListening(): AppResult<List<CurrentlyListeningSession>>

    /**
     * The full readership of [bookId] for the caller: each user (including the caller) with their
     * current progress% (if reading) and their newest-first list of finish dates. `NotFound` if the
     * caller cannot access the book.
     */
    suspend fun bookReadership(bookId: BookId): AppResult<BookReadership>
}
