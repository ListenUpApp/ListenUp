package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val BOOK_ID = "cover-delete-b1"
private const val COVER_HASH = "seed-cover-hash-abc123"

/**
 * Tier 3 e2e test for the cover-delete surface: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.BookEditRepository.deleteBookCover]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `BookService`, the server nullifies the book row's cover columns and re-upserts
 * the aggregate, and the resulting `SyncEvent.Updated` (payload.cover == null)
 * flows back through the client `SyncEngine` into Room — the seed's [BookEntity.coverHash]
 * flips from the planted hash to `null`.
 *
 * Server-side `deleteBookCover` semantics (NotFound, NotPresent, filesystem-side
 * delete, embedded-source handling) are covered by `:server`'s
 * `BookServiceImplDeleteCoverTest`. This file proves the wiring between the two
 * halves: client repository → RPC → server service → SSE → sync handler → Room,
 * specifically for the null-cover transition.
 *
 * The on-disk file-delete side-effect is deliberately not asserted here — the
 * seed plants only the DB columns (no file on disk), and `CoverStorage.delete` is
 * a best-effort idempotent no-op on a missing path. That branch already has
 * direct coverage in `:server:test`.
 */
class CoverDeleteE2ETest :
    FunSpec({

        test("deleteBookCover propagates null cover state to client Room via RPC → SSE → Room round trip") {
            withClientSyncEngineAgainstServer {
                // Start the engine first, then seed via the live SSE tail with a non-null
                // cover so the delete has something to remove (server returns NotPresent
                // when current.cover == null). Poll until the seed's coverHash has landed
                // in client Room so the subsequent null transition is observable.
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(
                    bookFixture(
                        id = BOOK_ID,
                        title = "Cover Book",
                        cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = COVER_HASH),
                    ),
                )
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId(BOOK_ID))?.coverHash != COVER_HASH) {
                        // Poll until the SSE tail has applied the seed row with cover.
                    }
                }

                // Issue the delete. Offline-first: the cover pointer is cleared in Room optimistically
                // and a durable `books` op is enqueued.
                val result = bookEditRepository.deleteBookCover(BookId(BOOK_ID))
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Await the server round-trip — the engine drains the op over RPC, nulling the cover
                // server-side. A yielding delay keeps the drain coroutine (same Dispatchers.Default
                // pool) from being starved by a tight busy-spin.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (serverBookRepository.findById(BookId(BOOK_ID))?.cover != null) {
                        delay(50)
                    }
                }

                // The optimistic Room write cleared the local cover pointer; the SSE echo confirms it.
                clientDatabase
                    .bookDao()
                    .getById(BookId(BOOK_ID))
                    ?.coverHash
                    .shouldBeNull()

                // Dual assertion against the server side: the drained op nulled the cover.
                val serverBook = serverBookRepository.findById(BookId(BOOK_ID)).shouldNotBeNull()
                serverBook.cover.shouldBeNull()
                // Sanity: the book row itself still exists — delete-cover is not delete-book.
                clientDatabase
                    .bookDao()
                    .getById(BookId(BOOK_ID))
                    .shouldNotBeNull()
                    .title shouldBe "Cover Book"
            }
        }
    })

private fun bookFixture(
    id: String,
    title: String,
    cover: CoverPayload?,
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
        cover = cover,
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
