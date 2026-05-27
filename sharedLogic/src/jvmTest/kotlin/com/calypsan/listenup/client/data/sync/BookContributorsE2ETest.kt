package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
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
                // Seed a book with no contributors. Start the engine first so the
                // seed arrives through the live SSE tail — same pattern as BookEditE2ETest.
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(bookFixture(id = "contrib-b1", title = "Seed Book"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("contrib-b1")) == null) {
                        // Poll until the SSE tail has applied the seed row.
                    }
                }
                // Pre-condition: no contributors linked to the book yet.
                clientDatabase.contributorDao().getByBookId("contrib-b1") shouldHaveSize 0

                // Issue the contributor-set over the real kotlinx.rpc transport with
                // id = null so the server's `resolveOrCreate` path fires and creates
                // a brand-new contributor row.
                val result =
                    bookEditRepository.setBookContributors(
                        BookId("contrib-b1"),
                        listOf(
                            BookContributorInput(
                                id = null,
                                name = NEW_AUTHOR_NAME,
                                role = "author",
                                position = 0,
                            ),
                        ),
                    )
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // The INNER JOIN witness: getByBookId only returns the joined
                // contributor when BOTH the `contributors` Created event AND the
                // `books` Updated event have been applied — the contributor row
                // must exist AND the junction row must exist. A single poll covers
                // both SSE deliveries.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.contributorDao().getByBookId("contrib-b1").isEmpty()) {
                        // SSE delivery latency is non-deterministic; poll the real query.
                    }
                }

                val linked = clientDatabase.contributorDao().getByBookId("contrib-b1")
                linked shouldHaveSize 1
                linked.single().name shouldBe NEW_AUTHOR_NAME

                // Dual assertion against the server side: the upserted book row's
                // payload should now carry exactly the same auto-resolved contributor,
                // proving the server end of the round trip is the one driving Room.
                val serverBook = serverBookRepository.findById(BookId("contrib-b1"))
                checkNotNull(serverBook) { "server-side book row missing after setBookContributors" }
                serverBook.contributors shouldHaveSize 1
                serverBook.contributors.single().name shouldBe NEW_AUTHOR_NAME
                serverBook.contributors.single().id shouldBe linked.single().id.value
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
