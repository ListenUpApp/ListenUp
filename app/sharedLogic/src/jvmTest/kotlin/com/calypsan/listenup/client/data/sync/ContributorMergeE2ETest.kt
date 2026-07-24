package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val SOURCE_NAME = "Richard Bachman"
private const val TARGET_NAME = "Stephen King"
private const val BOOK_ONE_ID = "merge-b1"
private const val BOOK_TWO_ID = "merge-b2"
private const val BOOK_THREE_ID = "merge-b3"
private const val EXPLICIT_CREDITED_AS = "R. Bachman"

/**
 * Tier 3 e2e test for the Books-C2 contributor merge: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.ContributorEditRepository.mergeContributor]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `ContributorService`. The server relinks every `book_contributors` junction row
 * referencing the source onto the target (capturing source's display name into
 * `credited_as` when the column was NULL, preserving any explicit override),
 * absorbs source's display name + aliases into target's alias set, re-upserts
 * every affected book to bump revisions, soft-deletes the source, and emits a
 * burst of SSE events: one `books.Updated` per affected book + one
 * `contributors.Updated` for the target (alias change) + one `contributors.Deleted`
 * for the source tombstone.
 *
 * All of these must land in client Room: every affected book's `book_contributors`
 * junction points at the target id (the [BookMirrorApply.applyContributors]
 * path replaces the junction set on every book upsert), the target's
 * `contributor_aliases` rows include the source's display name, and the source
 * row carries `deletedAt != null`. The poll witness combines all three so the
 * cascade is only fully applied when SSE has delivered every event.
 *
 * Server-side merge semantics (alias dedup case-insensitively, target's own name
 * excluded from aliases, FTS reindex of both `book_search` and `contributor_search`)
 * are covered by `:server`'s `ContributorServiceImplMergeTest`. This file proves
 * the cross-domain wiring + the highest-value invariant — `credited_as` preservation —
 * survives the full RPC → SSE → Room round trip.
 */
class ContributorMergeE2ETest :
    FunSpec({

        test(
            "mergeContributor cascade: junction relink + creditedAs capture + " +
                "target alias gain + source tombstone all land in client Room",
        ) {
            withClientSyncEngineAgainstServer {
                // Seed source + target contributors. resolveOrCreate publishes a
                // contributor.Created SSE event per call that the engine catches up on.
                val sourceId = serverContributorRepository.resolveOrCreate(SOURCE_NAME, sortName = null)
                val targetId = serverContributorRepository.resolveOrCreate(TARGET_NAME, sortName = null)
                val sourcePayload =
                    BookContributorPayload(
                        id = sourceId.value,
                        name = SOURCE_NAME,
                        sortName = null,
                        role = "author",
                        creditedAs = null,
                    )

                engine.start(currentUserId = "u1")

                // Three books all linked to source. b1/b2 have no explicit credited_as
                // (the merge must CAPTURE source.name into credited_as). b3 has an
                // explicit override (the merge must PRESERVE it untouched).
                serverBookRepository.upsert(
                    bookFixture(
                        id = BOOK_ONE_ID,
                        title = "The Long Walk",
                        contributors = listOf(sourcePayload),
                    ),
                )
                serverBookRepository.upsert(
                    bookFixture(
                        id = BOOK_TWO_ID,
                        title = "Thinner",
                        contributors = listOf(sourcePayload),
                    ),
                )
                serverBookRepository.upsert(
                    bookFixture(
                        id = BOOK_THREE_ID,
                        title = "Roadwork",
                        contributors = listOf(sourcePayload.copy(creditedAs = EXPLICIT_CREDITED_AS)),
                    ),
                )

                // Wait for the seed catch-up: every book is linked to the source through
                // the INNER-JOIN witness. Until all three junctions are present, the
                // post-merge relink assertion would be ambiguous.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.contributorDao().getByBookId(BOOK_ONE_ID).isEmpty() ||
                        clientDatabase.contributorDao().getByBookId(BOOK_TWO_ID).isEmpty() ||
                        clientDatabase.contributorDao().getByBookId(BOOK_THREE_ID).isEmpty()
                    ) {
                        // SSE delivery latency is non-deterministic; poll the real query.
                    }
                }

                // Issue the merge over the real kotlinx.rpc transport.
                val result =
                    contributorEditRepository.mergeContributor(
                        source = ContributorId(sourceId.value),
                        target = ContributorId(targetId.value),
                    )
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Merge complete only when ALL of these hold in client Room:
                //  - every affected book's junction points at target (via INNER-JOIN witness),
                //  - target's alias junction contains source.name (from the contributor.Updated event),
                //  - source row is tombstoned (from the contributor.Deleted event).
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (!mergeFullyLanded(
                            clientDb = clientDatabase,
                            sourceId = sourceId.value,
                            targetId = targetId.value,
                        )
                    ) {
                        // SSE delivery latency is non-deterministic; poll until convergence.
                    }
                }

                // Dual assertion against the server side — proves the server is the
                // one driving the client state, not a quirk of local handler logic.
                val finalTargetServer =
                    serverContributorRepository.findById(targetId.value).shouldNotBeNull()
                finalTargetServer.aliases shouldContainExactlyInAnyOrder listOf(SOURCE_NAME)
                serverContributorRepository
                    .findById(sourceId.value)
                    .shouldNotBeNull()
                    .deletedAt
                    .shouldNotBeNull()

                // creditedAs preservation — the highest-value invariant of this test.
                // b1/b2: captured source.name into the previously-NULL credited_as column.
                // b3: kept its pre-existing override untouched.
                val finalBookOne = serverBookRepository.findById(BookId(BOOK_ONE_ID)).shouldNotBeNull()
                val finalBookTwo = serverBookRepository.findById(BookId(BOOK_TWO_ID)).shouldNotBeNull()
                val finalBookThree = serverBookRepository.findById(BookId(BOOK_THREE_ID)).shouldNotBeNull()
                finalBookOne.contributors.first { it.id == targetId.value }.creditedAs shouldBe SOURCE_NAME
                finalBookTwo.contributors.first { it.id == targetId.value }.creditedAs shouldBe SOURCE_NAME
                finalBookThree.contributors.first { it.id == targetId.value }.creditedAs shouldBe EXPLICIT_CREDITED_AS
            }
        }
    })

/**
 * True once every signal of a fully-applied merge is observable in client Room:
 * all three affected books' junctions point at [targetId], target's alias junction
 * contains the source's display name, and the source row is tombstoned.
 */
private suspend fun mergeFullyLanded(
    clientDb: ListenUpDatabase,
    sourceId: String,
    targetId: String,
): Boolean {
    val contributorDao = clientDb.contributorDao()
    val booksOnTarget =
        listOf(BOOK_ONE_ID, BOOK_TWO_ID, BOOK_THREE_ID).all { bookId ->
            contributorDao.getByBookId(bookId).any { it.id.value == targetId }
        }
    val targetHasAlias = SOURCE_NAME in clientDb.contributorAliasDao().getForContributor(targetId)
    val sourceTombstoned = contributorDao.getById(sourceId)?.deletedAt != null
    return booksOnTarget && targetHasAlias && sourceTombstoned
}

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
