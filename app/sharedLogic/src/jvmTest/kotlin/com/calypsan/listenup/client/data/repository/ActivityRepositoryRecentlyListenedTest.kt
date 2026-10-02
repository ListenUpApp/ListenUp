package com.calypsan.listenup.client.data.repository

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.data.sync.domains.AccessDeltaPolicy
import com.calypsan.listenup.client.data.sync.domains.activitiesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * A profile's "Recently listened" strip, read from the synced `activities` mirror through real Room.
 *
 * What these pin: the strip is one person's books, newest first, one entry per book, drawn only from
 * the three activities that mean "listened" (started, finished, a session). It never shows a book the
 * viewer cannot open — a tombstoned activity, a deleted book, or a book whose share was revoked (the
 * access prune tombstones its activities) all drop out — and it follows the mirror live, so a session
 * that syncs in while the profile is open appears without a refresh.
 */
class ActivityRepositoryRecentlyListenedTest :
    FunSpec({

        test("one person's books, newest first, deduplicated by book, with title and cover hash") {
            withRepository { db, repository ->
                seedBook(db, "b1", coverHash = "h1")
                seedBook(db, "b2")
                seedBook(db, "b3")
                seedActivity(db, "a1", "u1", "b1", ActivityType.STARTED_BOOK, at = 1_000)
                seedActivity(db, "a2", "u1", "b2", ActivityType.LISTENING_SESSION, at = 2_000)
                seedActivity(db, "a3", "u1", "b1", ActivityType.LISTENING_SESSION, at = 3_000)
                seedActivity(db, "a4", "u1", "b3", ActivityType.FINISHED_BOOK, at = 2_500)
                // Someone else's listening is not on u1's profile.
                seedActivity(db, "a5", "u2", "b2", ActivityType.LISTENING_SESSION, at = 9_000)

                repository.observeRecentlyListened("u1", limit = 10).first() shouldBe
                    listOf(
                        ProfileRecentBook(bookId = "b1", title = "Book b1", coverHash = "h1"),
                        ProfileRecentBook(bookId = "b3", title = "Book b3", coverHash = null),
                        ProfileRecentBook(bookId = "b2", title = "Book b2", coverHash = null),
                    )
            }
        }

        test("only started, finished and listening-session activities count") {
            withRepository { db, repository ->
                seedBook(db, "b1")
                seedBook(db, "b2")
                seedBook(db, "b3")
                seedActivity(db, "a1", "u1", "b1", ActivityType.STARTED_BOOK, at = 1_000)
                seedActivity(db, "a2", "u1", "b2", ActivityType.LISTENING_MILESTONE, at = 2_000)
                seedActivity(db, "a3", "u1", "b3", ActivityType.STREAK_MILESTONE, at = 3_000)
                seedActivity(db, "a4", "u1", "b2", ActivityType.SHELF_CREATED, at = 4_000)

                repository.observeRecentlyListened("u1", limit = 10).first().map { it.bookId } shouldBe listOf("b1")
            }
        }

        test("a tombstoned activity and a deleted book are both left out") {
            withRepository { db, repository ->
                seedBook(db, "b1")
                seedBook(db, "b2")
                seedBook(db, "gone", deletedAt = 5L)
                seedActivity(db, "a1", "u1", "b1", ActivityType.LISTENING_SESSION, at = 1_000)
                seedActivity(db, "a2", "u1", "b2", ActivityType.LISTENING_SESSION, at = 2_000, deletedAt = 7L)
                seedActivity(db, "a3", "u1", "gone", ActivityType.LISTENING_SESSION, at = 3_000)
                // A book the viewer never had: no local `books` row at all.
                seedActivity(db, "a4", "u1", "absent", ActivityType.LISTENING_SESSION, at = 4_000)

                repository.observeRecentlyListened("u1", limit = 10).first().map { it.bookId } shouldBe listOf("b1")
            }
        }

        test("capped at the limit, keeping the newest") {
            withRepository { db, repository ->
                (1..5).forEach { n ->
                    seedBook(db, "b$n")
                    seedActivity(db, "a$n", "u1", "b$n", ActivityType.LISTENING_SESSION, at = n * 1_000L)
                }

                repository.observeRecentlyListened("u1", limit = 3).first().map { it.bookId } shouldBe
                    listOf("b5", "b4", "b3")
            }
        }

        test("a session that syncs in while the profile is open moves its book to the front") {
            withRepository { db, repository ->
                seedBook(db, "b1")
                seedBook(db, "b2")
                seedActivity(db, "a1", "u1", "b1", ActivityType.LISTENING_SESSION, at = 2_000)
                seedActivity(db, "a2", "u1", "b2", ActivityType.LISTENING_SESSION, at = 1_000)
                val handler = activitiesHandler(db)

                repository.observeRecentlyListened("u1", limit = 10).test {
                    awaitItem().map { it.bookId } shouldBe listOf("b1", "b2")

                    // The firehose delivers a fresh session on b2 — through the real sync handler.
                    handler.onEvent(
                        SyncEvent.Created(
                            id = "a3",
                            revision = 3L,
                            occurredAt = 3_000L,
                            payload = payload("a3", "u1", "b2", ActivityType.LISTENING_SESSION, at = 3_000),
                        ),
                    )

                    awaitBookIds(listOf("b2", "b1"))
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a book whose share was revoked disappears once the access prune tombstones its activity") {
            withRepository { db, repository ->
                seedBook(db, "b1")
                seedBook(db, "shared")
                val handler = activitiesHandler(db)
                handler.onCatchUpItem(payload("a1", "u1", "b1", ActivityType.LISTENING_SESSION, at = 1_000), false)
                handler.onCatchUpItem(payload("a2", "u1", "shared", ActivityType.FINISHED_BOOK, at = 2_000), false)

                repository.observeRecentlyListened("u1", limit = 10).test {
                    awaitItem().map { it.bookId } shouldBe listOf("shared", "b1")

                    // A scoped AccessChanged revoke on "shared": the domain's own candidate query names
                    // its activities, the targeted fetch returns none of them, so pruneWithin tombstones
                    // them. The book row itself is still live here — the activity prune alone must hide it.
                    val gated = handler as AccessFilteredSyncHandler
                    val policy = gated.deltaPolicy as AccessDeltaPolicy.Targeted
                    val candidates = policy.candidatesFor(listOf("shared"))
                    gated.pruneWithin(candidateIds = candidates, accessibleIds = emptySet(), now = 10L)

                    awaitBookIds(listOf("b1"))
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

private fun withRepository(block: suspend (ListenUpDatabase, ActivityRepositoryImpl) -> Unit) {
    val db = createInMemoryTestDatabase()
    try {
        runTest { block(db, ActivityRepositoryImpl(db.activityDao())) }
    } finally {
        db.close()
    }
}

private fun activitiesHandler(db: ListenUpDatabase): SyncDomainHandler<ActivitySyncPayload> =
    activitiesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())

/** Room can re-emit an unchanged list on an unrelated invalidation; wait for the shape that matters. */
private suspend fun ReceiveTurbine<List<ProfileRecentBook>>.awaitBookIds(expected: List<String>) {
    var latest = awaitItem().map { it.bookId }
    while (latest != expected) latest = awaitItem().map { it.bookId }
}

private suspend fun seedBook(
    db: ListenUpDatabase,
    id: String,
    coverHash: String? = null,
    deletedAt: Long? = null,
) {
    db.bookDao().upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "Book $id",
            sortTitle = "Book $id",
            subtitle = null,
            coverHash = coverHash,
            totalDuration = 0L,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
            deletedAt = deletedAt,
        ),
    )
}

private suspend fun seedActivity(
    db: ListenUpDatabase,
    id: String,
    userId: String,
    bookId: String,
    type: String,
    at: Long,
    deletedAt: Long? = null,
) {
    db.activityDao().upsert(
        ActivityEntity(
            id = id,
            userId = userId,
            type = type,
            occurredAt = at,
            bookId = bookId,
            isReread = false,
            durationMs = 0L,
            milestoneValue = 0,
            milestoneUnit = null,
            shelfId = null,
            shelfName = null,
            revision = 1L,
            deletedAt = deletedAt,
        ),
    )
}

private fun payload(
    id: String,
    userId: String,
    bookId: String,
    type: String,
    at: Long,
): ActivitySyncPayload =
    ActivitySyncPayload(
        id = id,
        userId = userId,
        type = type,
        bookId = bookId,
        isReread = false,
        durationMs = 0L,
        milestoneValue = 0,
        milestoneUnit = null,
        shelfId = null,
        shelfName = null,
        occurredAt = at,
        revision = 1L,
        createdAt = at,
        updatedAt = at,
        deletedAt = null,
    )
