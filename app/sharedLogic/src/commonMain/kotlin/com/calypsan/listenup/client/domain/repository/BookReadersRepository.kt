package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the Book Detail Readers section.
 *
 * Backed by the `SocialService.bookReadership` RPC, which is ACL-filtered and *includes* the caller
 * server-side. The repository fetches on first subscribe and re-fetches on every presence ping (the
 * server's `ActiveSessionsChanged` nudge or a firehose reconnect). On RPC failure the cached
 * readership is left intact and continues to render (Never-Stranded — possibly stale, never blank);
 * the next ping recovers.
 */
interface BookReadersRepository {
    /**
     * Observe the readers state for a specific book.
     *
     * The returned [Flow] emits the current reader list on subscribe and re-emits on every
     * presence ping. The current user is included and flagged via [Reader.isYou].
     *
     * @param bookId The book to observe readers for.
     * @return Flow emitting [BookReaders] on subscribe and on every presence refresh.
     */
    fun observeReadersFor(bookId: String): Flow<BookReaders>
}
