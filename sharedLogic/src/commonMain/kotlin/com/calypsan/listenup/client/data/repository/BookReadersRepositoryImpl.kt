@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.ActiveSessionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Implementation of [BookReadersRepository] backed entirely by Room observation.
 *
 * Combines two reactive Room queries:
 * 1. [ActiveSessionDao.observeForBook] — SSE-maintained table of who is currently listening.
 *    Rows are deleted by the server's P3-B completion cascade when a user finishes the book,
 *    so this table is always current without any manual refresh.
 * 2. [PlaybackPositionDao.observeFinishedForBook] — current user's finished position for
 *    the book. [PlaybackPositionEntity] stores only the current user's position (one row
 *    per book, no userId column), so this emits at most one result.
 *
 * No debounce, no REST refresh, no cache layer. Room handles change coalescing.
 */
class BookReadersRepositoryImpl(
    private val activeSessionDao: ActiveSessionDao,
    private val playbackPositionDao: PlaybackPositionDao,
    private val authSession: AuthSession,
) : BookReadersRepository {
    override fun observeReadersFor(bookId: String): Flow<BookReaders> =
        flow { emit(authSession.getUserId()) }.flatMapLatest { currentUserId ->
            combine(
                activeSessionDao.observeForBook(bookId),
                playbackPositionDao.observeFinishedForBook(BookId(bookId)),
            ) { activeSessions, finishedPosition ->
                val currentlyListening =
                    activeSessions
                        .filter { it.userId != currentUserId }
                        .map { Reader(userId = it.userId, displayName = it.displayName) }

                val completedBy =
                    buildList {
                        if (finishedPosition != null) {
                            // The current user finished the book — surface them in completedBy.
                            // Display name comes from AuthSession user profile; fall back to "You"
                            // since this is the current user and we don't need a separate lookup.
                            add(
                                Reader(
                                    userId = currentUserId ?: "",
                                    displayName = "You",
                                ),
                            )
                        }
                    }

                BookReaders(
                    currentlyListening = currentlyListening,
                    completedBy = completedBy,
                    totalCompletions = completedBy.size,
                )
            }
        }
}
