package com.calypsan.listenup.client.voice

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.SearchFacets
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// ========== Fake Search Repository ==========

/**
 * Fake implementation of SearchRepository for testing.
 *
 * Configurable search results and error simulation for testing
 * different resolution scenarios including error paths.
 */
class FakeSearchRepository : SearchRepository {
    var searchResults: List<SearchHit> = emptyList()
    var lastQuery: String? = null
    var lastTypes: List<SearchHitType>? = null

    /** Set to non-null to make search throw this exception */
    var exceptionToThrow: Exception? = null

    /**
     * Convenience method to set search results from vararg.
     */
    fun setResults(vararg hits: SearchHit) {
        searchResults = hits.toList()
        exceptionToThrow = null
    }

    /**
     * Configure the repository to throw an exception on next search.
     */
    fun setError(exception: Exception) {
        exceptionToThrow = exception
    }

    override suspend fun search(
        query: String,
        types: List<SearchHitType>?,
        genres: List<String>?,
        genrePath: String?,
        limit: Int,
    ): SearchResult {
        exceptionToThrow?.let { throw it }

        lastQuery = query
        lastTypes = types
        return SearchResult(
            query = query,
            total = searchResults.size,
            tookMs = 10,
            hits = searchResults.take(limit),
            facets = SearchFacets(),
            isOfflineResult = false,
        )
    }
}

// ========== Fake Home Repository ==========

/**
 * Fake implementation of HomeRepository for testing.
 *
 * Provides configurable continue listening data for testing resume scenarios.
 */
class FakeHomeRepository : HomeRepository {
    var continueListeningBooks: List<ContinueListeningBook> = emptyList()

    /**
     * Set the continue listening book (most recently played).
     */
    fun setContinueListening(vararg books: ContinueListeningBook) {
        continueListeningBooks = books.toList()
    }

    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> =
        AppResult.Success(continueListeningBooks.take(limit))

    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> =
        MutableStateFlow(
            continueListeningBooks.take(limit).map { book ->
                ContinueListeningItem.Ready(bookId = book.bookId, book = book)
            },
        )
}

// ========== Fake Book Repository ==========

/**
 * Fake implementation of BookRepository for testing.
 *
 * Stores books in memory for testing book lookup scenarios.
 */
class FakeBookRepository : BookRepository {
    private val books = mutableMapOf<String, BookListItem>()
    private val chapters = mutableMapOf<String, List<Chapter>>()

    fun addBook(book: BookListItem) {
        books[book.id.value] = book
    }

    fun addChapters(
        bookId: String,
        bookChapters: List<Chapter>,
    ) {
        chapters[bookId] = bookChapters
    }

    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = chapters[bookId] ?: emptyList()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = MutableStateFlow(chapters[bookId].orEmpty())

    override fun observeIsBookLive(id: String): Flow<Boolean> = MutableStateFlow(true)

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> = MutableStateFlow(emptyList())

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> = MutableStateFlow(emptyList())

    override fun observeBookListItems(): Flow<List<BookListItem>> = MutableStateFlow(books.values.toList())

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> =
        MutableStateFlow(ids.mapNotNull { books[it] })

    override suspend fun getBookListItem(id: String): BookListItem? = books[id]

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> = ids.mapNotNull { books[it] }

    override fun observeBookDetail(id: String): Flow<BookDetail?> = MutableStateFlow(null)

    override fun search(query: String): Flow<List<BookListItem>> = MutableStateFlow(emptyList())

    override suspend fun getBookDetail(id: String): BookDetail? = null
}

// ========== Fake Series Repository ==========

/**
 * Fake implementation of SeriesRepository for testing.
 *
 * Stores series data in memory for testing series navigation scenarios.
 */
class FakeSeriesRepository : SeriesRepository {
    private val seriesMap = mutableMapOf<String, Series>()
    private val seriesBooks = mutableMapOf<String, List<String>>()
    private val seriesWithBooksMap = mutableMapOf<String, SeriesWithBooks>()
    private val bookSeriesMap = mutableMapOf<String, String>()

    fun addSeries(series: Series) {
        seriesMap[series.id.value] = series
    }

    /**
     * Add a series with its book IDs in one call.
     */
    fun addSeries(
        series: Series,
        bookIds: List<String>,
    ) {
        seriesMap[series.id.value] = series
        seriesBooks[series.id.value] = bookIds
    }

    fun setBookIdsForSeries(
        seriesId: String,
        bookIds: List<String>,
    ) {
        seriesBooks[seriesId] = bookIds
    }

    fun setSeriesWithBooks(seriesWithBooks: SeriesWithBooks) {
        seriesWithBooksMap[seriesWithBooks.series.id.value] = seriesWithBooks
    }

    fun setSeriesForBook(
        bookId: String,
        seriesId: String,
    ) {
        bookSeriesMap[bookId] = seriesId
    }

    override fun observeAll(): Flow<List<Series>> = MutableStateFlow(seriesMap.values.toList())

    override fun observeById(id: String): Flow<Series?> = MutableStateFlow(seriesMap[id])

    override suspend fun getById(id: String): Series? = seriesMap[id]

    override fun observeByBookId(bookId: String): Flow<Series?> {
        val seriesId = bookSeriesMap[bookId]
        return MutableStateFlow(seriesId?.let { seriesMap[it] })
    }

    override suspend fun getBookIdsForSeries(seriesId: String): List<String> = seriesBooks[seriesId] ?: emptyList()

    override fun observeBookIdsForSeries(seriesId: String): Flow<List<String>> =
        MutableStateFlow(seriesBooks[seriesId] ?: emptyList())

    override fun observeAllWithBooks(): Flow<List<SeriesWithBooks>> =
        MutableStateFlow(seriesWithBooksMap.values.toList())

    override fun observeSeriesWithBooks(seriesId: String): Flow<SeriesWithBooks?> =
        MutableStateFlow(seriesWithBooksMap[seriesId])

    override suspend fun searchSeries(
        query: String,
        limit: Int,
    ): SeriesSearchResponse =
        SeriesSearchResponse(
            series = emptyList(),
            isOfflineResult = false,
            tookMs = 5,
        )
}

// ========== Test Data Builders ==========

/**
 * Create a test book with sensible defaults.
 */
fun testBook(
    id: String = "book-1",
    title: String = "Test Book",
    authorName: String = "Test Author",
    narratorName: String = "Test Narrator",
    seriesId: String? = null,
    seriesName: String? = null,
    seriesSequence: String? = null,
    series: List<com.calypsan.listenup.client.domain.model.BookSeries> = emptyList(),
    duration: Long = 3_600_000, // 1 hour
): BookListItem {
    // Use explicit series list if provided, otherwise build from individual params
    val bookSeries =
        series.ifEmpty {
            if (seriesId != null && seriesName != null) {
                listOf(
                    com.calypsan.listenup.client.domain.model.BookSeries(
                        seriesId = seriesId,
                        seriesName = seriesName,
                        sequence = seriesSequence,
                    ),
                )
            } else {
                emptyList()
            }
        }

    return BookListItem(
        id = BookId(id),
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        authors = listOf(BookContributor(id = "author-1", name = authorName, roles = listOf("author"))),
        narrators = listOf(BookContributor(id = "narrator-1", name = narratorName, roles = listOf("narrator"))),
        duration = duration,
        coverPath = null,
        addedAt = Timestamp(1000),
        updatedAt = Timestamp(1000),
        series = bookSeries,
    )
}

/**
 * Create a test BookSeries with sensible defaults.
 */
fun testBookSeries(
    seriesId: String,
    seriesName: String,
    sequence: String? = null,
) = com.calypsan.listenup.client.domain.model.BookSeries(
    seriesId = seriesId,
    seriesName = seriesName,
    sequence = sequence,
)

/**
 * Create a test series with sensible defaults.
 */
fun testSeries(
    id: String = "series-1",
    name: String = "Test Series",
): Series =
    Series(
        id = SeriesId(id),
        name = name,
        createdAt = Timestamp(1000),
    )

/**
 * Create a test continue listening book with sensible defaults.
 */
fun testContinueListeningBook(
    bookId: String = "book-1",
    title: String = "Test Book",
    authorNames: String = "Test Author",
    progress: Float = 0.5f,
    currentPositionMs: Long = 1_800_000, // 30 minutes
    totalDurationMs: Long = 3_600_000, // 1 hour
    lastPlayedAt: String = "2024-01-15T10:30:00Z",
): ContinueListeningBook =
    ContinueListeningBook(
        bookId = bookId,
        title = title,
        authorNames = authorNames,
        coverPath = null,
        progress = progress,
        currentPositionMs = currentPositionMs,
        totalDurationMs = totalDurationMs,
        lastPlayedAt = lastPlayedAt,
    )

/**
 * Create a test search hit with sensible defaults.
 */
fun testSearchHit(
    id: String = "book-1",
    type: SearchHitType = SearchHitType.BOOK,
    name: String = "Test Book",
    author: String? = "Test Author",
    score: Float = 1.0f,
): SearchHit =
    SearchHit(
        id = id,
        type = type,
        name = name,
        author = author,
        score = score,
    )
