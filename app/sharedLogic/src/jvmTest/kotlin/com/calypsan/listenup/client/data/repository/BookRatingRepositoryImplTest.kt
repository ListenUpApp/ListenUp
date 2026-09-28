package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookExternalRatingEntity
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
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

        test("re-rating after a clear reuses the same candidateId — the row keeps one stable wire id") {
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

        // ========== Outside (external) ratings ==========

        test("observeExternalForBook excludes disabled, UNKNOWN-source, and deleted rows") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.bookExternalRatingDao().upsert(externalEntity("b1", "AUDIBLE", average = 4.5, count = 100))
                db.bookExternalRatingDao().upsert(externalEntity("b1", "HARDCOVER", average = 3.0, count = 10, enabled = false))
                db.bookExternalRatingDao().upsert(externalEntity("b1", "UNKNOWN", average = 5.0, count = 999))
                db.bookExternalRatingDao().upsert(
                    externalEntity("b1", "GOODREADS", average = 4.0, count = 50, deletedAt = 1L),
                )
                val repo = repo(db)

                val external = repo.observeExternalForBook("b1").first()

                external shouldBe
                    listOf(ExternalRating(source = com.calypsan.listenup.api.sync.ExternalRatingSource.AUDIBLE, average = 4.5, count = 100))
                db.close()
            }
        }

        test("observeExternalForBook orders enabled rows by count descending") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.bookExternalRatingDao().upsert(externalEntity("b1", "AUDIBLE", average = 4.5, count = 10))
                db.bookExternalRatingDao().upsert(externalEntity("b1", "HARDCOVER", average = 4.0, count = 200))
                val repo = repo(db)

                val external = repo.observeExternalForBook("b1").first()

                external.map { it.count } shouldBe listOf(200, 10)
                db.close()
            }
        }

        test("observeCombinedScores groups enabled, known-source rows by book and excludes the rest") {
            runTest {
                val db = createInMemoryTestDatabase()
                // b1: two enabled known sources -> combined.
                db.bookExternalRatingDao().upsert(externalEntity("b1", "AUDIBLE", average = 4.0, count = 100))
                db.bookExternalRatingDao().upsert(externalEntity("b1", "HARDCOVER", average = 5.0, count = 300))
                // b2: only a disabled row -> no combined score at all.
                db.bookExternalRatingDao().upsert(externalEntity("b2", "AUDIBLE", average = 2.0, count = 5, enabled = false))
                // b3: only an UNKNOWN-source row -> no combined score at all.
                db.bookExternalRatingDao().upsert(externalEntity("b3", "UNKNOWN", average = 1.0, count = 1))
                val repo = repo(db)

                val scores = repo.observeCombinedScores().first()

                scores shouldContainExactly mapOf("b1" to CombinedScore(average = 4.75, count = 400))
                db.close()
            }
        }

        test("refreshExternal calls the rating service") {
            runTest {
                val db = createInMemoryTestDatabase()
                var calledWith: String? = null
                val service =
                    object : BookRatingService {
                        override suspend fun rate(
                            bookId: com.calypsan.listenup.core.BookId,
                            request: com.calypsan.listenup.api.dto.RateBookRequest,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun clearRating(bookId: com.calypsan.listenup.core.BookId): AppResult<Unit> =
                            AppResult.Success(Unit)

                        override suspend fun refreshExternalRatings(bookId: com.calypsan.listenup.core.BookId): AppResult<Unit> {
                            calledWith = bookId.value
                            return AppResult.Success(Unit)
                        }
                    }
                val repo = repo(db, ratingChannel = RpcChannel.forTest(service))

                repo.refreshExternal("b1").shouldBeInstanceOf<AppResult.Success<*>>()

                calledWith shouldBe "b1"
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

private fun externalEntity(
    bookId: String,
    source: String,
    average: Double,
    count: Int,
    enabled: Boolean = true,
    deletedAt: Long? = null,
) = BookExternalRatingEntity(
    bookId = bookId,
    source = source,
    syncId = "$bookId:$source",
    average = average,
    count = count,
    enabled = enabled,
    revision = 1,
    deletedAt = deletedAt,
)

private fun repo(
    db: ListenUpDatabase,
    userId: String? = "me",
    ratingChannel: RpcChannel<BookRatingService> = RpcChannel.forTest(mock()),
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
        externalRatingDao = db.bookExternalRatingDao(),
        offlineEditor = offlineEditor,
        authSession = authSession,
        ratingChannel = ratingChannel,
    )
}
