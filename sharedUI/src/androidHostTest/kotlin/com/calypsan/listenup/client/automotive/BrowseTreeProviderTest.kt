package com.calypsan.listenup.client.automotive

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ContributorWithAliases
import com.calypsan.listenup.client.data.local.db.DiscoveryBookWithAuthor
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [BrowseTreeProvider.getChildren].
 *
 * [MediaItem.Builder] internally uses Android's [android.net.Uri] and is only available
 * on a real device or via Robolectric. This test therefore uses [RobolectricTestRunner]
 * + JUnit4, consistent with [DeepLinkParserTest] and [PlaybackErrorHandlerTest].
 * The `junit-vintage-engine` on the classpath keeps these discoverable alongside
 * Kotest specs in `androidHostTest`.
 *
 * The DAO and repository interfaces are satisfied by in-memory fakes that hold data
 * in simple lists. This avoids the complexity of a real Room database while exercising
 * all routing branches of [BrowseTreeProvider.getChildren] without modifying `:shared`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseTreeProviderTest {
    // ──────────────────────────────────────────────────────────────────────────────
    // ROOT branch
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren ROOT returns Library folder when no resume book`(): Unit =
        runBlocking {
            val provider = makeProvider(continueListeningBooks = emptyList())
            val children = provider.getChildren(BrowseTree.ROOT)
            // No resume item when there are no in-progress books
            children shouldHaveSize 1
            children[0].mediaId shouldBe BrowseTree.LIBRARY
        }

    @Test
    fun `getChildren ROOT includes Resume item as first child when book is in progress`(): Unit =
        runBlocking {
            val book = makeContinueListeningBook(bookId = "book-resume")
            val provider = makeProvider(continueListeningBooks = listOf(book))
            val children = provider.getChildren(BrowseTree.ROOT)
            // Resume item first, then Library
            children shouldHaveSize 2
            children[0].mediaId shouldBe BrowseTree.bookId("book-resume")
            children[1].mediaId shouldBe BrowseTree.LIBRARY
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // LIBRARY branch
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY returns exactly four static sub-folders`(): Unit =
        runBlocking {
            val provider = makeProvider()
            val children = provider.getChildren(BrowseTree.LIBRARY)
            children shouldHaveSize 4
            children.map { it.mediaId } shouldBe
                listOf(
                    BrowseTree.LIBRARY_RECENT,
                    BrowseTree.LIBRARY_DOWNLOADED,
                    BrowseTree.LIBRARY_SERIES,
                    BrowseTree.LIBRARY_AUTHORS,
                )
        }

    @Test
    fun `getChildren LIBRARY sub-folders are all browsable and not playable`(): Unit =
        runBlocking {
            val provider = makeProvider()
            val children = provider.getChildren(BrowseTree.LIBRARY)
            children.forEach { item ->
                item.mediaMetadata.isBrowsable shouldBe true
                item.mediaMetadata.isPlayable shouldBe false
            }
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // LIBRARY_RECENT branch
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY_RECENT returns empty list when no books`(): Unit =
        runBlocking {
            val provider = makeProvider(continueListeningBooks = emptyList())
            provider.getChildren(BrowseTree.LIBRARY_RECENT).shouldBeEmpty()
        }

    @Test
    fun `getChildren LIBRARY_RECENT maps each book to a playable media item`(): Unit =
        runBlocking {
            val books =
                listOf(
                    makeContinueListeningBook("book-1", "Book One"),
                    makeContinueListeningBook("book-2", "Book Two"),
                )
            val provider = makeProvider(continueListeningBooks = books)
            val children = provider.getChildren(BrowseTree.LIBRARY_RECENT)
            children shouldHaveSize 2
            children[0].mediaId shouldBe BrowseTree.bookId("book-1")
            children[0].mediaMetadata.title.toString() shouldBe "Book One"
            children[0].mediaMetadata.isPlayable shouldBe true
            children[1].mediaId shouldBe BrowseTree.bookId("book-2")
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // LIBRARY_DOWNLOADED branch
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY_DOWNLOADED returns empty list when no books`(): Unit =
        runBlocking {
            val provider = makeProvider(allBooks = emptyList())
            provider.getChildren(BrowseTree.LIBRARY_DOWNLOADED).shouldBeEmpty()
        }

    @Test
    fun `getChildren LIBRARY_DOWNLOADED includes only books whose downloads are all COMPLETED`(): Unit =
        runBlocking {
            val bookDownloaded = makeBookEntity("book-dl", "Downloaded Book")
            val bookPending = makeBookEntity("book-pending", "Pending Book")
            val bookNoDownloads = makeBookEntity("book-none", "No Downloads")
            val downloads =
                mapOf(
                    "book-dl" to listOf(makeDownloadEntity("book-dl", "file-1", DownloadState.COMPLETED)),
                    "book-pending" to listOf(makeDownloadEntity("book-pending", "file-2", DownloadState.DOWNLOADING)),
                    "book-none" to emptyList(),
                )
            val provider =
                makeProvider(
                    allBooks = listOf(bookDownloaded, bookPending, bookNoDownloads),
                    downloadsByBookId = downloads,
                )
            val children = provider.getChildren(BrowseTree.LIBRARY_DOWNLOADED)
            children shouldHaveSize 1
            children[0].mediaId shouldBe BrowseTree.bookId("book-dl")
        }

    @Test
    fun `getChildren LIBRARY_DOWNLOADED excludes books with mixed download states`(): Unit =
        runBlocking {
            val book = makeBookEntity("book-mixed", "Mixed Download Book")
            val downloads =
                mapOf(
                    "book-mixed" to
                        listOf(
                            makeDownloadEntity("book-mixed", "file-1", DownloadState.COMPLETED),
                            makeDownloadEntity("book-mixed", "file-2", DownloadState.FAILED),
                        ),
                )
            val provider = makeProvider(allBooks = listOf(book), downloadsByBookId = downloads)
            provider.getChildren(BrowseTree.LIBRARY_DOWNLOADED).shouldBeEmpty()
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // LIBRARY_SERIES branch
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY_SERIES returns empty list when no series`(): Unit =
        runBlocking {
            val provider = makeProvider(allSeries = emptyList())
            provider.getChildren(BrowseTree.LIBRARY_SERIES).shouldBeEmpty()
        }

    @Test
    fun `getChildren LIBRARY_SERIES maps each series to a browsable item`(): Unit =
        runBlocking {
            val series =
                listOf(
                    makeSeriesEntity("series-1", "The Stormlight Archive"),
                    makeSeriesEntity("series-2", "Mistborn"),
                )
            val provider = makeProvider(allSeries = series)
            val children = provider.getChildren(BrowseTree.LIBRARY_SERIES)
            children shouldHaveSize 2
            children[0].mediaId shouldBe BrowseTree.seriesId("series-1")
            children[0].mediaMetadata.title.toString() shouldBe "The Stormlight Archive"
            children[0].mediaMetadata.isBrowsable shouldBe true
            children[1].mediaId shouldBe BrowseTree.seriesId("series-2")
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // LIBRARY_AUTHORS branch
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY_AUTHORS returns empty list when no contributors`(): Unit =
        runBlocking {
            val provider = makeProvider(allContributors = emptyList())
            provider.getChildren(BrowseTree.LIBRARY_AUTHORS).shouldBeEmpty()
        }

    @Test
    fun `getChildren LIBRARY_AUTHORS maps each contributor to a browsable item`(): Unit =
        runBlocking {
            val contributors =
                listOf(
                    makeContributorEntity("author-1", "Brandon Sanderson"),
                    makeContributorEntity("author-2", "Patrick Rothfuss"),
                )
            val provider = makeProvider(allContributors = contributors)
            val children = provider.getChildren(BrowseTree.LIBRARY_AUTHORS)
            children shouldHaveSize 2
            children[0].mediaId shouldBe BrowseTree.authorId("author-1")
            children[0].mediaMetadata.title.toString() shouldBe "Brandon Sanderson"
            children[0].mediaMetadata.isBrowsable shouldBe true
            children[1].mediaId shouldBe BrowseTree.authorId("author-2")
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // Dynamic: series books
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren series prefix returns books in that series`(): Unit =
        runBlocking {
            val book1 = makeBookEntity("book-sw1", "The Way of Kings")
            val book2 = makeBookEntity("book-sw2", "Words of Radiance")
            val series = makeSeriesEntity("series-sa", "The Stormlight Archive")
            val seriesWithBooks =
                SeriesWithBooks(
                    series = series,
                    books = listOf(book1, book2),
                    bookSequences = emptyList(),
                )
            val provider = makeProvider(seriesWithBooksById = mapOf("series-sa" to seriesWithBooks))
            val children = provider.getChildren(BrowseTree.seriesId("series-sa"))
            children shouldHaveSize 2
            children[0].mediaId shouldBe BrowseTree.bookId("book-sw1")
            children[0].mediaMetadata.isPlayable shouldBe true
        }

    @Test
    fun `getChildren series prefix returns empty list when series not found`(): Unit =
        runBlocking {
            val provider = makeProvider(seriesWithBooksById = emptyMap())
            provider.getChildren(BrowseTree.seriesId("series-nonexistent")).shouldBeEmpty()
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // Dynamic: author books
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren author prefix returns books by that author`(): Unit =
        runBlocking {
            val book = makeBookEntity("book-1", "The Name of the Wind")
            val author = makeContributorEntity("author-pr", "Patrick Rothfuss")
            val provider =
                makeProvider(
                    bookIdsForContributor = mapOf("author-pr" to listOf("book-1")),
                    contributorsById = mapOf("author-pr" to author),
                    booksById = mapOf("book-1" to book),
                )
            val children = provider.getChildren(BrowseTree.authorId("author-pr"))
            children shouldHaveSize 1
            children[0].mediaId shouldBe BrowseTree.bookId("book-1")
            children[0].mediaMetadata.isPlayable shouldBe true
        }

    @Test
    fun `getChildren author prefix returns empty list when author has no books`(): Unit =
        runBlocking {
            val provider =
                makeProvider(
                    bookIdsForContributor = mapOf("author-empty" to emptyList()),
                    contributorsById = mapOf("author-empty" to makeContributorEntity("author-empty", "No Books Author")),
                )
            provider.getChildren(BrowseTree.authorId("author-empty")).shouldBeEmpty()
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // Unknown / dynamic fallback
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren with an unknown parentId hits the fallback and returns empty list`(): Unit =
        runBlocking {
            val provider = makeProvider()
            // Not a known static ID, not a series prefix, not an author prefix
            provider.getChildren("/__totally_unknown__/node").shouldBeEmpty()
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // MAX_ITEMS_PER_LEVEL cap
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY_SERIES caps result at 8 items even with more series`(): Unit =
        runBlocking {
            val manySeries = (1..12).map { makeSeriesEntity("s-$it", "Series $it") }
            val provider = makeProvider(allSeries = manySeries)
            provider.getChildren(BrowseTree.LIBRARY_SERIES) shouldHaveSize 8
        }

    @Test
    fun `getChildren LIBRARY_AUTHORS caps result at 8 items`(): Unit =
        runBlocking {
            val manyAuthors = (1..15).map { makeContributorEntity("a-$it", "Author $it") }
            val provider = makeProvider(allContributors = manyAuthors)
            provider.getChildren(BrowseTree.LIBRARY_AUTHORS) shouldHaveSize 8
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // MediaItem metadata shape
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getChildren LIBRARY_RECENT items are playable and not browsable`(): Unit =
        runBlocking {
            val book = makeContinueListeningBook("book-x", "Book X")
            val provider = makeProvider(continueListeningBooks = listOf(book))
            val item = provider.getChildren(BrowseTree.LIBRARY_RECENT).first()
            item.mediaMetadata.isPlayable shouldBe true
            item.mediaMetadata.isBrowsable shouldBe false
            item.mediaMetadata.mediaType shouldBe MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
        }

    @Test
    fun `getChildren LIBRARY_SERIES items are browsable and not playable`(): Unit =
        runBlocking {
            val series = makeSeriesEntity("s-1", "A Series")
            val provider = makeProvider(allSeries = listOf(series))
            val item = provider.getChildren(BrowseTree.LIBRARY_SERIES).first()
            item.mediaMetadata.isBrowsable shouldBe true
            item.mediaMetadata.isPlayable shouldBe false
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // Builder helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun makeProvider(
        continueListeningBooks: List<ContinueListeningBook> = emptyList(),
        allBooks: List<BookEntity> = emptyList(),
        downloadsByBookId: Map<String, List<DownloadEntity>> = emptyMap(),
        allSeries: List<SeriesEntity> = emptyList(),
        seriesWithBooksById: Map<String, SeriesWithBooks> = emptyMap(),
        allContributors: List<ContributorEntity> = emptyList(),
        bookIdsForContributor: Map<String, List<String>> = emptyMap(),
        contributorsById: Map<String, ContributorEntity> = emptyMap(),
        booksById: Map<String, BookEntity> = emptyMap(),
    ): BrowseTreeProvider =
        BrowseTreeProvider(
            homeRepository =
                FakeHomeRepository(continueListeningBooks),
            bookDao =
                FakeBookDao(
                    allBooks = allBooks,
                    booksById = booksById,
                ),
            seriesDao =
                FakeSeriesDao(
                    allSeries = allSeries,
                    seriesWithBooksById = seriesWithBooksById,
                ),
            contributorDao =
                FakeContributorDao(
                    allContributors = allContributors,
                    bookIdsForContributor = bookIdsForContributor,
                    contributorsById = contributorsById,
                ),
            downloadDao = FakeDownloadDao(downloadsByBookId),
            imageStorage = FakeImageStorage(),
        )

    private fun makeContinueListeningBook(
        bookId: String,
        title: String = "A Book",
        authorNames: String = "An Author",
    ) = ContinueListeningBook(
        bookId = bookId,
        title = title,
        authorNames = authorNames,
        coverPath = null,
        coverBlurHash = null,
        progress = 0.5f,
        currentPositionMs = 3_600_000L,
        totalDurationMs = 7_200_000L,
        lastPlayedAt = "2024-01-01T12:00:00Z",
    )

    private fun makeBookEntity(
        id: String,
        title: String,
    ) = BookEntity(
        id = BookId(id),
        libraryId = TEST_LIBRARY_ID,
        folderId = TEST_FOLDER_ID,
        title = title,
        sortTitle = null,
        subtitle = null,
        coverHash = null,
        coverBlurHash = null,
        totalDuration = 7_200_000L,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun makeSeriesEntity(
        id: String,
        name: String,
    ) = SeriesEntity(
        id = SeriesId(id),
        name = name,
        description = null,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun makeContributorEntity(
        id: String,
        name: String,
    ) = ContributorEntity(
        id = ContributorId(id),
        name = name,
        sortName = null,
        asin = null,
        description = null,
        imagePath = null,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun makeDownloadEntity(
        bookId: String,
        audioFileId: String,
        state: DownloadState,
    ) = DownloadEntity(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = "audio.mp3",
        fileIndex = 0,
        state = state,
        localPath = if (state == DownloadState.COMPLETED) "/data/$bookId/audio.mp3" else null,
        totalBytes = 10_000_000L,
        downloadedBytes = if (state == DownloadState.COMPLETED) 10_000_000L else 0L,
        queuedAt = 1_700_000_000_000L,
        startedAt = null,
        completedAt = if (state == DownloadState.COMPLETED) 1_700_000_001_000L else null,
        errorMessage = null,
    )

    companion object {
        private val EPOCH = Timestamp(0L)
        private val TEST_LIBRARY_ID = LibraryId("test-library")
        private val TEST_FOLDER_ID = FolderId("test-folder")
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// In-memory fakes for :shared DAO and repository interfaces
// ──────────────────────────────────────────────────────────────────────────────

private class FakeHomeRepository(
    private val books: List<ContinueListeningBook>,
) : HomeRepository {
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> = AppResult.Success(books.take(limit))

    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = flowOf(books.take(limit).map { book -> ContinueListeningItem.Ready(bookId = book.bookId, book = book) })
}

private class FakeBookDao(
    private val allBooks: List<BookEntity> = emptyList(),
    private val booksById: Map<String, BookEntity> = emptyMap(),
) : BookDao {
    override suspend fun getAll(): List<BookEntity> = allBooks

    override suspend fun getById(id: BookId): BookEntity? = booksById[id.value]

    override suspend fun upsert(book: BookEntity) = Unit

    override suspend fun upsertAll(books: List<BookEntity>) = Unit

    override suspend fun count(): Int = allBooks.size

    override fun observeAll(): Flow<List<BookEntity>> = flowOf(allBooks)

    override fun observeAllWithContributors(): Flow<List<BookWithContributors>> = flowOf(emptyList())

    override suspend fun getByIdWithContributors(id: BookId): BookWithContributors? = null

    override fun observeByIdWithContributors(id: BookId): Flow<BookWithContributors?> = flowOf(null)

    override suspend fun getByIdsWithContributors(ids: List<BookId>): List<BookWithContributors> = emptyList()

    override fun observeByIdsWithContributors(ids: List<BookId>): Flow<List<BookWithContributors>> = flowOf(emptyList())

    override suspend fun deleteById(id: BookId) = Unit

    override suspend fun deleteByIds(ids: List<BookId>) = Unit

    override suspend fun deleteAll() = Unit

    override suspend fun updateRevisionAndTimestamp(
        id: BookId,
        revision: Long,
        updatedAt: Timestamp,
    ) = Unit

    override suspend fun softDelete(
        id: BookId,
        deletedAt: Long,
        revision: Long,
    ) = Unit

    override suspend fun touchUpdatedAt(
        id: BookId,
        timestamp: Timestamp,
    ) = Unit

    override fun observeBySeriesId(seriesId: String): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun observeBySeriesIdWithContributors(seriesId: String): Flow<List<BookWithContributors>> = flowOf(emptyList())

    override fun observeByContributorAndRole(
        contributorId: String,
        role: String,
    ): Flow<List<BookWithContributors>> = flowOf(emptyList())

    override fun observeRecentlyAdded(limit: Int): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun observeRecentlyAddedWithAuthor(limit: Int): Flow<List<DiscoveryBookWithAuthor>> = flowOf(emptyList())

    override fun observeRandomUnstartedBooksWithAuthor(limit: Int): Flow<List<DiscoveryBookWithAuthor>> = flowOf(emptyList())

    override suspend fun liveIds(): List<String> = allBooks.map { it.id.value }

    override suspend fun tombstoneNotIn(
        accessibleIds: Collection<String>,
        now: Long,
    ) = Unit
}

private class FakeSeriesDao(
    private val allSeries: List<SeriesEntity> = emptyList(),
    private val seriesWithBooksById: Map<String, SeriesWithBooks> = emptyMap(),
) : SeriesDao {
    override suspend fun getAll(): List<SeriesEntity> = allSeries

    override suspend fun getByIdWithBooks(id: String): SeriesWithBooks? = seriesWithBooksById[id]

    override suspend fun getById(id: String): SeriesEntity? = allSeries.firstOrNull { it.id.value == id }

    override fun observeAll(): Flow<List<SeriesEntity>> = flowOf(allSeries)

    override fun observeById(id: String): Flow<SeriesEntity?> = flowOf(allSeries.firstOrNull { it.id.value == id })

    override fun observeByBookId(bookId: String): Flow<SeriesEntity?> = flowOf(null)

    override suspend fun getBookIdsForSeries(seriesId: String): List<String> = emptyList()

    override fun observeBookIdsForSeries(seriesId: String): Flow<List<String>> = flowOf(emptyList())

    override suspend fun upsert(series: SeriesEntity) = Unit

    override suspend fun upsertAll(series: List<SeriesEntity>) = Unit

    override suspend fun deleteById(id: String) = Unit

    override fun observeAllWithBooks(): Flow<List<SeriesWithBooks>> = flowOf(emptyList())

    override fun observeByIdWithBooks(id: String): Flow<SeriesWithBooks?> = flowOf(null)

    override suspend fun count(): Int = allSeries.size

    override suspend fun deleteAll() = Unit

    override suspend fun softDelete(
        id: SeriesId,
        deletedAt: Long,
        revision: Long,
    ) = Unit
}

private class FakeContributorDao(
    private val allContributors: List<ContributorEntity> = emptyList(),
    private val bookIdsForContributor: Map<String, List<String>> = emptyMap(),
    private val contributorsById: Map<String, ContributorEntity> = emptyMap(),
) : ContributorDao {
    override suspend fun getAll(): List<ContributorEntity> = allContributors

    override suspend fun getById(id: String): ContributorEntity? = contributorsById[id] ?: allContributors.firstOrNull { it.id.value == id }

    override suspend fun getBookIdsForContributor(contributorId: String): List<String> = bookIdsForContributor[contributorId] ?: emptyList()

    override fun observeAll(): Flow<List<ContributorEntity>> = flowOf(allContributors)

    override fun observeAllWithAliases(): Flow<List<ContributorWithAliases>> = flowOf(emptyList())

    override fun observeByIdWithAliases(id: String): Flow<ContributorWithAliases?> = flowOf(null)

    override suspend fun getByIdWithAliases(id: String): ContributorWithAliases? = null

    override suspend fun upsert(contributor: ContributorEntity) = Unit

    override suspend fun upsertAll(contributors: List<ContributorEntity>) = Unit

    override suspend fun deleteById(id: String) = Unit

    override fun observeByRoleWithCount(role: String): Flow<List<com.calypsan.listenup.client.data.local.db.ContributorWithBookCount>> = flowOf(emptyList())

    override fun observeById(id: String): Flow<ContributorEntity?> = flowOf(null)

    override fun observeRolesWithCountForContributor(contributorId: String): Flow<List<com.calypsan.listenup.client.data.local.db.RoleWithBookCount>> = flowOf(emptyList())

    override suspend fun count(): Int = allContributors.size

    override suspend fun deleteAll() = Unit

    override suspend fun softDelete(
        id: ContributorId,
        deletedAt: Long,
        revision: Long,
    ) = Unit

    override fun observeByBookId(bookId: String): Flow<List<ContributorEntity>> = flowOf(emptyList())

    override suspend fun getByBookId(bookId: String): List<ContributorEntity> = emptyList()

    override fun observeBookIdsForContributor(contributorId: String): Flow<List<String>> = flowOf(bookIdsForContributor[contributorId] ?: emptyList())
}

private class FakeDownloadDao(
    private val downloadsByBookId: Map<String, List<DownloadEntity>> = emptyMap(),
) : DownloadDao {
    override suspend fun getForBook(bookId: String): List<DownloadEntity> = downloadsByBookId[bookId] ?: emptyList()

    override fun observeForBook(bookId: String): Flow<List<DownloadEntity>> = flowOf(emptyList())

    override fun observeAll(): Flow<List<DownloadEntity>> = flowOf(emptyList())

    override suspend fun getByAudioFileId(audioFileId: String): DownloadEntity? = null

    override suspend fun getIncomplete(): List<DownloadEntity> = emptyList()

    override suspend fun getWaitingForServer(): List<DownloadEntity> = emptyList()

    override suspend fun getOldWaitingForServer(thresholdMs: Long): List<DownloadEntity> = emptyList()

    override suspend fun getLocalPath(audioFileId: String): String? = null

    override suspend fun insert(download: DownloadEntity) = Unit

    override suspend fun insertAll(downloads: List<DownloadEntity>) = Unit

    override suspend fun updateState(
        audioFileId: String,
        state: DownloadState,
        startedAt: Long?,
    ) = Unit

    override suspend fun updateStateForBookExcluding(
        bookId: String,
        newState: DownloadState,
        excludeState: DownloadState,
    ) = Unit

    override suspend fun updateProgress(
        audioFileId: String,
        downloaded: Long,
        total: Long,
    ) = Unit

    override suspend fun markCompletedWithState(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
        state: DownloadState,
    ) = Unit

    override suspend fun markWaitingForServer(
        audioFileId: String,
        transcodeJobId: String,
    ) = Unit

    override suspend fun updateErrorWithState(
        audioFileId: String,
        error: String,
        state: DownloadState,
    ) = Unit

    override suspend fun markDeletedForBook(bookId: String) = Unit

    override suspend fun hasDeletedRecords(bookId: String): Boolean = false

    override suspend fun deleteForBook(bookId: String) = Unit

    override suspend fun deleteAll() = Unit
}

/** [ImageStorage] fake that reports no cover exists for any book. */
private class FakeImageStorage : ImageStorage {
    override fun exists(bookId: BookId): Boolean = false

    override fun getCoverPath(bookId: BookId): String = "/fake/covers/${bookId.value}.jpg"

    override suspend fun saveCover(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteCover(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun saveCoverStaging(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override fun getCoverStagingPath(bookId: BookId): String = "/fake/staging/${bookId.value}.jpg"

    override suspend fun commitCoverStaging(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteCoverStaging(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun clearAll(): AppResult<Int> = AppResult.Success(0)

    override suspend fun saveContributorImage(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override fun getContributorImagePath(contributorId: String): String = "/fake/contributors/$contributorId.jpg"

    override fun contributorImageExists(contributorId: String): Boolean = false

    override suspend fun deleteContributorImage(contributorId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun saveSeriesCover(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override fun getSeriesCoverPath(seriesId: String): String = "/fake/series/$seriesId.jpg"

    override fun seriesCoverExists(seriesId: String): Boolean = false

    override suspend fun deleteSeriesCover(seriesId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun saveUserAvatar(
        userId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override fun getUserAvatarPath(userId: String): String = "/fake/avatars/$userId.jpg"

    override fun userAvatarExists(userId: String): Boolean = false

    override suspend fun deleteUserAvatar(userId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun saveSeriesCoverStaging(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override fun getSeriesCoverStagingPath(seriesId: String): String = "/fake/staging/series/$seriesId.jpg"

    override suspend fun commitSeriesCoverStaging(seriesId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteSeriesCoverStaging(seriesId: String): AppResult<Unit> = AppResult.Success(Unit)
}
