package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The real-server e2e proof the dead `clientOpId` echo-dedup never gave: an optimistic book edit is
 * made through the real repository, its op drains to the in-process `:server` over real kotlinx.rpc,
 * and an inbound server snapshot for the same book arrives while the edit is still in flight. The
 * entity-level shield must hold the optimistic state — no flicker/revert — and then converge once the
 * op drains.
 *
 * Inbound frames are driven through the harness's real dispatcher (the same seam the live SSE tail
 * feeds) so the in-flight window is deterministic rather than racing the engine's reactive drain.
 * The drain and the resulting server mutation are real: [serverBookRepository] is asserted to prove
 * the client → RPC → server write path executed.
 */
class BookEchoShieldE2ETest :
    FunSpec({

        test("an in-flight book edit is not reverted by a concurrent inbound snapshot, then converges") {
            withClientSyncEngineAgainstServer {
                // Seed the book on the server AND apply it to client Room (the real books handler).
                serverBookRepository.upsert(bookFixture(id = "b1", title = "Original Title", revision = 0))
                dispatcher.handle(booksFrame(bookFixture(id = "b1", title = "Original Title", revision = 1)))
                clientDatabase.bookDao().getById(BookId("b1"))?.title shouldBe "Original Title"

                // The user edits the title. The real repository writes Room optimistically and enqueues
                // a durable `books` op — the edit is now in flight (the engine is not draining here).
                bookEditRepository
                    .updateBook(BookId("b1"), BookUpdate(title = "User Edit"))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                clientDatabase.bookDao().getById(BookId("b1"))?.title shouldBe "User Edit"

                // A concurrent server-side snapshot for the SAME book arrives while the op is queued.
                // Without the shield this reverts Room to "Concurrent Server State" — the flicker.
                dispatcher.handle(booksFrame(bookFixture(id = "b1", title = "Concurrent Server State", revision = 2)))
                clientDatabase.bookDao().getById(BookId("b1"))?.title shouldBe "User Edit"

                // Drain the op for real: client → kotlinx.rpc → server → BookRepository mutation.
                queue.drain()
                serverBookRepository.findById(BookId("b1"))?.title shouldBe "User Edit"

                // The op's own echo (now that it has drained) applies and the book converges.
                dispatcher.handle(booksFrame(bookFixture(id = "b1", title = "User Edit", revision = 3)))
                clientDatabase.bookDao().getById(BookId("b1"))?.title shouldBe "User Edit"
            }
        }
    })

private fun booksFrame(payload: BookSyncPayload) =
    SyncFrame(
        revision = payload.revision,
        domain = "books",
        json =
            contractJson.encodeToString(
                SyncEvent.serializer(BookSyncPayload.serializer()),
                SyncEvent.Updated(
                    id = payload.id,
                    revision = payload.revision,
                    occurredAt = payload.updatedAt,
                    payload = payload,
                ),
            ),
    )

private fun bookFixture(
    id: String,
    title: String,
    revision: Long,
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
        revision = revision,
        updatedAt = revision * 100L,
        createdAt = 0L,
        deletedAt = null,
    )
