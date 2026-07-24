package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failureOf
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

/**
 * Proves [PlaybackPreparer.fetchBookFromServer] decodes the contract [BookSyncPayload] and delegates
 * the write to [SyncDomainHandler.onCatchUpItem] — the shared, atomic aggregate write-through that
 * also drives the RPC on-demand fetch. The handler owns the atomicity guarantee (rollback behaviour
 * is exercised in [BookRepositoryImplTest]); this test confirms the delegation wiring.
 *
 * Replaced the prior `bookIngestPort.upsertWithAudioFiles` path: a single decode →
 * persist seam, no parallel mapping that can drift from the contract.
 */
class PlaybackManagerFallbackFetchAtomicityTest :
    FunSpec({
        // Minimal-valid [BookSyncPayload] (the wire type GET /api/v1/books/{id} returns). Only `id`
        // matters for delegation; everything else is a sensible constant.
        fun bookPayload(id: String): BookSyncPayload =
            BookSyncPayload(
                id = id,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Delegation Test",
                sortTitle = null,
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
                rootRelPath = "books/delegation-test",
                inode = null,
                scannedAt = 1L,
                contributors = emptyList(),
                series = emptyList(),
                audioFiles = emptyList(),
                chapters = emptyList(),
                revision = 1L,
                updatedAt = 1L,
                createdAt = 1L,
                deletedAt = null,
            )

        fun preparer(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            channel: RpcChannel<BookService>,
            handler: SyncDomainHandler<BookSyncPayload>,
        ): PlaybackPreparer =
            PlaybackPreparer(
                serverConfig = mock(),
                playbackPreferences = mock(),
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = mock(),
                progressTracker = buildProgressTracker(),
                tokenProvider = mock(),
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = mock(),
                prepareRepository = testPlaybackPrepareRepository("af-1"),
                channel = channel,
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = handler,
            )

        test("fetchBookFromServer delegates the write to bookSyncDomainHandler.onCatchUpItem") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookService: BookService = mock()
                    val handler = mock<SyncDomainHandler<BookSyncPayload>>()
                    everySuspend { handler.onCatchUpItem(any(), any()) } returns AppResult.Success(Unit)
                    everySuspend { bookService.getBook(any()) } returns AppResult.Success(bookPayload("book-1"))

                    val result =
                        preparer(db, RpcChannel.forTest(bookService), handler).fetchBookFromServer(BookId("book-1"))

                    withClue("fetchBookFromServer should return true on success") { result shouldBe true }
                    verifySuspend(VerifyMode.exactly(1)) {
                        handler.onCatchUpItem(any<BookSyncPayload>(), any())
                    }
                }
            } finally {
                db.close()
            }
        }

        test("fetchBookFromServer returns false when onCatchUpItem returns Failure") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookService: BookService = mock()
                    val handler = mock<SyncDomainHandler<BookSyncPayload>>()
                    everySuspend { handler.onCatchUpItem(any(), any()) } returns failureOf("persistence error")
                    everySuspend { bookService.getBook(any()) } returns AppResult.Success(bookPayload("book-fail"))

                    val result =
                        preparer(db, RpcChannel.forTest(bookService), handler).fetchBookFromServer(BookId("book-fail"))

                    withClue("fetchBookFromServer should return false when persistence fails") { result shouldBe false }
                    verifySuspend(VerifyMode.exactly(1)) {
                        handler.onCatchUpItem(any<BookSyncPayload>(), any())
                    }
                }
            } finally {
                db.close()
            }
        }
    })
