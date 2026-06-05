package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.social.BookReader
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * Read-only social presence: who is listening right now, and who is reading a given book.
 *
 * Every result is filtered through the server's `BookAccessPolicy` for the calling viewer —
 * a session for a book the caller cannot access is never returned, and an inaccessible book's
 * reader list returns [com.calypsan.listenup.api.error.SocialError.NotFound] (never revealing
 * the book exists).
 */
@Rpc
interface SocialService {
    /** Other users' live sessions on books the caller can access (caller excluded). */
    suspend fun currentlyListening(): AppResult<List<CurrentlyListeningSession>>

    /** Other users currently reading [bookId]; `NotFound` if the caller cannot access it. */
    suspend fun bookReaders(bookId: BookId): AppResult<List<BookReader>>
}
