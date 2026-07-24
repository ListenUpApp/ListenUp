package com.calypsan.listenup.client.books

import app.cash.turbine.test
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.BookDetailJoinSources
import com.calypsan.listenup.client.data.repository.BookRepositoryImpl
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests for the `books` sync domain: a write on the server's
 * [com.calypsan.listenup.server.services.BookRepository] crosses the live SSE
 * firehose, the client [SyncEngine][com.calypsan.listenup.client.data.sync.SyncEngine]
 * routes it through the real books sync handler ([booksDomain]), and the book lands in the
 * client's Room database — exactly the round-trip production performs.
 *
 * The fixture [withClientSyncEngineAgainstServer] boots a real `:server`
 * `testApplication` and a real client engine in one process; these tests assert
 * against the client `clientDatabase` it exposes.
 *
 * Async waits poll a real query inside [withTimeout] — matching the
 * `ClientSyncEngineE2ETest` idiom — rather than a fixed `delay`, since SSE
 * delivery latency is non-deterministic.
 */
class BooksEndToEndTest :
    FunSpec({

        test("server upsert → SSE → client Room has the book and its contributor") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // Resolve the contributor on the server so its row exists in the
                // contributors table before we insert the book_contributors FK row.
                val contributorId =
                    serverContributorRepository.resolveOrCreate("Brandon Sanderson", sortName = null)

                serverBookRepository.upsert(
                    bookPayload(
                        id = "b1",
                        title = "The Way of Kings",
                        contributors = listOf(authorContributor(contributorId.value, "Brandon Sanderson")),
                    ),
                )

                val book =
                    awaitClientBook(clientDatabase, "b1", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                book.title shouldBe "The Way of Kings"

                // Verify the author cross-ref landed in Room for this book
                val authorRef =
                    clientDatabase
                        .bookContributorDao()
                        .get(BookId("b1"), contributorId.value, "author")
                authorRef.shouldNotBeNull()
                // Verify the contributor itself was synced with the correct name
                val contributor = clientDatabase.contributorDao().getById(contributorId.value)
                contributor.shouldNotBeNull()
                contributor.name shouldBe "Brandon Sanderson"
            }
        }

        test("server softDelete → SSE → client book is tombstoned") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                serverBookRepository.upsert(bookPayload(id = "b1", title = "Words of Radiance"))
                awaitClientBook(clientDatabase, "b1", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                serverBookRepository.softDelete(BookId("b1"))

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("b1"))?.deletedAt == null) {
                        // Poll the real query — SSE delivery latency is non-deterministic.
                    }
                }
                clientDatabase
                    .bookDao()
                    .getById(BookId("b1"))
                    ?.deletedAt
                    .shouldNotBeNull()
            }
        }

        test("observeBookDetail emits when the synced book lands in Room") {
            withClientSyncEngineAgainstServer {
                val clientRepo = clientBookRepository(clientDatabase)
                engine.start(currentUserId = "u1")

                clientRepo.observeBookDetail("b1").test {
                    // Cache miss before any sync: the book is absent.
                    awaitItem() shouldBe null

                    serverBookRepository.upsert(
                        bookPayload(id = "b1", title = "Oathbringer"),
                    )

                    // The synced book lands in Room → the observe-flow re-emits it.
                    var emitted = awaitItem()
                    while (emitted == null) emitted = awaitItem()
                    emitted.title shouldBe "Oathbringer"

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/**
 * Polls the client Room DB until the book [id] is present, or fails after
 * [timeout]. Returns the landed [com.calypsan.listenup.client.data.local.db.BookEntity].
 */
private suspend fun awaitClientBook(
    database: ListenUpDatabase,
    id: String,
    timeout: kotlin.time.Duration,
) = withTimeout(timeout) {
    var book = database.bookDao().getById(BookId(id))
    while (book == null) {
        book = database.bookDao().getById(BookId(id))
    }
    book
}

/**
 * Builds a client [BookRepository] over the fixture's in-memory Room DB. The
 * RPC / network / image / genre / tag collaborators are mocked: this surface
 * only exercises the Room-observing `observeBookDetail` path. The book is
 * already present (placed there by the SSE round-trip), so [NetworkMonitor] is
 * offline and the never-stranded RPC fallback is never reached.
 */
private fun clientBookRepository(database: ListenUpDatabase): BookRepository {
    val channel = RpcChannel.forTest(mock<BookService>())
    val networkMonitor: NetworkMonitor = mock()
    every { networkMonitor.isOnline() } returns false
    val imageStorage: ImageStorage = mock()
    every { imageStorage.exists(any()) } returns false
    val genreRepository: GenreRepository = mock()
    every { genreRepository.observeGenresForBook(any()) } returns MutableStateFlow(emptyList())
    val tagRepository: TagRepository = mock()
    every { tagRepository.observeTagsForBook(any()) } returns MutableStateFlow(emptyList())
    val moodRepository: MoodRepository = mock()
    every { moodRepository.observeMoodsForBook(any()) } returns MutableStateFlow(emptyList())

    val transactionRunner = RoomTransactionRunner(database)
    val syncHandler =
        booksDomain(
            database = database,
            mapper = BookEntityMapper(),
            imageStorage = stubImageStorage(),
        ).toHandler(transactionRunner = transactionRunner, registry = ClientSyncDomainRegistry())

    return BookRepositoryImpl(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        audioFileDao = database.audioFileDao(),
        searchDao = database.searchDao(),
        transactionRunner = transactionRunner,
        imageStorage = imageStorage,
        joinSources = BookDetailJoinSources(genreRepository, tagRepository, moodRepository),
        networkMonitor = networkMonitor,
        channel = channel,
        bookSyncDomainHandler = syncHandler,
    )
}

private fun authorContributor(
    id: String,
    name: String,
): BookContributorPayload =
    BookContributorPayload(
        id = id,
        name = name,
        sortName = name,
        role = "author",
        creditedAs = null,
    )

private fun bookPayload(
    id: String,
    title: String,
    contributors: List<BookContributorPayload> = emptyList(),
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
        contributors = contributors,
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
