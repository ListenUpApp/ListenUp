package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.BookMatchRecord
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.TierLabels
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.DiscoveryPick
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A book mirror holding [details] and nothing else.
 *
 * The shared player reads two things from a `BookRepository`: `observeBookDetail`, for the loaded
 * book's identity, and `observeIsBookLive`, to keep it loaded while it is in the library. Those are
 * the members with real behaviour here. The others answer empty rather than throwing: a spec that
 * accidentally reaches one should fail on the assertion it was actually making, not on a stub.
 *
 * Not a mock. `mokkery` would let a defaulted member appear on the interface and silently answer
 * null on every stub site at once; a hand-written fake breaks at compile time instead, which is
 * where an interface change should be noticed.
 */
internal class FakeBookRepository(
    private val details: Map<String, BookDetail> = emptyMap(),
) : BookRepository {
    override fun observeBookDetail(id: String): Flow<BookDetail?> = flowOf(details[id])

    override suspend fun getBookDetail(id: String): BookDetail? = details[id]

    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = emptyList()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeBookTierLabels(bookId: String): Flow<TierLabels> = flowOf(TierLabels.None)

    override fun observeMatchRecord(bookId: String): Flow<BookMatchRecord?> = flowOf(null)

    // A book this mirror holds is live, so the shared player's liveness watch keeps it loaded.
    override fun observeIsBookLive(id: String): Flow<Boolean> = flowOf(id in details)

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryPick>> = flowOf(emptyList())

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeBookListItems(): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookListItem(id: String): BookListItem? = null

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> = emptyList()

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> = flowOf(emptyList())

    override fun search(query: String): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun deleteBook(id: BookId): AppResult<Unit> = AppResult.Success(Unit)
}
