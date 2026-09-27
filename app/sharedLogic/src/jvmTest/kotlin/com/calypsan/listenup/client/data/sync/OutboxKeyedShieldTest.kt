package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncPayload
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private const val ME = "u1"

/**
 * The anti-flicker shield for the outbox domains whose op key is NOT the row's wire id.
 *
 * Since SERVER-SYNC-04 an inbound frame names a row by its opaque wire id, while these repositories
 * key their queued ops by the natural identity they write under — a rating by its book, a junction
 * by its `"parent:child"` pair. Looking the op up by the wire id never
 * found it, so a stale echo overwrote the optimistic edit. Proven here against the REAL queue and
 * the REAL production catalog's handlers, exactly as production wires them.
 */
class OutboxKeyedShieldTest :
    FunSpec({

        test("book_ratings: a stale echo does not overwrite a queued Set, and the drain's own echo lands") {
            withShield { db, queue, registry ->
                val ratings = registry.handler<BookRatingSyncPayload>("book_ratings")
                ratings.onEvent(created(rating(id = "r1", halfStars = 6, revision = 19)))

                // The listener taps 8: Room holds it optimistically and the op is queued under bookId.
                db.bookRatingDao().upsert(localRating(syncId = "r1", halfStars = 8, revision = 19))
                val opId = queue.enqueue(OutboxChannels.BookRatings, "b1", OpKind.Upsert, "{}", ME, signal = false)

                // An older write's echo (Set 6) lands at a revision above the local row.
                ratings.onEvent(updated(rating(id = "r1", halfStars = 6, revision = 20)))
                db.bookRatingDao().find("b1", ME)!!.halfStars shouldBe 8

                // The op drains; its own echo carries 8 at rev 21 — the revision proves it applied.
                db.pendingOperationV2Dao().delete(opId)
                ratings.onEvent(updated(rating(id = "r1", halfStars = 8, revision = 21)))
                val converged = db.bookRatingDao().find("b1", ME)!!
                converged.halfStars shouldBe 8
                converged.revision shouldBe 21
            }
        }

        test("book_ratings: a server re-id echo is shielded by the payload's book, not its unseen wire id") {
            withShield { db, queue, registry ->
                val ratings = registry.handler<BookRatingSyncPayload>("book_ratings")
                db.bookRatingDao().upsert(localRating(syncId = "client-candidate", halfStars = 8, revision = 0))
                queue.enqueue(OutboxChannels.BookRatings, "b1", OpKind.Upsert, "{}", ME, signal = false)

                ratings.onEvent(created(rating(id = "server-id", halfStars = 6, revision = 20)))

                db.bookRatingDao().find("b1", ME)!!.halfStars shouldBe 8
            }
        }

        test("book_ratings: a queued Clear is not undone by a catch-up that re-delivers the live row") {
            withShield { db, queue, registry ->
                val ratings = registry.handler<BookRatingSyncPayload>("book_ratings")
                ratings.onEvent(created(rating(id = "r1", halfStars = 6, revision = 19)))
                db.bookRatingDao().tombstone(bookId = "b1", userId = ME, deletedAt = 50L)
                queue.enqueue(OutboxChannels.BookRatings, "b1", OpKind.Upsert, "{}", ME, signal = false)

                ratings.onCatchUpItem(rating(id = "r1", halfStars = 6, revision = 21), isTombstone = false)

                db
                    .bookRatingDao()
                    .find("b1", ME)!!
                    .deletedAt
                    .shouldNotBeNull()
            }
        }

        test("book_ratings: another listener's rating of the same book still applies while mine is queued") {
            withShield { db, queue, registry ->
                val ratings = registry.handler<BookRatingSyncPayload>("book_ratings")
                queue.enqueue(OutboxChannels.BookRatings, "b1", OpKind.Upsert, "{}", ME, signal = false)

                ratings.onEvent(created(rating(id = "r2", userId = "u2", halfStars = 4, revision = 5)))

                db.bookRatingDao().find("b1", "u2")!!.halfStars shouldBe 4
            }
        }

        test("book_moods: a queued remove is not undone by a live echo of that junction") {
            withShield { db, queue, registry ->
                val moods = registry.handler<BookMoodSyncPayload>("book_moods")
                moods.onEvent(created(BookMoodSyncPayload(id = "w1", bookId = "b1", moodId = "m1", createdAt = 1L, revision = 5)))
                db.bookMoodDao().tombstone(bookId = "b1", moodId = "m1", deletedAt = 50L)
                queue.enqueue(OutboxChannels.BookMoods, "b1:m1", OpKind.Delete, "{}", ME, signal = false)

                moods.onEvent(updated(BookMoodSyncPayload(id = "w1", bookId = "b1", moodId = "m1", createdAt = 1L, revision = 7)))

                db
                    .bookMoodDao()
                    .findByKey("b1", "m1")!!
                    .deletedAt
                    .shouldNotBeNull()
            }
        }

        test("book_tags: a queued remove is not undone by a live echo of that junction") {
            withShield { db, queue, registry ->
                val tags = registry.handler<BookTagSyncPayload>("book_tags")
                tags.onEvent(created(BookTagSyncPayload(id = "w1", bookId = "b1", tagId = "t1", createdAt = 1L, revision = 5)))
                db.bookTagDao().tombstone(bookId = "b1", tagId = "t1", deletedAt = 50L, revision = 0)
                queue.enqueue(OutboxChannels.BookTags, "b1:t1", OpKind.Delete, "{}", ME, signal = false)

                tags.onEvent(updated(BookTagSyncPayload(id = "w1", bookId = "b1", tagId = "t1", createdAt = 1L, revision = 7)))

                db
                    .bookTagDao()
                    .findByKey("b1", "t1")!!
                    .deletedAt
                    .shouldNotBeNull()
            }
        }

        test("collection_books: a queued remove is not undone by a live echo of that junction") {
            withShield { db, queue, registry ->
                val members = registry.handler<CollectionBookSyncPayload>("collection_books")
                members.onEvent(
                    created(CollectionBookSyncPayload(id = "w1", collectionId = "c1", bookId = "b1", createdAt = 1L, revision = 5)),
                )
                db.collectionBookDao().tombstone(collectionId = "c1", bookId = "b1", deletedAt = 50L, revision = 0)
                queue.enqueue(OutboxChannels.CollectionBooks, "c1:b1", OpKind.Delete, "{}", ME, signal = false)

                members.onEvent(
                    updated(CollectionBookSyncPayload(id = "w1", collectionId = "c1", bookId = "b1", createdAt = 1L, revision = 7)),
                )

                db
                    .collectionBookDao()
                    .findByKey("c1", "b1")!!
                    .deletedAt
                    .shouldNotBeNull()
            }
        }

        test("shelf_books: a queued remove is not undone by a live echo of that junction") {
            withShield { db, queue, registry ->
                val shelved = registry.handler<ShelfBookSyncPayload>("shelf_books")
                shelved.onEvent(created(shelfBook(sortOrder = 0, revision = 5)))
                db.shelfBookDao().softDelete(id = "w1", deletedAt = 50L, revision = 5)
                queue.enqueue(OutboxChannels.ShelfBooks, "s1:b1", OpKind.Delete, "{}", ME, signal = false)

                shelved.onEvent(updated(shelfBook(sortOrder = 0, revision = 7)))

                db
                    .shelfBookDao()
                    .findByShelfAndBook("s1", "b1")!!
                    .deletedAt
                    .shouldNotBeNull()
            }
        }

        test("shelf_books: while a reorder is queued, another book's Created for that shelf applies") {
            withShield { db, queue, registry ->
                val shelved = registry.handler<ShelfBookSyncPayload>("shelf_books")
                shelved.onEvent(created(shelfBook(sortOrder = 0, revision = 5)))
                queue.enqueue(OutboxChannels.ShelfBooks, "s1", OpKind.Update, "{}", ME, coalesce = true, signal = false)

                // Another device adds a different book: a reorder must not hold back the shelf's content.
                shelved.onEvent(created(shelfBook(sortOrder = 1, revision = 6, id = "w2", bookId = "b2")))

                db.shelfBookDao().findByShelfAndBook("s1", "b2").shouldNotBeNull()
            }
        }

        test("book_ratings: a dead-lettered op lifts the shield, so the echo applies") {
            withShield { db, queue, registry ->
                val ratings = registry.handler<BookRatingSyncPayload>("book_ratings")
                ratings.onEvent(created(rating(id = "r1", halfStars = 6, revision = 19)))
                db.bookRatingDao().upsert(localRating(syncId = "r1", halfStars = 8, revision = 19))
                val opId = queue.enqueue(OutboxChannels.BookRatings, "b1", OpKind.Upsert, "{}", ME, signal = false)
                val op = db.pendingOperationV2Dao().get(opId)!!
                db.pendingOperationV2Dao().update(op.copy(failureCount = MAX_RETRYABLE_ATTEMPTS + 1))

                ratings.onEvent(updated(rating(id = "r1", halfStars = 6, revision = 20)))

                db.bookRatingDao().find("b1", ME)!!.halfStars shouldBe 6
            }
        }

        test("book_ratings: with no signed-in user nothing is shielded") {
            withShield(FakeAuthSession(userId = null, authState = AuthState.Initializing)) { db, queue, registry ->
                val ratings = registry.handler<BookRatingSyncPayload>("book_ratings")
                ratings.onEvent(created(rating(id = "r1", halfStars = 6, revision = 19)))
                db.bookRatingDao().upsert(localRating(syncId = "r1", halfStars = 8, revision = 19))
                queue.enqueue(OutboxChannels.BookRatings, "b1", OpKind.Upsert, "{}", ME, signal = false)

                ratings.onEvent(updated(rating(id = "r1", halfStars = 6, revision = 20)))

                db.bookRatingDao().find("b1", ME)!!.halfStars shouldBe 6
            }
        }
    })

private fun withShield(
    authSession: AuthSession = FakeAuthSession(userId = ME),
    block: suspend (ListenUpDatabase, PendingOperationQueue, ClientSyncDomainRegistry) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val queue =
            PendingOperationQueue(
                dao = db.pendingOperationV2Dao(),
                sender = PendingOperationSender { AppResult.Success(Unit) },
            )
        val registry = ClientSyncDomainRegistry()
        registerTestSyncDomains(
            db = db,
            registry = registry,
            authSession = authSession,
            inFlightOutbox = OutboxInFlightQuery(queue::hasQueuedOpFor),
        )
        block(db, queue, registry)
    } finally {
        db.close()
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> ClientSyncDomainRegistry.handler(name: String): SyncDomainHandler<T> = lookup(name) as SyncDomainHandler<T>

private fun <T : SyncPayload> created(payload: T) =
    SyncEvent.Created(id = payload.id, revision = payload.revision, occurredAt = 1L, payload = payload)

private fun <T : SyncPayload> updated(payload: T) =
    SyncEvent.Updated(id = payload.id, revision = payload.revision, occurredAt = 1L, payload = payload)

private fun rating(
    id: String,
    halfStars: Int,
    revision: Long,
    userId: String = ME,
) = BookRatingSyncPayload(
    id = id,
    bookId = "b1",
    userId = userId,
    halfStars = halfStars,
    note = null,
    ratedAt = 1L,
    updatedAt = revision,
    revision = revision,
)

private fun localRating(
    syncId: String,
    halfStars: Int,
    revision: Long,
) = BookRatingEntity(
    bookId = "b1",
    userId = ME,
    syncId = syncId,
    halfStars = halfStars,
    note = null,
    ratedAt = 1L,
    updatedAt = 100L,
    revision = revision,
    deletedAt = null,
)

private fun shelfBook(
    sortOrder: Int,
    revision: Long,
    id: String = "w1",
    bookId: String = "b1",
) = ShelfBookSyncPayload(
    id = id,
    shelfId = "s1",
    bookId = bookId,
    sortOrder = sortOrder,
    revision = revision,
    updatedAt = revision,
    createdAt = 1L,
)
