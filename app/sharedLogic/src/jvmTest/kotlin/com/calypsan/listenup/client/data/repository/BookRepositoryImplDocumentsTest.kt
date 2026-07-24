package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.local.db.BookDocumentEntity
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [BookRepositoryImpl.observeBookListItems] (no-arg overload)
 * verifying that [com.calypsan.listenup.client.domain.model.BookListItem.hasDocuments]
 * is set correctly based on the presence of rows in [book_documents].
 */
class BookRepositoryImplDocumentsTest :
    FunSpec({

        test("observeBookListItems sets hasDocuments=true for a book with a document and false for one without") {
            withTestRepoForDocuments { repo, db ->
                seedRoomBook(db, id = "b1", title = "Has Docs")
                seedRoomBook(db, id = "b2", title = "No Docs")

                // Give b1 a document.
                db.bookDocumentDao().upsertAll(
                    listOf(
                        BookDocumentEntity(
                            bookId = BookId("b1"),
                            index = 0,
                            id = "doc-1",
                            filename = "companion.pdf",
                            format = "pdf",
                            size = 2048L,
                            hash = "deadbeef",
                        ),
                    ),
                )

                repo.observeBookListItems().test {
                    val items = awaitItem()
                    val b1 = items.first { it.title == "Has Docs" }
                    val b2 = items.first { it.title == "No Docs" }

                    b1.hasDocuments shouldBe true
                    b2.hasDocuments shouldBe false

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeBookListItems sets hasDocuments=false when no documents exist") {
            withTestRepoForDocuments { repo, db ->
                seedRoomBook(db, id = "b1", title = "Alone")

                repo.observeBookListItems().test {
                    val items = awaitItem()
                    items.first().hasDocuments shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun withTestRepoForDocuments(
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
private suspend fun seedRoomBook(
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
            documents = emptyList(),
            revision = 1L,
            updatedAt = 100L,
            createdAt = 1L,
            deletedAt = null,
        ),
        isTombstone = false,
    )
}
