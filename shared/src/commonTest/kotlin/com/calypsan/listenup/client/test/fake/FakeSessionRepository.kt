package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.BookReadersResult
import com.calypsan.listenup.client.domain.model.ReaderInfo
import com.calypsan.listenup.client.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import com.calypsan.listenup.core.Success

/**
 * In-memory fake of [SessionRepository]. Backed by a [MutableStateFlow] of
 * bookId → [BookReadersResult]; unknown book IDs observe the [empty] sentinel.
 *
 * Tracks [refreshCounts] for tests that want to verify the refresh side effect
 * (this is a call-counting concession for the one method with no persistent state).
 *
 * Bug 1 (Readers section) lives on this seam. Fakes reproduce the read-after-write
 * semantics that a Mokkery mock cannot.
 */
class FakeSessionRepository(
    initialReaders: Map<String, BookReadersResult> = emptyMap(),
) : SessionRepository {
    private val state = MutableStateFlow(initialReaders)
    private val _refreshCounts = mutableMapOf<String, Int>()

    /** Map of bookId → number of times [refreshBookReaders] was called. */
    val refreshCounts: Map<String, Int> get() = _refreshCounts.toMap()

    override fun observeBookReaders(bookId: String): Flow<BookReadersResult> =
        state.asStateFlow().map { it[bookId] ?: empty }

    override suspend fun refreshBookReaders(bookId: String) {
        _refreshCounts[bookId] = (_refreshCounts[bookId] ?: 0) + 1
    }

    /** Test helper: update the readers for [bookId], emitting to all observers. */
    fun setReaders(
        bookId: String,
        readers: BookReadersResult,
    ) {
        state.value = state.value + (bookId to readers)
    }

    private companion object {
        private val empty =
            BookReadersResult(
                yourSessions = emptyList(),
                otherReaders = emptyList(),
                totalReaders = 0,
                totalCompletions = 0,
            )
    }
}
