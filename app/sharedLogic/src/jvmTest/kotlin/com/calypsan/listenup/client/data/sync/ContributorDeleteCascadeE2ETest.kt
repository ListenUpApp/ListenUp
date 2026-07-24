package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val CONTRIBUTOR_NAME = "To Delete"
private const val BOOK_ONE_ID = "cascade-b1"
private const val BOOK_TWO_ID = "cascade-b2"

/**
 * Tier 3 e2e test for the Books-C1 contributor-delete cascade: a client-side call
 * to [com.calypsan.listenup.client.domain.repository.ContributorEditRepository.deleteContributor]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `ContributorService`, the server hard-deletes all junction rows linking the
 * contributor to its books, re-upserts each affected book with the contributor
 * stripped, soft-deletes the contributor, and THREE SSE events flow back through
 * the client `SyncEngine`:
 *
 *  1. `books` domain `Updated` — for the first book, junction list now empty
 *  2. `books` domain `Updated` — for the second book, junction list now empty
 *  3. `contributors` domain `Deleted` — for the contributor itself
 *
 * All three events must land in client Room: both books' `book_contributors`
 * junction rows are gone (the [BookMirrorApply.applyContributors] path
 * replaces the junction set on every book upsert), and the contributor row carries
 * `deletedAt != null` (the `contributors` domain's `Deleted` branch sets
 * the tombstone). The poll witness is the empty `getByBookId` join for both books
 * combined with a non-null `deletedAt` on the contributor row — the cascade is
 * only fully applied when all three conditions hold.
 *
 * Server-side `deleteContributor` semantics (NotFound, atomic rollback, junction
 * mass-delete, FTS reindex) are covered by `:server`'s `ContributorServiceImplTest`.
 * This file proves the cross-domain wiring: one client call → two book.Updated +
 * one contributor.Deleted SSE events → three Room writes.
 */
class ContributorDeleteCascadeE2ETest :
    FunSpec({

        test(
            "deleteContributor cascade: two book.Updated + one contributor.Deleted SSE events " +
                "land junction wipe + tombstone in client Room",
        ) {
            withClientSyncEngineAgainstServer {
                // Seed the contributor first — resolveOrCreate publishes a contributor.Created
                // event the engine will catch up on once started. Then build two books that
                // link to this contributor through the junction.
                val contributorId = serverContributorRepository.resolveOrCreate(CONTRIBUTOR_NAME, sortName = null)
                val linkedContributor =
                    BookContributorPayload(
                        id = contributorId.value,
                        name = CONTRIBUTOR_NAME,
                        sortName = null,
                        role = "author",
                        creditedAs = null,
                    )

                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(
                    bookFixture(
                        id = BOOK_ONE_ID,
                        title = "Cascade Book One",
                        contributors = listOf(linkedContributor),
                    ),
                )
                serverBookRepository.upsert(
                    bookFixture(
                        id = BOOK_TWO_ID,
                        title = "Cascade Book Two",
                        contributors = listOf(linkedContributor),
                    ),
                )

                // Wait for the seed catch-up: both books linked to the contributor through the
                // INNER-JOIN witness. Until both `book_contributors` junctions are present,
                // the cascade-empty assertion later would pass trivially.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.contributorDao().getByBookId(BOOK_ONE_ID).isEmpty() ||
                        clientDatabase.contributorDao().getByBookId(BOOK_TWO_ID).isEmpty()
                    ) {
                        // SSE delivery latency is non-deterministic; poll the real query.
                    }
                }

                // Issue the delete over the real kotlinx.rpc transport.
                val result =
                    contributorEditRepository.deleteContributor(ContributorId(contributorId.value))
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // deleteContributor is now offline-first: the optimistic local cascade + tombstone
                // satisfy the CLIENT witness immediately, so the loop must also wait for the durable
                // op to drain to the server (which hard-deletes the junctions, strips each book, and
                // soft-deletes the contributor) — otherwise the server-side assertions below race the
                // drain. The two `book.Updated` + one `contributor.Deleted` echoes then reconcile the
                // optimistic state (idempotent re-apply).
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (true) {
                        val clientDone =
                            clientDatabase.contributorDao().getByBookId(BOOK_ONE_ID).isEmpty() &&
                                clientDatabase.contributorDao().getByBookId(BOOK_TWO_ID).isEmpty() &&
                                clientDatabase.contributorDao().getById(contributorId.value)?.deletedAt != null
                        val serverDone =
                            serverBookRepository.findById(BookId(BOOK_ONE_ID))?.contributors?.isEmpty() == true &&
                                serverBookRepository.findById(BookId(BOOK_TWO_ID))?.contributors?.isEmpty() == true &&
                                serverContributorRepository.findById(contributorId.value)?.deletedAt != null
                        if (clientDone && serverDone) break
                    }
                }

                // Dual assertion against the server side: both books carry no contributors,
                // and the contributor row carries deletedAt != null — proving the server end
                // of the round trip is the one driving Room.
                serverBookRepository
                    .findById(BookId(BOOK_ONE_ID))
                    .shouldNotBeNull()
                    .contributors
                    .shouldBeEmpty()
                serverBookRepository
                    .findById(BookId(BOOK_TWO_ID))
                    .shouldNotBeNull()
                    .contributors
                    .shouldBeEmpty()
                serverContributorRepository
                    .findById(contributorId.value)
                    .shouldNotBeNull()
                    .deletedAt
                    .shouldNotBeNull()

                // And the client-side tombstone is observable too — final crisp assertion
                // beyond the polling-loop exit condition.
                clientDatabase
                    .contributorDao()
                    .getById(contributorId.value)
                    .shouldNotBeNull()
                    .deletedAt
                    .shouldNotBeNull()
                clientDatabase.contributorDao().getByBookId(BOOK_ONE_ID) shouldBe emptyList()
                clientDatabase.contributorDao().getByBookId(BOOK_TWO_ID) shouldBe emptyList()
            }
        }
    })

private fun bookFixture(
    id: String,
    title: String,
    contributors: List<BookContributorPayload>,
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
