package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase

/**
 * Integration tests for [BookRepositoryImpl.observeBookListItems] (ids-filtered overload).
 *
 * Uses a real in-memory Room database so the reactive DAO query under test is exercised
 * via actual SQLite — a mocked DAO cannot verify that Room emits on row-changes.
 */
class BookRepositoryImplTest :
    FunSpec({

        test("observeBookListItems(ids) emits when a requested book changes in Room") {
            withTestRepo { repo, db ->
                seedRoom(db, id = "b1", title = "Alpha")
                seedRoom(db, id = "b2", title = "Bravo")
                seedRoom(db, id = "b3", title = "Charlie")

                repo.observeBookListItems(listOf("b1", "b2", "b3")).test {
                    val first = awaitItem()
                    first.map { it.title }.shouldContainExactlyInAnyOrder("Alpha", "Bravo", "Charlie")

                    // Mutate b2 — Room should emit a new list with the updated title.
                    seedRoom(db, id = "b2", title = "Bravo Updated")

                    val second = awaitItem()
                    second.map { it.title }.shouldContainExactlyInAnyOrder("Alpha", "Bravo Updated", "Charlie")

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeBookListItems(ids) returns empty list when none of the ids match") {
            withTestRepo { repo, _ ->
                repo.observeBookListItems(listOf("missing-1", "missing-2")).test {
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeBookListItems(ids) shape matches getBookListItems for the same input") {
            withTestRepo { repo, db ->
                seedRoom(db, id = "b1", title = "Alpha")
                seedRoom(db, id = "b2", title = "Bravo")

                val snapshot = repo.getBookListItems(listOf("b1", "b2"))

                repo.observeBookListItems(listOf("b1", "b2")).test {
                    val reactive = awaitItem()
                    // Same ids and titles — reactive and suspend overloads use the same mapping.
                    reactive.sortedBy { it.id.value }.map { it.id.value } shouldBe
                        snapshot.sortedBy { it.id.value }.map { it.id.value }
                    reactive.sortedBy { it.id.value }.map { it.title } shouldBe
                        snapshot.sortedBy { it.id.value }.map { it.title }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeBookListItems(ids) with empty ids list emits empty list immediately") {
            withTestRepo { repo, _ ->
                repo.observeBookListItems(emptyList()).test {
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Builds an in-memory database and a [BookRepositoryImpl] wired with minimal mocked
 * dependencies, runs [block], then closes the database.
 */
private fun withTestRepo(
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

/** Seeds a minimal book into Room via the canonical sync write path. */
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
    handler.onCatchUpItem(
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
