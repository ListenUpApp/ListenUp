package com.calypsan.listenup.client.data.sync

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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP_TIMEOUT = 30.seconds

/**
 * Tier 3 E2E for the collection sync handlers + the `AccessChanged` reconcile/prune.
 *
 * Two roles share one in-process server (see
 * [withCollectionSyncEngineAgainstServer][com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer]):
 * the **admin** owns and mutates collections through the real, access-gated `CollectionService`;
 * the **member** runs the client [SyncEngine] and asserts against their Room DB. The member's
 * catch-up + SSE firehose are gated by the server's `BookAccessPolicy`, exactly as in production.
 *
 * **Step 1 — handler round-trips.** Admin-owned (so admin-visible) collection / membership /
 * share rows replay through the member's catch-up and land in Room via the three real handlers
 * (`collections`, `collection_books`, `collection_shares`). Driven against an admin-shared
 * collection so the member is entitled to see every row.
 *
 * **Step 2 — the reconcile/prune deliverable.** Admin shares a *private* collection with the
 * member → the firehose emits `AccessChanged` → the member's engine re-derives + upserts the
 * collection's book into Room (grant direction). Admin **revokes** → a second `AccessChanged` →
 * the member's engine prunes that book from Room (revoke direction) — no full resync, no cursor
 * movement.
 *
 * Async waits poll real Room queries inside [withTimeout], matching the
 * [TagSyncE2ETest] idiom.
 */
class CollectionSyncAndReconcileE2ETest :
    FunSpec({

        // ── Step 1: handler round-trips (the three collection domains) ──────────────────────

        test("shared collection's rows replay into the member's Room via the real handlers")
            .config(timeout = 2.minutes) {
                withCollectionSyncEngineAgainstServer {
                    serverBookRepository.upsert(bookPayload("book-1")).requireSuccess()

                    // Admin creates a private collection, adds the book, and shares it with the
                    // member — so the member is entitled to see all three domains' rows.
                    val collectionId = adminCollections.createPrivateCollection("Shared", "book-1")
                    adminCollections
                        .shareCollection(collectionId, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()

                    engine.start(currentUserId = COLLECTION_E2E_MEMBER_ID)

                    // collections domain: the collection row lands.
                    awaitClientCollection(clientDatabase, collectionId.value)
                    clientDatabase.collectionDao().getById(collectionId.value)?.name shouldBe "Shared"

                    // collection_books domain: the membership junction lands.
                    val junction =
                        withTimeout(ROUND_TRIP_TIMEOUT) {
                            var row = clientDatabase.collectionBookDao().findByKey(collectionId.value, "book-1")
                            while (row == null || row.deletedAt != null) {
                                row = clientDatabase.collectionBookDao().findByKey(collectionId.value, "book-1")
                            }
                            row
                        }
                    junction.bookId shouldBe "book-1"

                    // collection_shares domain: the share row lands (visible because it names the member).
                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.collectionShareDao().liveIds().isEmpty()) {
                            // poll until the share replays
                        }
                    }
                    val share = clientDatabase.collectionShareDao().liveIds()
                    share.size shouldBe 1
                }
            }

        // ── Step 2: the reconcile/prune deliverable ─────────────────────────────────────────

        test("share grants the member the book; revoke prunes it from the member's Room")
            .config(timeout = 2.minutes) {
                withCollectionSyncEngineAgainstServer {
                    serverBookRepository.upsert(bookPayload("book-private")).requireSuccess()

                    // A PRIVATE collection the admin owns and the member cannot see yet.
                    val collectionId = adminCollections.createPrivateCollection("Private", "book-private")

                    engine.start(currentUserId = COLLECTION_E2E_MEMBER_ID)

                    // Control: before sharing, the private book is not in the member's Room — the
                    // access gate is active (otherwise the appear/prune assertions are vacuous).
                    clientDatabase.bookDao().getById(BookId("book-private")).shouldBeNull()

                    // ── Grant direction ──
                    // Admin shares → server emits AccessChanged to the member → the engine
                    // re-derives the access-filtered set and upserts the now-accessible book
                    // as a LIVE (non-tombstoned) row.
                    adminCollections
                        .shareCollection(collectionId, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()

                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        // Poll until the row is present AND live (non-tombstoned).
                        while (clientDatabase.bookDao().getById(BookId("book-private")).let { it == null || it.deletedAt != null }) {
                            // poll until the AccessChanged reconcile upserts the book live
                        }
                    }
                    clientDatabase.bookDao().getById(BookId("book-private")).shouldNotBeNull()
                    clientDatabase.bookDao().getById(BookId("book-private"))!!.deletedAt shouldBe null

                    // ── Revoke direction (the load-bearing prune) ──
                    // Admin revokes → server emits AccessChanged to the member → the engine
                    // re-derives the (now-smaller) set and prunes the revoked book from Room.
                    // bookDao.getById does NOT filter tombstones (unlike collectionDao), so the
                    // prune surfaces as a non-null deletedAt on the still-present row.
                    adminCollections.revokeShare(collectionId, COLLECTION_E2E_MEMBER_ID).requireSuccess()

                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.bookDao().getById(BookId("book-private"))?.deletedAt == null) {
                            // poll until the prune tombstones the book
                        }
                    }
                    clientDatabase
                        .bookDao()
                        .getById(BookId("book-private"))!!
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
    })

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Polls the member's Room DB until the collection [id] is present (live), or fails after timeout. */
private suspend fun awaitClientCollection(
    database: ListenUpDatabase,
    id: String,
) = withTimeout(ROUND_TRIP_TIMEOUT) {
    var entity = database.collectionDao().getById(id)
    while (entity == null) {
        entity = database.collectionDao().getById(id)
    }
    entity
}

/** Creates a private collection owned by the acting (admin) caller and adds [bookId]; returns its id. */
private suspend fun com.calypsan.listenup.api.CollectionService.createPrivateCollection(
    name: String,
    bookId: String,
): CollectionId {
    val created = createCollection("test-library", name).requireSuccess()
    addBookToCollection(created.id, BookId(bookId)).requireSuccess()
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
