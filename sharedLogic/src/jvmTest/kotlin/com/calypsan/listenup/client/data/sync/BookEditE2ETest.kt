package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.dto.BookUpdate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e test for the Books-C1 edit surface: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.BookEditRepository.updateBook]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `BookService`, the server mutates the row through [com.calypsan.listenup.server.services.BookRepository],
 * the resulting SSE event flows back through the client `SyncEngine`, and the
 * patched title lands in the client Room database — exactly the round trip
 * production performs.
 *
 * Server-side `updateBook` semantics (NotFound, validation, no-op patch) are
 * covered by `:server`'s `BookServiceImplTest`. This file proves the wiring
 * between the two halves: client repository → RPC → server service → SSE →
 * sync handler → Room. Sibling tasks add e2e coverage for `setBookContributors`,
 * the `deleteContributor` cascade, and `deleteBookCover`.
 */
class BookEditE2ETest :
    FunSpec({

        test("updateBook propagates new title to client Room via RPC → SSE → Room round trip") {
            withClientSyncEngineAgainstServer {
                // Start the engine first, then seed via the live SSE tail — the
                // pattern established by `BooksEndToEndTest`. Polling waits until
                // the seed row has landed in client Room so the subsequent edit's
                // SSE emission is observable as a distinct title transition.
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(bookFixture(id = "edit-b1", title = "Original Title"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("edit-b1"))?.title != "Original Title") {
                        // Poll until the SSE tail has applied the seed row.
                    }
                }

                // Issue the edit over the real kotlinx.rpc transport.
                val result =
                    bookEditRepository.updateBook(
                        BookId("edit-b1"),
                        BookUpdate(title = "Updated Title"),
                    )
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // SSE delivers the patched payload; the sync handler writes it into Room.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("edit-b1"))?.title != "Updated Title") {
                        // Poll the real query — SSE delivery latency is non-deterministic.
                    }
                }
                clientDatabase.bookDao().getById(BookId("edit-b1"))?.title shouldBe "Updated Title"
            }
        }
    })

private fun bookFixture(
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
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
