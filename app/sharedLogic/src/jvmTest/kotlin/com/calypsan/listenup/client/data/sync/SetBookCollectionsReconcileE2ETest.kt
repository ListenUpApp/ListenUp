package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.COLLECTION_E2E_MEMBER_ID
import com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP_TIMEOUT = 30.seconds

/**
 * Poll interval between Room reads. A generous, non-trivial delay is load-bearing: the SSE-frame
 * collector, the dispatcher, and the reconcile (`handleAccessChanged` → access-filtered catch-up
 * → `pruneTo`) all run on `Dispatchers.Default` — the same pool the test coroutine polls on. A
 * tight busy-spin starves those coroutines so the `AccessChanged` frame is never delivered or
 * processed; yielding the pool for a real interval each tick lets the round-trip make progress.
 */
private val POLL_INTERVAL = 100.milliseconds

/**
 * Tier 3 E2E — the **security proof** for the admin `setBookCollections` write path.
 * Proves end-to-end that when an admin replace-sets a book's
 * collection membership, the per-user `AccessChanged` control signal flows through the
 * member's real client [SyncEngine] and reconciles their Room: a member who LOSES their
 * access path prunes the book; a member who GAINS one receives it.
 *
 * Real client sync engine ↔ real in-process `:server` ↔ real SQLite, via
 * [withCollectionSyncEngineAgainstServer][com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer].
 * The **admin** drives `setBookCollections` through the access-gated `CollectionService`;
 * the **member** runs the engine and asserts against their Room DB.
 *
 * **The fixture both directions lean on.** Two admin-owned collections:
 *  - **A — shared with the member.** The member is an active-share member, so any
 *    add/remove of A in a `setBookCollections` diff emits an `AccessChanged` *addressed to
 *    the member* (that's the enumerable edge `setBookCollections` reconciles), and a book in
 *    A is *reachable* by the member ([BookAccessPolicy] active-share rule).
 *  - **B — private, not shared.** No `AccessChanged` reaches the member from B, and a book
 *    in B alone is *unreachable* by the member.
 *
 * Moving a book between A and B therefore both (a) flips the member's reachability of the
 * book and (b) routes an `AccessChanged` to the member (because A is in the add/remove diff)
 * — the exact pairing the reconcile needs.
 *
 * Non-vacuity is asserted in-band: each direction checks the *opposite* presence state
 * before the mutation (present-and-live before revoke; absent before grant), so a test that
 * never synced the book — or one where the gate silently let everything through — fails
 * rather than passing trivially. Both checks are *synchronous* reads immediately after
 * `engine.start` returns: `runStart` runs the access-filtered catch-up to completion before
 * connecting SSE, so the pre-state is settled without a poll loop. Polling there would race the
 * server firehose's per-user control subscriber (the `AccessChanged` channel is `replay = 0`,
 * so a frame published before that subscriber is live is dropped) and intermittently miss the
 * load-bearing signal.
 */
class SetBookCollectionsReconcileE2ETest :
    FunSpec({

        // ── Revoke direction (the leak case) ────────────────────────────────────────────────

        test("setBookCollections moving a book out of the member's reach prunes it from Room")
            .config(timeout = 2.minutes) {
                withCollectionSyncEngineAgainstServer {
                    serverBookRepository.upsert(bookPayload("book-revoke")).requireSuccess()

                    // A: shared with the member (member-reachable). B: private (unreachable).
                    val shared = adminCollections.createEmptyCollection("Shared A")
                    val privateColl = adminCollections.createEmptyCollection("Private B")
                    adminCollections
                        .shareCollection(shared, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()

                    // The book starts in A only → reachable by the member.
                    adminCollections.setBookCollections(BookId("book-revoke"), listOf(shared)).requireSuccess()

                    engine.start(currentUserId = COLLECTION_E2E_MEMBER_ID)

                    // PRESENT-before: `engine.start` suspends until the initial access-filtered
                    // catch-up has run (catch-up precedes SSE connect in `runStart`), so the
                    // book is already in the member's Room — checked synchronously, with no poll
                    // loop that would compete with the firehose coming up. Without this check the
                    // prune assertion below would be vacuous.
                    clientDatabase.bookDao().getById(BookId("book-revoke")).shouldNotBeNull()
                    clientDatabase.bookDao().getById(BookId("book-revoke"))!!.deletedAt shouldBe null

                    // ── The mutation under test ──
                    // Replace-set the book to B only: A is in the REMOVED diff (member is an
                    // active-share member of A → AccessChanged addressed to them); B is private
                    // (member not reachable). The member re-derives a smaller accessible set and
                    // prunes the book — bookDao.getById does NOT filter tombstones, so the prune
                    // surfaces as a non-null deletedAt on the still-present row.
                    adminCollections
                        .setBookCollections(BookId("book-revoke"), listOf(privateColl))
                        .requireSuccess()

                    awaitBookTombstoned(clientDatabase, "book-revoke")
                    clientDatabase
                        .bookDao()
                        .getById(BookId("book-revoke"))!!
                        .deletedAt
                        .shouldNotBeNull()
                }
            }

        // ── Grant direction ─────────────────────────────────────────────────────────────────

        test("setBookCollections moving a book into the member's reach surfaces it in Room")
            .config(timeout = 2.minutes) {
                withCollectionSyncEngineAgainstServer {
                    serverBookRepository.upsert(bookPayload("book-grant")).requireSuccess()

                    val shared = adminCollections.createEmptyCollection("Shared A")
                    val privateColl = adminCollections.createEmptyCollection("Private B")
                    adminCollections
                        .shareCollection(shared, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()

                    // The book starts in B only → unreachable by the member.
                    adminCollections.setBookCollections(BookId("book-grant"), listOf(privateColl)).requireSuccess()

                    engine.start(currentUserId = COLLECTION_E2E_MEMBER_ID)

                    // ABSENT-before: `engine.start` suspends until the initial access-filtered
                    // catch-up has run (catch-up precedes SSE connect in `runStart`), so the
                    // private-only book was offered to catch-up and gated out — not merely
                    // un-synced-yet. Without this the appear assertion below would be vacuous.
                    clientDatabase.bookDao().getById(BookId("book-grant")).shouldBeNull()

                    // ── The mutation under test ──
                    // Replace-set the book to A only: A is in the ADDED diff (member is an
                    // active-share member → AccessChanged addressed to them); B is REMOVED. The
                    // member re-derives a larger accessible set and upserts the now-reachable book
                    // as a LIVE (non-tombstoned) row.
                    adminCollections
                        .setBookCollections(BookId("book-grant"), listOf(shared))
                        .requireSuccess()

                    awaitBookLive(clientDatabase, "book-grant")
                    val book = clientDatabase.bookDao().getById(BookId("book-grant"))
                    book.shouldNotBeNull()
                    book.deletedAt shouldBe null
                }
            }
    })

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Polls the member's Room until [id] is present AND live (non-tombstoned), or fails after timeout. */
private suspend fun awaitBookLive(
    database: ListenUpDatabase,
    id: String,
) = withTimeout(ROUND_TRIP_TIMEOUT) {
    while (database.bookDao().getById(BookId(id)).let { it == null || it.deletedAt != null }) {
        delay(POLL_INTERVAL)
    }
}

/** Polls the member's Room until [id] is present but tombstoned (`deletedAt != null`), or fails after timeout. */
private suspend fun awaitBookTombstoned(
    database: ListenUpDatabase,
    id: String,
) = withTimeout(ROUND_TRIP_TIMEOUT) {
    while (database.bookDao().getById(BookId(id))?.deletedAt == null) {
        delay(POLL_INTERVAL)
    }
}

private suspend fun CollectionService.createEmptyCollection(name: String): CollectionId {
    val created = createCollection("test-library", name).requireSuccess()
    return created.id
}

private fun <T> AppResult<T>.requireSuccess(): T {
    require(this is AppResult.Success) { "expected Success but got $this" }
    return data
}

private fun bookPayload(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Book $id",
        sortTitle = "Book $id",
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
        contributors = emptyList<BookContributorPayload>(),
        series = emptyList<BookSeriesPayload>(),
        audioFiles = emptyList<BookAudioFilePayload>(),
        chapters = emptyList<BookChapterPayload>(),
        revision = 1L,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )
