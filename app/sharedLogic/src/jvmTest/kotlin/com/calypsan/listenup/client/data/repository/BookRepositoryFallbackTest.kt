package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.result.AppResult as WireResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
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
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Verifies the "Never Stranded" RPC-fallback paths in [BookRepositoryImpl]:
 * the [BookRepositoryImpl.observeBookDetail] cache-miss write-through and the
 * hybrid [BookRepositoryImpl.search] (server FTS online, local FTS offline).
 *
 * Uses a real in-memory Room database — the cache-miss test asserts the Room
 * Flow re-emits after the write-through, which a mocked DAO cannot model.
 */
class BookRepositoryFallbackTest :
    FunSpec({

        test("observe a book present in Room emits it and never calls getBook") {
            withTestRepo(online = true) { repo, db, service ->
                seedRoom(db, id = "b1", title = "Present Book")

                repo.observeBookDetail("b1").test {
                    awaitItem()?.title shouldBe "Present Book"
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(0)) { service.getBook(any()) }
            }
        }

        test("observe a cache-missing book while online fetches via getBook and Room re-emits") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getBook(BookId("b2")) } returns
                    WireResult.Success(payload(id = "b2", title = "Fetched Book"))

                repo.observeBookDetail("b2").test {
                    // First emission: cache miss → null.
                    awaitItem() shouldBe null
                    // Write-through populates Room → the Room Flow re-emits.
                    awaitItem()?.title shouldBe "Fetched Book"
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(1)) { service.getBook(BookId("b2")) }
            }
        }

        test("observe a cache-missing book while offline emits null and never calls getBook") {
            withTestRepo(online = false) { repo, db, service ->
                repo.observeBookDetail("b3").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(0)) { service.getBook(any()) }
            }
        }

        test("search online hydrates server ids from Room in the server's rank order") {
            withTestRepo(online = true) { repo, db, service ->
                seedRoom(db, id = "b1", title = "Alpha")
                seedRoom(db, id = "b2", title = "Bravo")
                seedRoom(db, id = "b3", title = "Charlie")
                // Server returns ranked ids — b3, b1, b2 — not insertion order.
                everySuspend { service.searchBooks("kings", any()) } returns
                    WireResult.Success(listOf(BookId("b3"), BookId("b1"), BookId("b2")))

                repo.search("kings").test {
                    awaitItem().map { it.id.value } shouldBe listOf("b3", "b1", "b2")
                    awaitComplete()
                }
            }
        }

        test("search online hydrates a server result id absent from Room via getBook") {
            withTestRepo(online = true) { repo, db, service ->
                // b1 and b2 are already in Room; b3 is NOT seeded — it must be fetched on demand.
                seedRoom(db, id = "b1", title = "Alpha")
                seedRoom(db, id = "b2", title = "Bravo")
                // Server returns b3 first (top rank), then b1, then b2.
                everySuspend { service.searchBooks("kings", any()) } returns
                    WireResult.Success(listOf(BookId("b3"), BookId("b1"), BookId("b2")))
                // getBook is called for the cache-missing b3.
                everySuspend { service.getBook(BookId("b3")) } returns
                    WireResult.Success(payload(id = "b3", title = "Charlie"))

                repo.search("kings").test {
                    // Result must include the on-demand-fetched b3, in server rank order.
                    awaitItem().map { it.id.value } shouldBe listOf("b3", "b1", "b2")
                    awaitComplete()
                }
            }
        }

        test("search offline falls back to local FTS5") {
            withTestRepo(online = false) { repo, db, service ->
                seedRoom(db, id = "b1", title = "The Way of Kings")
                db.searchDao().insertBookFts(
                    bookId = "b1",
                    title = "The Way of Kings",
                    subtitle = null,
                    description = null,
                    author = null,
                    narrator = null,
                    seriesName = null,
                    genres = null,
                )

                repo.search("kings").test {
                    awaitItem().map { it.id.value } shouldBe listOf("b1")
                    awaitComplete()
                }

                verifySuspend(exactly(0)) { service.searchBooks(any(), any()) }
            }
        }
    })

/**
 * Builds an in-memory database, a real books sync handler for write-through,
 * a [BookRepositoryImpl] wired with mocked RPC + network, runs [block], and closes
 * the database afterwards. [online] controls the mocked [NetworkMonitor].
 */
private fun withTestRepo(
    online: Boolean = true,
    block: suspend (BookRepositoryImpl, ListenUpDatabase, BookService) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val service: BookService = mock()
        val channel = RpcChannel.forTest(service)

        val networkMonitor: NetworkMonitor = mock()
        every { networkMonitor.isOnline() } returns online

        val imageStorage: ImageStorage = mock()
        every { imageStorage.exists(any()) } returns false

        val genreRepository: GenreRepository = mock()
        every { genreRepository.observeGenresForBook(any()) } returns emptyFlowOf()

        val tagRepository: TagRepository = mock()
        every { tagRepository.observeTagsForBook(any()) } returns emptyFlowOf()

        val moodRepository: MoodRepository = mock()
        every { moodRepository.observeMoodsForBook(any()) } returns emptyFlowOf()

        val transactionRunner = RoomTransactionRunner(db)
        val syncHandler =
            booksDomain(
                database = db,
                mapper = BookEntityMapper(),
                imageStorage = stubImageStorage(),
            ).toHandler(transactionRunner = transactionRunner, registry = ClientSyncDomainRegistry())

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

        block(repo, db, service)
    } finally {
        db.close()
    }
}

/** A [MutableStateFlow]-backed empty list flow that emits once — so `combine` upstreams produce a value. */
private fun <T> emptyFlowOf(): kotlinx.coroutines.flow.Flow<List<T>> = MutableStateFlow(emptyList())

/** Seeds a minimal book aggregate into Room via the canonical sync write path. */
private suspend fun seedRoom(
    db: ListenUpDatabase,
    id: String,
    title: String,
) {
    val handler =
        booksDomain(
            database = db,
            mapper = BookEntityMapper(),
            imageStorage = stubImageStorage(),
        ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
    handler.onCatchUpItem(payload(id = id, title = title), isTombstone = false)
}

private fun payload(
    id: String,
    title: String,
): BookSyncPayload =
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
    )
