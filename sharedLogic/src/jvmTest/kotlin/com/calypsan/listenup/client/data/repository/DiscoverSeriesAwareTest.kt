package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * TDD tests for the series-aware discover filter in [BookRepositoryImpl.observeRandomUnstartedBooks].
 *
 * Rules under test:
 * - Standalone books (no series edge) are always included.
 * - A book that is first-in-series (sequence "1" / "0" / "0.5") in ANY of its series is included.
 * - Mid-series books (sequence "3" etc.) are excluded unless they're first in another series.
 * - The result count never exceeds the requested limit.
 */
class DiscoverSeriesAwareTest :
    FunSpec({

        test("observeRandomUnstartedBooks includes standalone + first-in-series, excludes mid-series") {
            withDiscoverRepo { repo, db ->
                seedBook(db, "standalone")
                seedBook(db, "first")
                seedBook(db, "mid")
                seedSeries(db, "s1", "Series One")
                linkSeries(db, "first", "s1", "1")
                linkSeries(db, "mid", "s1", "3")

                val ids = repo.observeRandomUnstartedBooks(limit = 10).first().map { it.id }
                ids.shouldContainExactlyInAnyOrder("standalone", "first")
            }
        }

        test("a book that is first in ANY of its series is included") {
            withDiscoverRepo { repo, db ->
                seedBook(db, "multi")
                seedSeries(db, "s1", "Series One")
                seedSeries(db, "s2", "Series Two")
                linkSeries(db, "multi", "s1", "4") // mid in s1
                linkSeries(db, "multi", "s2", "1") // first in s2

                val ids = repo.observeRandomUnstartedBooks(limit = 10).first().map { it.id }
                ids.shouldContainExactlyInAnyOrder("multi")
            }
        }

        test("result count never exceeds the limit") {
            withDiscoverRepo { repo, db ->
                repeat(5) { seedBook(db, "b$it") } // all standalone → all candidates

                val result = repo.observeRandomUnstartedBooks(limit = 3).first()
                result.size shouldBeLessThanOrEqual 3
            }
        }
    })

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun withDiscoverRepo(
    block: suspend (BookRepositoryImpl, ListenUpDatabase) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val imageStorage: ImageStorage = mock()
        every { imageStorage.exists(any()) } returns false

        val genreRepository: GenreRepository = mock()
        every { genreRepository.observeGenresForBook(any()) } returns MutableStateFlow(emptyList())

        val tagRepository: TagRepository = mock()
        every { tagRepository.observeTagsForBook(any()) } returns MutableStateFlow(emptyList())

        val moodRepository: MoodRepository = mock()
        every { moodRepository.observeMoodsForBook(any()) } returns MutableStateFlow(emptyList())

        val networkMonitor: NetworkMonitor = mock()
        every { networkMonitor.isOnline() } returns false

        val transactionRunner = RoomTransactionRunner(db)
        val syncHandler =
            booksDomain(
                database = db,
                mapper = BookEntityMapper(),
                imageStorage = stubImageStorage(),
            ).toHandler(transactionRunner = transactionRunner, registry = ClientSyncDomainRegistry())

        val channel = RpcChannel.forTest(mock<BookService>())

        val repo =
            BookRepositoryImpl(
                bookDao = db.bookDao(),
                chapterDao = db.chapterDao(),
                audioFileDao = db.audioFileDao(),
                searchDao = db.searchDao(),
                transactionRunner = transactionRunner,
                imageStorage = imageStorage,
                joinSources = BookDetailJoinSources(genreRepository, tagRepository, moodRepository),
                networkMonitor = networkMonitor,
                channel = channel,
                bookSyncDomainHandler = syncHandler,
            )

        block(repo, db)
    } finally {
        db.close()
    }
}

private suspend fun seedBook(
    db: ListenUpDatabase,
    id: String,
) {
    db.bookDao().upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = "Book $id",
            subtitle = null,
            coverHash = null,
            coverBlurHash = null,
            totalDuration = 0L,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}

private suspend fun seedSeries(
    db: ListenUpDatabase,
    id: String,
    name: String,
) {
    db.seriesDao().upsert(
        SeriesEntity(
            id = SeriesId(id),
            name = name,
            description = null,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}

private suspend fun linkSeries(
    db: ListenUpDatabase,
    bookId: String,
    seriesId: String,
    sequence: String,
) {
    db.bookSeriesDao().insertAll(
        listOf(
            BookSeriesCrossRef(
                bookId = BookId(bookId),
                seriesId = SeriesId(seriesId),
                sequence = sequence,
            ),
        ),
    )
}
