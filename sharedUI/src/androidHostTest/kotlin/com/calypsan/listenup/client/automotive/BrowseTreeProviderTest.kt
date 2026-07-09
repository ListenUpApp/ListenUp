package com.calypsan.listenup.client.automotive

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [BrowseTreeProvider.getChildren].
 *
 * [MediaItem.Builder] internally uses Android's [android.net.Uri] and is only available
 * on a real device or via Robolectric. This test therefore uses [RobolectricTestRunner]
 * + JUnit4, consistent with [DeepLinkParserTest] and [PlaybackErrorHandlerTest].
 * The `junit-vintage-engine` on the classpath keeps these discoverable alongside
 * Kotest specs in `androidHostTest`.
 *
 * The provider consumes the public `domain/repository` interfaces, which are satisfied
 * here by mokkery mocks seeded with domain models. This exercises all routing branches
 * of [BrowseTreeProvider.getChildren] without naming any `:shared` DAO or `@Entity` type.
 */
@RunWith(RobolectricTestRunner::class)
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
            val provider = makeProvider(downloadedBooks = emptyList())
            provider.getChildren(BrowseTree.LIBRARY_DOWNLOADED).shouldBeEmpty()
        }

    @Test
    fun `getChildren LIBRARY_DOWNLOADED maps each downloaded book to a playable media item`(): Unit =
        runBlocking {
            val provider =
                makeProvider(
                    downloadedBooks = listOf(makeDownloadedBookSummary("book-dl", "Downloaded Book")),
                )
            val children = provider.getChildren(BrowseTree.LIBRARY_DOWNLOADED)
            children shouldHaveSize 1
            children[0].mediaId shouldBe BrowseTree.bookId("book-dl")
            children[0].mediaMetadata.isPlayable shouldBe true
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
                    makeSeries("series-1", "The Stormlight Archive"),
                    makeSeries("series-2", "Mistborn"),
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
                    makeContributor("author-1", "Brandon Sanderson"),
                    makeContributor("author-2", "Patrick Rothfuss"),
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
            val book1 = makeBookListItem("book-sw1", "The Way of Kings")
            val book2 = makeBookListItem("book-sw2", "Words of Radiance")
            val seriesWithBooks =
                SeriesWithBooks(
                    series = makeSeries("series-sa", "The Stormlight Archive"),
                    books = listOf(book1, book2),
                    bookSequences = emptyMap(),
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
            val provider =
                makeProvider(
                    bookIdsForContributor = mapOf("author-pr" to listOf("book-1")),
                    contributorsById = mapOf("author-pr" to makeContributor("author-pr", "Patrick Rothfuss")),
                    booksById = mapOf("book-1" to makeBookListItem("book-1", "The Name of the Wind")),
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
                    contributorsById = mapOf("author-empty" to makeContributor("author-empty", "No Books Author")),
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
            val manySeries = (1..12).map { makeSeries("s-$it", "Series $it") }
            val provider = makeProvider(allSeries = manySeries)
            provider.getChildren(BrowseTree.LIBRARY_SERIES) shouldHaveSize 8
        }

    @Test
    fun `getChildren LIBRARY_AUTHORS caps result at 8 items`(): Unit =
        runBlocking {
            val manyAuthors = (1..15).map { makeContributor("a-$it", "Author $it") }
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
            val provider = makeProvider(allSeries = listOf(makeSeries("s-1", "A Series")))
            val item = provider.getChildren(BrowseTree.LIBRARY_SERIES).first()
            item.mediaMetadata.isBrowsable shouldBe true
            item.mediaMetadata.isPlayable shouldBe false
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // Builder helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun makeProvider(
        continueListeningBooks: List<ContinueListeningBook> = emptyList(),
        downloadedBooks: List<DownloadedBookSummary> = emptyList(),
        allSeries: List<Series> = emptyList(),
        seriesWithBooksById: Map<String, SeriesWithBooks> = emptyMap(),
        allContributors: List<Contributor> = emptyList(),
        bookIdsForContributor: Map<String, List<String>> = emptyMap(),
        contributorsById: Map<String, Contributor> = emptyMap(),
        booksById: Map<String, BookListItem> = emptyMap(),
    ): BrowseTreeProvider {
        val bookRepository = mock<BookRepository>()
        everySuspend { bookRepository.getBookListItem(any()) } returns null
        booksById.forEach { (id, book) ->
            everySuspend { bookRepository.getBookListItem(id) } returns book
        }

        val seriesRepository = mock<SeriesRepository>()
        every { seriesRepository.observeAll() } returns flowOf(allSeries)
        every { seriesRepository.observeSeriesWithBooks(any()) } returns flowOf(null)
        seriesWithBooksById.forEach { (id, value) ->
            every { seriesRepository.observeSeriesWithBooks(id) } returns flowOf(value)
        }

        val contributorRepository = mock<ContributorRepository>()
        every { contributorRepository.observeAll() } returns flowOf(allContributors)
        everySuspend { contributorRepository.getById(any()) } returns null
        everySuspend { contributorRepository.getBookIdsForContributor(any()) } returns emptyList()
        contributorsById.forEach { (id, value) ->
            everySuspend { contributorRepository.getById(id) } returns value
        }
        bookIdsForContributor.forEach { (id, ids) ->
            everySuspend { contributorRepository.getBookIdsForContributor(id) } returns ids
        }

        val downloadRepository = mock<DownloadRepository>()
        every { downloadRepository.observeDownloadedBooks() } returns flowOf(downloadedBooks)

        return BrowseTreeProvider(
            homeRepository = FakeHomeRepository(continueListeningBooks),
            bookRepository = bookRepository,
            seriesRepository = seriesRepository,
            contributorRepository = contributorRepository,
            downloadRepository = downloadRepository,
            imageStorage = FakeImageStorage(),
        )
    }

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

    private fun makeBookListItem(
        id: String,
        title: String,
    ) = BookListItem(
        id = BookId(id),
        libraryId = TEST_LIBRARY_ID,
        folderId = TEST_FOLDER_ID,
        title = title,
        authors = emptyList(),
        narrators = emptyList(),
        duration = 7_200_000L,
        coverPath = null,
        addedAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun makeSeries(
        id: String,
        name: String,
    ) = Series(
        id = SeriesId(id),
        name = name,
    )

    private fun makeContributor(
        id: String,
        name: String,
    ) = Contributor(
        id = ContributorId(id),
        name = name,
    )

    private fun makeDownloadedBookSummary(
        bookId: String,
        title: String,
    ) = DownloadedBookSummary(
        bookId = bookId,
        title = title,
        authorNames = "An Author",
        coverBlurHash = null,
        sizeBytes = 10_000_000L,
        fileCount = 1,
    )

    companion object {
        private val EPOCH = Timestamp(0L)
        private val TEST_LIBRARY_ID = LibraryId("test-library")
        private val TEST_FOLDER_ID = FolderId("test-folder")
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// In-memory fake for the HomeRepository interface (mokkery-free, list-backed)
// ──────────────────────────────────────────────────────────────────────────────

private class FakeHomeRepository(
    private val books: List<ContinueListeningBook>,
) : HomeRepository {
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> = AppResult.Success(books.take(limit))

    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = flowOf(books.take(limit).map { book -> ContinueListeningItem.Ready(bookId = book.bookId, book = book) })
}

/** [ImageStorage] fake that reports no cover exists for any book. */
private class FakeImageStorage : ImageStorage {
    override fun exists(bookId: BookId): Boolean = false

    override fun listCoverBookIds(): Set<BookId> = emptySet()

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
