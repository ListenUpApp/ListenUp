@file:OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.BookDetailJoinSources
import com.calypsan.listenup.client.data.repository.BookRepositoryImpl
import com.calypsan.listenup.client.data.repository.ContributorRepositoryImpl
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesRepositoryImpl
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the LibraryViewModel's data sources against a **real** in-memory Room database, rather
 * than the `flowOf(...)` mocks the unit test uses — which emit-once-and-complete and so can mask
 * real Room behaviour.
 *
 * Part 1 asserts each `rawContent` source flow emits its first value on an empty DB (so the
 * top-level combine can produce a value and `uiState` leaves its `Loading` seed). Part 2 pins the
 * stat-elimination invariant: `observeBookListItems` derives `coverPath` purely from the
 * persisted `coverDownloadedAt` marker and never performs a per-book filesystem stat
 * ([ImageStorage.exists]).
 */
class LibraryViewModelRealRoomTest :
    FunSpec({

        // ───────────────────────── Part 1: each rawContent source emits ─────────────────────────

        test("observeBookListItems emits an initial value on an empty DB") {
            val db = createInMemoryTestDatabase()
            try {
                runBlocking {
                    val books = withTimeout(5.seconds) { realBookRepository(db).observeBookListItems().first() }
                    books shouldBe emptyList()
                }
            } finally {
                db.close()
            }
        }

        test("observeAllWithBooks emits an initial value on an empty DB") {
            val db = createInMemoryTestDatabase()
            try {
                runBlocking {
                    val series = withTimeout(5.seconds) { realSeriesRepository(db).observeAllWithBooks().first() }
                    series shouldBe emptyList()
                }
            } finally {
                db.close()
            }
        }

        test("observeContributorsByRole(author) emits an initial value on an empty DB") {
            val db = createInMemoryTestDatabase()
            try {
                runBlocking {
                    val authors =
                        withTimeout(5.seconds) {
                            realContributorRepository(db)
                                .observeContributorsByRole(ContributorRole.AUTHOR.apiValue)
                                .first()
                        }
                    authors shouldBe emptyList()
                }
            } finally {
                db.close()
            }
        }

        test("observeAll(positions) emits an initial value on an empty DB") {
            val db = createInMemoryTestDatabase()
            try {
                runBlocking {
                    val positions =
                        withTimeout(5.seconds) { realPlaybackPositionRepository(db).observeAll().first() }
                    positions shouldBe emptyMap()
                }
            } finally {
                db.close()
            }
        }

        // ───────────────────── Part 2: mapping performs zero per-book filesystem stats ─────────────────────

        test("observeBookListItems maps without any per-book filesystem stat") {
            val db = createInMemoryTestDatabase()
            try {
                // Strict mock: only stub what the pure mapper legitimately needs (getCoverPath).
                // `exists` is deliberately left unstubbed — Mokkery's default strict mode throws on
                // any unstubbed call, so if the mapper still called `exists()` this test would fail
                // with that throw. The absence of a throw IS the guard for the stat-elimination.
                val imageStorage =
                    mock<ImageStorage> {
                        every { getCoverPath(any()) } returns "/covers/b1.jpg"
                    }
                val repository = bookRepositoryWith(db, imageStorage)
                seedBook(db, "b1")
                // Mark the cover as downloaded so coverPathFor() exercises getCoverPath().
                db.bookDao().markCoverDownloaded(BookId("b1"), Timestamp(1L))

                runBlocking {
                    val items =
                        withTimeout(5.seconds) {
                            repository.observeBookListItems().first { it.isNotEmpty() }
                        }
                    items.size shouldBe 1

                    verify(VerifyMode.not) { imageStorage.exists(any()) }
                }
            } finally {
                db.close()
            }
        }
    })

/** Seeds a single minimal book into [db] via the real sync handler — the same write path SSE uses. */
private suspend fun seedBook(
    db: ListenUpDatabase,
    id: String,
) {
    val handler =
        booksDomain(
            database = db,
            mapper = BookEntityMapper(),
            imageStorage = stubImageStorage(),
        ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
    handler.onCatchUpItem(
        BookSyncPayload(
            id = id,
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = "Book $id",
            subtitle = null,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            explicit = false,
            totalDuration = 3_600_000L,
            cover = null,
            rootRelPath = "books/$id",
            inode = null,
            scannedAt = 1L,
            contributors = emptyList(),
            series = emptyList(),
            audioFiles = emptyList(),
            chapters = emptyList(),
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        ),
        isTombstone = false,
    )
}

private fun bookRepositoryWith(
    db: ListenUpDatabase,
    imageStorage: ImageStorage,
): BookRepositoryImpl {
    val transactionRunner = RoomTransactionRunner(db)
    val genreRepository = mock<GenreRepository> { every { observeGenresForBook(any()) } returns MutableStateFlow(emptyList()) }
    val tagRepository =
        mock<com.calypsan.listenup.client.domain.repository.TagRepository> {
            every { observeTagsForBook(any()) } returns MutableStateFlow(emptyList())
        }
    val moodRepository =
        mock<com.calypsan.listenup.client.domain.repository.MoodRepository> {
            every { observeMoodsForBook(any()) } returns MutableStateFlow(emptyList())
        }
    return BookRepositoryImpl(
        bookDao = db.bookDao(),
        chapterDao = mock<ChapterDao>(),
        audioFileDao = mock<AudioFileDao>(),
        searchDao = db.searchDao(),
        transactionRunner = transactionRunner,
        imageStorage = imageStorage,
        joinSources = BookDetailJoinSources(genreRepository, tagRepository, moodRepository),
        networkMonitor = mock<NetworkMonitor> { every { isOnline() } returns false },
        channel = RpcChannel.forTest(mock<BookService>()),
        bookSyncDomainHandler =
            booksDomain(
                database = db,
                mapper = BookEntityMapper(),
                imageStorage = stubImageStorage(),
            ).toHandler(transactionRunner = transactionRunner, registry = ClientSyncDomainRegistry()),
    )
}

// ───────────────────────────────── real repositories over Room ─────────────────────────────────
//
// The observe-flows touch only the DAOs + ImageStorage; every other collaborator (RPC, network,
// API, sync handlers, pending queue) is mocked because the read path never invokes it.

private fun mockImageStorage(): ImageStorage = mock<ImageStorage> { every { exists(any()) } returns false }

private fun realBookRepository(db: ListenUpDatabase): BookRepositoryImpl {
    val transactionRunner = RoomTransactionRunner(db)
    val genreRepository = mock<GenreRepository> { every { observeGenresForBook(any()) } returns MutableStateFlow(emptyList()) }
    val tagRepository =
        mock<com.calypsan.listenup.client.domain.repository.TagRepository> {
            every { observeTagsForBook(any()) } returns MutableStateFlow(emptyList())
        }
    val moodRepository =
        mock<com.calypsan.listenup.client.domain.repository.MoodRepository> {
            every { observeMoodsForBook(any()) } returns MutableStateFlow(emptyList())
        }
    return BookRepositoryImpl(
        bookDao = db.bookDao(),
        chapterDao = mock<ChapterDao>(),
        audioFileDao = mock<AudioFileDao>(),
        searchDao = db.searchDao(),
        transactionRunner = transactionRunner,
        imageStorage = mockImageStorage(),
        joinSources = BookDetailJoinSources(genreRepository, tagRepository, moodRepository),
        networkMonitor = mock<NetworkMonitor> { every { isOnline() } returns false },
        channel = RpcChannel.forTest(mock<BookService>()),
        bookSyncDomainHandler =
            booksDomain(
                database = db,
                mapper = BookEntityMapper(),
                imageStorage = stubImageStorage(),
            ).toHandler(transactionRunner = transactionRunner, registry = ClientSyncDomainRegistry()),
    )
}

private fun realSeriesRepository(db: ListenUpDatabase): SeriesRepositoryImpl =
    SeriesRepositoryImpl(
        seriesDao = db.seriesDao(),
        bookDao = db.bookDao(),
        searchDao = db.searchDao(),
        api = mock<SeriesApiContract>(),
        networkMonitor = mock<NetworkMonitor> { every { isOnline() } returns false },
        imageStorage = mockImageStorage(),
        channel = RpcChannel.forTest(mock<SeriesService>()),
        seriesSyncHandler = mock<SyncDomainHandler<SeriesSyncPayload>>(),
    )

private fun realContributorRepository(db: ListenUpDatabase): ContributorRepositoryImpl =
    ContributorRepositoryImpl(
        contributorDao = db.contributorDao(),
        bookDao = db.bookDao(),
        searchDao = db.searchDao(),
        api = mock<ContributorApiContract>(),
        networkMonitor = mock<NetworkMonitor> { every { isOnline() } returns false },
        imageStorage = mockImageStorage(),
        channel = RpcChannel.forTest(mock<ContributorService>()),
        contributorSyncHandler = mock<SyncDomainHandler<ContributorSyncPayload>>(),
    )

private fun realPlaybackPositionRepository(db: ListenUpDatabase): PlaybackPositionRepositoryImpl =
    PlaybackPositionRepositoryImpl(
        dao = db.playbackPositionDao(),
        transactionRunner = RoomTransactionRunner(db),
        pendingQueue =
            PendingOperationQueue(
                dao = db.pendingOperationV2Dao(),
                sender = PendingOperationSender { AppResult.Success(Unit) },
            ),
        authSession = mock<AuthSession>(),
    )
