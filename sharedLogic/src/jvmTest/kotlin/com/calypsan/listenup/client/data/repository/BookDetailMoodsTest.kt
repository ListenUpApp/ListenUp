package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.Mood as WireMood
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.moodsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Integration test for the load-bearing book-detail moods join.
 *
 * Moods do NOT ride the book payload — like tags, they reach [BookDetail] via the
 * client-side `combine`-join in [BookRepositoryImpl.observeBookDetail]. This test seeds
 * a book, a mood, and the `book_moods` junction through the real sync handlers and Room
 * DAOs, then asserts the emitted [BookDetail.moods] is populated.
 *
 * Uses a real in-memory Room database and the real [MoodRepositoryImpl] so the reactive
 * JOIN under test is exercised via actual SQLite.
 */
class BookDetailMoodsTest :
    FunSpec({

        test("observeBookDetail populates BookDetail.moods from book_moods + moods rows") {
            withTestRepo { repo, db ->
                seedBook(db, id = "b1", title = "Project Hail Mary")
                seedMood(db, id = "m1", name = "Feel-Good", slug = "feel-good")
                seedBookMood(db, bookId = "b1", moodId = "m1")

                repo.observeBookDetail("b1").test {
                    val detail = awaitItem()
                    detail shouldNotBe null
                    detail!!.moods.map { it.name }.shouldContainExactly("Feel-Good")
                    detail.moods.map { it.slug }.shouldContainExactly("feel-good")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun withTestRepo(block: suspend (BookRepositoryImpl, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            val genreRepository: GenreRepository = mock()
            every { genreRepository.observeGenresForBook(any()) } returns MutableStateFlow(emptyList())

            val tagRepository: TagRepository = mock()
            every { tagRepository.observeTagsForBook(any()) } returns MutableStateFlow(emptyList())

            val moodRepository =
                MoodRepositoryImpl(
                    channel = RpcChannel.forTest(mock<MoodService>()),
                    moodDao = db.moodDao(),
                    bookMoodDao = db.bookMoodDao(),
                    offlineEditor = noopOfflineEditor(),
                )

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
    title: String,
) {
    booksDomain(
        database = db,
        mapper = BookEntityMapper(),
        imageStorage = stubImageStorage(),
    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry()).onCatchUpItem(
        BookSyncPayload(
            id = id,
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = title,
            sortTitle = title,
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
            revision = 1L,
            updatedAt = 100L,
            createdAt = 1L,
            deletedAt = null,
        ),
        isTombstone = false,
    )
}

private suspend fun seedMood(
    db: ListenUpDatabase,
    id: String,
    name: String,
    slug: String,
) {
    moodsDomain(db)
        .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
        .onCatchUpItem(
            WireMood(id = id, name = name, slug = slug, revision = 1L, updatedAt = 100L, deletedAt = null),
            isTombstone = false,
        )
}

private suspend fun seedBookMood(
    db: ListenUpDatabase,
    bookId: String,
    moodId: String,
) {
    bookMoodsDomain(db)
        .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
        .onCatchUpItem(
            BookMoodSyncPayload(
                bookId = bookId,
                moodId = moodId,
                createdAt = 100L,
                revision = 1L,
                deletedAt = null,
            ),
            isTombstone = false,
        )
}
