@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ActiveSessionDao
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of [BookReadersRepository] backed entirely by Room observation.
 *
 * Observes [ActiveSessionDao.observeForBook] — SSE-maintained table of who is currently
 * listening. Rows are deleted by the server's P3-B completion cascade when a user finishes
 * the book, so this table is always current without any manual refresh.
 *
 * The current user is filtered out so they don't appear in the list while listening to
 * their own book.
 *
 * No debounce, no REST refresh, no cache layer. Room handles change coalescing.
 */
class BookReadersRepositoryImpl(
    private val activeSessionDao: ActiveSessionDao,
    private val authSession: AuthSession,
) : BookReadersRepository {
    override fun observeReadersFor(bookId: String): Flow<BookReaders> =
        flow { emit(authSession.getUserId()) }.flatMapLatest { currentUserId ->
            activeSessionDao.observeForBook(bookId).map { activeSessions ->
                val currentlyListening =
                    activeSessions
                        .filter { it.userId != currentUserId }
                        .map { Reader(userId = it.userId, displayName = it.displayName) }
                BookReaders(currentlyListening = currentlyListening)
            }
        }
}
