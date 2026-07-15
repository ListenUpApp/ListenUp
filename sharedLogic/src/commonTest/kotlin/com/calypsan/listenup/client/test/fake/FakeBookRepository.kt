package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [BookRepository]. Backed by a [MutableStateFlow] of the book list
 * so `observeBookListItems()` emits on every `setBooks` call; chapters live in a parallel map.
 *
 * [refreshCount] is tracked for tests that care about the refresh side effect.
 * Discovery flows are derived on every emission and take the first [limit] items
 * after the appropriate ordering — deterministic for tests and close enough to
 * production for seam verification.
 */
class FakeBookRepository(
    initialBooks: List<BookListItem> = emptyList(),
    initialChapters: Map<String, List<Chapter>> = emptyMap(),
) : BookRepository {
    private val books = MutableStateFlow(initialBooks)
    private val chaptersByBookId = initialChapters.toMutableMap()
    private var _refreshCount = 0

    /** Number of times [refreshBooks] was called. */
    val refreshCount: Int get() = _refreshCount

    override suspend fun refreshBooks(): AppResult<Unit> {
        _refreshCount++
        return AppResult.Success(Unit)
    }

    override suspend fun getChapters(bookId: String): List<Chapter> = chaptersByBookId[bookId].orEmpty()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = flowOf(chaptersByBookId[bookId].orEmpty())

    /** A book is "live" in this fake when it is present in the current [books] list. */
    override fun observeIsBookLive(id: String): Flow<Boolean> =
        books.asStateFlow().map { list -> list.any { it.id == BookId(id) } }

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> =
        books.asStateFlow().map { list -> list.take(limit).map(::toDiscoveryBook) }

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> =
        books.asStateFlow().map { list ->
            list.sortedByDescending { it.addedAt.epochMillis }.take(limit).map(::toDiscoveryBook)
        }

    override fun observeBookListItems(): Flow<List<BookListItem>> = books.asStateFlow()

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> {
        val wanted = ids.map(::BookId).toSet()
        return books.asStateFlow().map { list -> list.filter { it.id in wanted } }
    }

    override suspend fun getBookListItem(id: String): BookListItem? = books.value.firstOrNull { it.id == BookId(id) }

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> {
        val wanted = ids.map(::BookId).toSet()
        return books.value.filter { it.id in wanted }
    }

    override fun observeBookDetail(id: String): Flow<BookDetail?> = flowOf(null)

    override fun search(query: String): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookDetail(id: String): BookDetail? = null

    /** Test helper: replace the book list, emitting to all observers. */
    fun setBooks(list: List<BookListItem>) {
        books.value = list
    }

    /** Test helper: set chapters for [bookId]. */
    fun setChapters(
        bookId: String,
        chapters: List<Chapter>,
    ) {
        chaptersByBookId[bookId] = chapters
    }

    private fun toDiscoveryBook(book: BookListItem): DiscoveryBook =
        DiscoveryBook(
            id = book.id.value,
            title = book.title,
            authorName = book.authors.firstOrNull()?.name,
            coverPath = book.coverPath,
            createdAt = book.addedAt.epochMillis,
        )
}
