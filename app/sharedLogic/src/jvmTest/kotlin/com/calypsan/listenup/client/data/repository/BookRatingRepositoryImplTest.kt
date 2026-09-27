package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Offline-first contract for the listener-rating write surface. `rate` and `clear` must write Room
 * at once and enqueue a durable, coalesced outbox op with no server present — three taps offline
 * leave one queued op, and a rating followed by a clear leaves one Clear, not two ops.
 */
class BookRatingRepositoryImplTest :
    FunSpec({
        test("rating writes Room at once and queues one coalesced op; three taps queue one") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.rate("b1", 6, null).shouldBeInstanceOf<AppResult.Success<*>>()
                repo.rate("b1", 8, " Great. ").shouldBeInstanceOf<AppResult.Success<*>>()
                repo.rate("b1", 7, "Great.").shouldBeInstanceOf<AppResult.Success<*>>()

                val mine = repo.observeForBook("b1").first().single()
                mine.userId shouldBe "me"
                mine.halfStars shouldBe 7
                mine.note shouldBe "Great."
                val syncId =
                    db
                        .bookRatingDao()
                        .find("b1", "me")
                        .shouldNotBeNull()
                        .syncId
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                contractJson.decodeFromString<BookRatingMutation>(op.payload) shouldBe
                    BookRatingMutation.Set(bookId = "b1", candidateId = syncId, halfStars = 7, note = "Great.")
                db.close()
            }
        }

        test("clearing removes the local row and replaces the queued rate") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.rate("b1", 6, null).shouldBeInstanceOf<AppResult.Success<*>>()
                repo.clear("b1").shouldBeInstanceOf<AppResult.Success<*>>()

                repo.observeForBook("b1").first() shouldBe emptyList()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe OutboxChannels.BookRatings.name
                contractJson.decodeFromString<BookRatingMutation>(op.payload) shouldBe BookRatingMutation.Clear("b1")
                db.close()
            }
        }

        test("clear on a never-rated book queues one Clear op and leaves no local row") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.clear("b1").shouldBeInstanceOf<AppResult.Success<*>>()

                repo.observeForBook("b1").first() shouldBe emptyList()
                db.bookRatingDao().find("b1", "me").shouldBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe OutboxChannels.BookRatings.name
                contractJson.decodeFromString<BookRatingMutation>(op.payload) shouldBe BookRatingMutation.Clear("b1")
                db.close()
            }
        }

        test("re-rating after a clear reuses the same candidateId — the server refuses a colliding one") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.rate("b1", 6, null).shouldBeInstanceOf<AppResult.Success<*>>()
                val originalSyncId =
                    db
                        .bookRatingDao()
                        .find("b1", "me")
                        .shouldNotBeNull()
                        .syncId
                repo.clear("b1").shouldBeInstanceOf<AppResult.Success<*>>()
                repo.rate("b1", 9, null).shouldBeInstanceOf<AppResult.Success<*>>()

                val row = db.bookRatingDao().find("b1", "me").shouldNotBeNull()
                row.syncId shouldBe originalSyncId
                row.deletedAt.shouldBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                val decoded = contractJson.decodeFromString<BookRatingMutation>(op.payload)
                decoded.shouldBeInstanceOf<BookRatingMutation.Set>()
                (decoded as BookRatingMutation.Set).candidateId shouldBe originalSyncId
                db.close()
            }
        }

        test("rate, clear, then rate again leaves exactly one queued op — the final Set") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.rate("b1", 6, null).shouldBeInstanceOf<AppResult.Success<*>>()
                repo.clear("b1").shouldBeInstanceOf<AppResult.Success<*>>()
                repo.rate("b1", 9, "Loved it.").shouldBeInstanceOf<AppResult.Success<*>>()

                val syncId =
                    db
                        .bookRatingDao()
                        .find("b1", "me")
                        .shouldNotBeNull()
                        .syncId
                val ops = db.pendingOperationV2Dao().nextDispatchable()
                ops.size shouldBe 1
                contractJson.decodeFromString<BookRatingMutation>(ops.single().payload) shouldBe
                    BookRatingMutation.Set(bookId = "b1", candidateId = syncId, halfStars = 9, note = "Loved it.")
                db.close()
            }
        }

        test("averages cover live ratings only") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.bookRatingDao().upsert(entity(bookId = "b1", userId = "a", halfStars = 6))
                db.bookRatingDao().upsert(entity(bookId = "b1", userId = "b", halfStars = 9))
                db.bookRatingDao().upsert(entity(bookId = "b1", userId = "c", halfStars = 2, deletedAt = 1L))
                val repo = repo(db)

                repo.observeAverages().first()["b1"] shouldBe ListenerAverage(averageHalfStars = 7.5, count = 2)
                db.close()
            }
        }

        test("an out-of-range rating is refused before anything is written") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.rate("b1", 11, null).shouldBeInstanceOf<AppResult.Failure>()

                repo.observeForBook("b1").first() shouldBe emptyList()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("a note over the character limit is refused before anything is written") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                repo.rate("b1", 6, "x".repeat(281)).shouldBeInstanceOf<AppResult.Failure>()

                repo.observeForBook("b1").first() shouldBe emptyList()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("rate fails when no one is signed in") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db, userId = null)

                repo.rate("b1", 6, null).shouldBeInstanceOf<AppResult.Failure>()

                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }
    })

private fun entity(
    bookId: String,
    userId: String,
    halfStars: Int,
    note: String? = null,
    deletedAt: Long? = null,
) = BookRatingEntity(
    bookId = bookId,
    userId = userId,
    syncId = "$bookId:$userId",
    halfStars = halfStars,
    note = note,
    ratedAt = 100L,
    updatedAt = 100L,
    revision = 1,
    deletedAt = deletedAt,
)

private fun repo(
    db: ListenUpDatabase,
    userId: String? = "me",
): BookRatingRepositoryImpl {
    val authSession = FakeAuthSession(userId = userId)
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Success(Unit) },
        )
    val offlineEditor =
        OfflineEditor(
            pendingQueue = queue,
            transactionRunner =
                object : TransactionRunner {
                    override suspend fun <R> atomically(block: suspend () -> R): R = block()
                },
            authSession = authSession,
        )
    return BookRatingRepositoryImpl(
        dao = db.bookRatingDao(),
        offlineEditor = offlineEditor,
        authSession = authSession,
    )
}
