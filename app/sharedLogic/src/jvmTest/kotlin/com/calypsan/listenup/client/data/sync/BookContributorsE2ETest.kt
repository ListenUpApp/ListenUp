package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val NEW_AUTHOR_NAME = "Brand New Author"

/**
 * Tier 3 e2e test for the Books-C1 contributor-set surface: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.BookEditRepository.setBookContributors]
 * with one auto-resolved (id = null) input crosses the live kotlinx.rpc transport into
 * the in-process `:server`'s `BookService`, the server resolves-or-creates the
 * contributor and upserts the book in a single transaction, and TWO SSE events flow
 * back through the client `SyncEngine`:
 *
 *  1. `contributors` domain `Created` — for the freshly-created contributor row
 *  2. `books` domain `Updated` — for the book whose contributor list now includes
 *     the new contributor
 *
 * Both events must land in client Room: the contributor row in `contributors`, the
 * junction row in `book_contributors`. The `INNER JOIN`-backed `getByBookId` query
 * is the single-poll witness that both events have been applied — it returns the
 * joined contributor only when both rows exist.
 *
 * Server-side `setBookContributors` semantics (limits, ordering, dedup) are covered
 * by `:server`'s `BookServiceImplTest`. This file proves the cross-domain wiring:
 * one client call → two SSE domains → two Room writes, observed via the join.
 */
class BookContributorsE2ETest :
    FunSpec({

        test(
            "setBookContributors with one auto-resolved name delivers " +
                "contributor.Created + book.Updated via SSE into client Room",
        ) {
            withClientSyncEngineAgainstServer {
                // Seed a book with no contributors, applied to client Room via the real dispatcher —
                // the deterministic-frame pattern of BookEchoShieldE2ETest, so the in-flight window
                // isn't racing the engine's reactive drain.
                serverBookRepository.upsert(bookFixture(id = "contrib-b1", title = "Seed Book"))
                dispatcher.handle(booksFrame(bookFixture(id = "contrib-b1", title = "Seed Book"), revision = 1))
                clientDatabase.contributorDao().getByBookId("contrib-b1") shouldHaveSize 0

                // Offline-first edit with id = null so the server's `resolveOrCreate` path fires. The
                // new contributor's id is minted server-side, so it CANNOT be linked optimistically —
                // this is the one edit whose result the optimistic write can't mirror; it converges via
                // the book's own echo once the op drains.
                bookEditRepository
                    .setBookContributors(
                        BookId("contrib-b1"),
                        listOf(BookContributorInput(id = null, name = NEW_AUTHOR_NAME, role = "author", position = 0)),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Drain the op for real: client → kotlinx.rpc → server → resolveOrCreate + book upsert.
                queue.drain()
                val serverBook = serverBookRepository.findById(BookId("contrib-b1"))
                checkNotNull(serverBook) { "server-side book row missing after setBookContributors" }
                serverBook.contributors shouldHaveSize 1
                serverBook.contributors.single().name shouldBe NEW_AUTHOR_NAME

                // The op has drained (shield lifted); the book's authoritative echo now applies and the
                // junction (plus a bootstrap contributor stub) lands in client Room — full convergence.
                dispatcher.handle(booksFrame(serverBook.copy(revision = 2, updatedAt = 200L), revision = 2))

                val linked = clientDatabase.contributorDao().getByBookId("contrib-b1")
                linked shouldHaveSize 1
                linked.single().name shouldBe NEW_AUTHOR_NAME
                linked.single().id.value shouldBe serverBook.contributors.single().id
            }
        }
    })

/** Encode [payload] (stamped at [revision]) as a `books` Updated SSE frame the dispatcher can apply. */
private fun booksFrame(
    payload: BookSyncPayload,
    revision: Long,
): SyncFrame {
    val stamped = payload.copy(revision = revision, updatedAt = revision * 100L)
    return SyncFrame(
        revision = revision,
        domain = "books",
        json =
            contractJson.encodeToString(
                SyncEvent.serializer(BookSyncPayload.serializer()),
                SyncEvent.Updated(id = stamped.id, revision = revision, occurredAt = stamped.updatedAt, payload = stamped),
            ),
    )
}

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
