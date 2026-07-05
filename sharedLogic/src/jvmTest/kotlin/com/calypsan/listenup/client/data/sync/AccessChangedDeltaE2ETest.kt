package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.BookReadershipEntity
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.sync.testing.COLLECTION_E2E_MEMBER_ID
import com.calypsan.listenup.client.data.sync.testing.CollectionSyncEngineScope
import com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP_TIMEOUT = 30.seconds

/**
 * Tier-3 E2E for the scoped `AccessChanged` DELTA against the real `:server` routes + SSE firehose +
 * the member's client [SyncEngine] (see
 * [withCollectionSyncEngineAgainstServer][com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer]).
 *
 * The oracle is **access-filtered digest parity**: after the reconcile settles, the member's local
 * (tombstone-exclusive) `books` digest must EQUAL the server's `accessibleBookIdsSql`-filtered member
 * digest at the same cursor — the exact comparison `SyncReconciler.reconcileOne` performs, so parity
 * means a follow-up reconcile is a stable no-op. Complements the unit-level coalesce/prune coverage in
 * [AccessChangedReconcileTest] by proving the whole stack — the server emits a correctly-scoped frame
 * and the member converges through the real transports.
 *
 * Two proofs:
 *  1. **Grant** — the member gains the book, the digests converge, and the member's persisted `books`
 *     cursor is UNCHANGED (share touches no book revision, so the book arrives purely via the cursor-
 *     untouched delta fetch — never a from-0 re-pull).
 *  2. **Revoke** — the scoped book is tombstoned and its readership + Continue-Listening position
 *     cascade away, WHILE a book the member holds through a different collection (outside the revoked
 *     scope) SURVIVES — the substrate-protection invariant end-to-end — and the digests converge.
 */
class AccessChangedDeltaE2ETest :
    FunSpec({

        test("grant: member gains the book, digests converge, and the books cursor is unchanged")
            .config(timeout = 2.minutes) {
                withCollectionSyncEngineAgainstServer {
                    serverBookRepository.upsert(bookPayload("book-private")).requireSuccess()
                    val collectionId = adminCollections.createPrivateCollection("Private", "book-private")

                    engine.start(currentUserId = COLLECTION_E2E_MEMBER_ID)

                    // Pre-grant: the private book is invisible, and the books cursor is at its start value.
                    clientDatabase.bookDao().getById(BookId("book-private")).shouldBeNull()
                    val cursorBeforeGrant = clientDatabase.syncCursorDao().getCursor("books")

                    adminCollections
                        .shareCollection(collectionId, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()

                    awaitLiveBook(clientDatabase, "book-private")

                    // Convergence oracle: the member's local books digest EQUALS the server's
                    // access-filtered member digest — the reconcile landed exactly the accessible set.
                    assertBooksDigestsConverge(this)

                    // The delta fetch never advances the persisted cursor: the book arrived scoped, not
                    // via a cursor-moving full re-pull.
                    clientDatabase.syncCursorDao().getCursor("books") shouldBe cursorBeforeGrant
                }
            }

        test("revoke: scoped book is tombstoned + cascaded, a book outside the scope survives, digests converge")
            .config(timeout = 2.minutes) {
                withCollectionSyncEngineAgainstServer {
                    serverBookRepository.upsert(bookPayload("book-scoped")).requireSuccess()
                    serverBookRepository.upsert(bookPayload("book-substrate")).requireSuccess()

                    // Two independent private collections shared with the member: revoking one must not
                    // touch the book held through the other (the substrate the naive design would nuke).
                    val scopedCollection = adminCollections.createPrivateCollection("Scoped", "book-scoped")
                    val substrateCollection = adminCollections.createPrivateCollection("Substrate", "book-substrate")

                    engine.start(currentUserId = COLLECTION_E2E_MEMBER_ID)

                    adminCollections
                        .shareCollection(scopedCollection, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()
                    adminCollections
                        .shareCollection(substrateCollection, COLLECTION_E2E_MEMBER_ID, SharePermission.Read)
                        .requireSuccess()

                    awaitLiveBook(clientDatabase, "book-scoped")
                    awaitLiveBook(clientDatabase, "book-substrate")

                    // Seed readership + a Continue-Listening position for the scoped book, so the revoke's
                    // afterPrune cascade is observable.
                    clientDatabase.bookReadershipDao().upsertAll(listOf(readershipRow("book-scoped", "reader")))
                    clientDatabase.playbackPositionDao().save(positionRow("book-scoped"))

                    // Revoke only the scoped collection → the server emits an AccessChanged delta scoped
                    // to book-scoped alone.
                    adminCollections.revokeShare(scopedCollection, COLLECTION_E2E_MEMBER_ID).requireSuccess()

                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.bookDao().getById(BookId("book-scoped"))?.deletedAt == null) {
                            // poll until the scoped prune tombstones the book
                        }
                    }

                    // The scoped book is pruned, and its dependents cascade away.
                    clientDatabase
                        .bookDao()
                        .getById(BookId("book-scoped"))!!
                        .deletedAt
                        .shouldNotBeNull()
                    clientDatabase
                        .bookReadershipDao()
                        .observeForBook("book-scoped")
                        .first()
                        .shouldBeEmpty()
                    clientDatabase.playbackPositionDao().get(BookId("book-scoped")).shouldBeNull()

                    // The substrate-protection invariant, end-to-end: the book held through the OTHER
                    // collection is outside the revoked scope, so it is never a prune candidate.
                    clientDatabase.bookDao().getById(BookId("book-substrate")).shouldNotBeNull()
                    clientDatabase.bookDao().getById(BookId("book-substrate"))!!.deletedAt shouldBe null

                    assertBooksDigestsConverge(this)
                }
            }
    })

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Polls the member's Room until [bookId] is present AND live (non-tombstoned), or fails after timeout. */
private suspend fun awaitLiveBook(
    database: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    bookId: String,
) = withTimeout(ROUND_TRIP_TIMEOUT) {
    while (database.bookDao().getById(BookId(bookId)).let { it == null || it.deletedAt != null }) {
        // poll until the AccessChanged delta upserts the book live
    }
}

/**
 * Asserts the member's local `books` digest equals the server's access-filtered member digest — the
 * convergence oracle. The client digest is tombstone-exclusive (`bookDao.digestRows`); the server's
 * is filtered through `accessibleBookIdsSql`, which itself excludes tombstones.
 */
private suspend fun assertBooksDigestsConverge(scope: CollectionSyncEngineScope) {
    val filter =
        scope.serverBookAccessPolicy
            .accessibleBookIdsSql(COLLECTION_E2E_MEMBER_ID, UserRole.MEMBER)
            .shouldNotBeNull()
    val serverDigest = scope.serverBookRepository.digest(COLLECTION_E2E_MEMBER_ID, Long.MAX_VALUE, filter)
    val clientRows =
        scope.clientDatabase
            .bookDao()
            .digestRows(Long.MAX_VALUE)
            .map { it.id to it.revision }
    val clientDigest = DigestComputer.compute(Long.MAX_VALUE, clientRows)

    clientDigest.count shouldBe serverDigest.count
    clientDigest.hash shouldBe serverDigest.hash
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

private fun readershipRow(
    bookId: String,
    userId: String,
): BookReadershipEntity =
    BookReadershipEntity(
        bookId = bookId,
        userId = userId,
        displayName = "Reader $userId",
        avatarType = "auto",
        currentProgressPct = null,
        finishesJson = "",
        observedAt = 1L,
    )

private fun positionRow(bookId: String): PlaybackPositionEntity =
    PlaybackPositionEntity(
        bookId = BookId(bookId),
        positionMs = 1_000L,
        playbackSpeed = 1.0f,
        updatedAt = 1L,
    )

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
