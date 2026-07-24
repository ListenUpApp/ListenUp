package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.sync.domains.contributorsDomain
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Cross-domain integration test for the contributor "self-heal" invariant (Books-B1 §12.2).
 *
 * The sync substrate provides no cross-domain ordering guarantee: a [BookSyncPayload] and the
 * [ContributorSyncPayload] for a contributor it references can arrive in either order. Both
 * handlers share a single Room database and the two compositions are tested end-to-end:
 *
 * - **Book-first:** [BookMirrorApply] inserts a bootstrap stub (`revision == 0`); a later
 *   [contributorsDomain] event supersedes it with the real entity.
 * - **Contributor-first:** [contributorsDomain] inserts the real row first; a later
 *   stale book event does NOT clobber it.
 */
class ContributorSelfHealIntegrationTest :
    FunSpec({

        test("book-first: contributor stub is superseded by the real contributor event") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    val bookHandler =
                        booksDomain(
                            database = db,
                            mapper = BookEntityMapper(),
                            imageStorage = stubImageStorage(),
                        ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = registry)
                    val contributorHandler =
                        contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), registry)

                    // 1. Book event arrives first — contributor "c1" is not yet in Room.
                    bookHandler.onEvent(
                        bookCreated(
                            bookPayload(
                                id = "b1",
                                contributors = listOf(contrib(id = "c1", name = "Stub Name From Book")),
                            ),
                        ),
                    )

                    // 2. Bootstrap stub was inserted with revision == 0.
                    val stub = db.contributorDao().getById("c1")
                    stub shouldNotBe null
                    stub!!.name shouldBe "Stub Name From Book"
                    stub.revision shouldBe 0L

                    // 3. Real contributor event arrives from the contributors domain.
                    contributorHandler.onEvent(
                        contributorCreated(contributorPayload(id = "c1", name = "Canonical Name", revision = CANONICAL_REVISION)),
                    )

                    // 4. The real entity supersedes the stub — identity and revision are updated.
                    val real = db.contributorDao().getById("c1")
                    real shouldNotBe null
                    real!!.name shouldBe "Canonical Name"
                    real.revision shouldBe CANONICAL_REVISION
                } finally {
                    db.close()
                }
            }
        }

        test("contributor-first: stale book event does not clobber the real contributor row") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    val bookHandler =
                        booksDomain(
                            database = db,
                            mapper = BookEntityMapper(),
                            imageStorage = stubImageStorage(),
                        ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = registry)
                    val contributorHandler =
                        contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), registry)

                    // 1. Real contributor event arrives first.
                    contributorHandler.onEvent(
                        contributorCreated(contributorPayload(id = "c2", name = "Canonical Name", revision = CANONICAL_REVISION)),
                    )

                    // 2. Book event arrives with a stale embedded contributor name.
                    bookHandler.onEvent(
                        bookCreated(
                            bookPayload(
                                id = "b2",
                                contributors = listOf(contrib(id = "c2", name = "Stale Name From Book")),
                            ),
                        ),
                    )

                    // 3. The contributor row must be untouched — contributors domain owns it.
                    val row = db.contributorDao().getById("c2")
                    row shouldNotBe null
                    row!!.name shouldBe "Canonical Name"
                    row.revision shouldBe CANONICAL_REVISION
                } finally {
                    db.close()
                }
            }
        }
    })

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** A revision value that is distinct from 0 (bootstrap stub sentinel) and 1 (default). */
private const val CANONICAL_REVISION = 9L

// ---------------------------------------------------------------------------
// Fixture builders — match the shapes used in the individual handler tests.
// ---------------------------------------------------------------------------

private fun bookCreated(payload: BookSyncPayload): SyncEvent.Created<BookSyncPayload> =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )

private fun contributorCreated(payload: ContributorSyncPayload): SyncEvent.Created<ContributorSyncPayload> =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )

private fun bookPayload(
    id: String,
    contributors: List<BookContributorPayload> = emptyList(),
    chapters: List<BookChapterPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
    audioFiles: List<BookAudioFilePayload> = emptyList(),
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Test Book",
        sortTitle = "Test Book",
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
        series = series,
        audioFiles = audioFiles,
        chapters = chapters,
        revision = 1L,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )

private fun contrib(
    id: String,
    name: String,
): BookContributorPayload =
    BookContributorPayload(
        id = id,
        name = name,
        sortName = name,
        role = "author",
        creditedAs = null,
    )

private fun contributorPayload(
    id: String,
    name: String,
    revision: Long = 1L,
): ContributorSyncPayload =
    ContributorSyncPayload(
        id = id,
        name = name,
        sortName = name,
        revision = revision,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )
