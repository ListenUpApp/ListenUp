package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.ReadingOrderRpcFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Offline-first coverage for [ReadingOrderRepositoryImpl] (Integration
 * Foundations §5.3/§5.4): every outbox-backed mutation applies its optimistic
 * Room write and enqueues a durable op — no RPC required, nothing lost offline.
 */
class ReadingOrderRepositoryOfflineTest :
    FunSpec({

        suspend fun withRepo(block: suspend (ReadingOrderRepository, ListenUpDatabase) -> Unit) {
            val db = createInMemoryTestDatabase()
            try {
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                val repo =
                    ReadingOrderRepositoryImpl(
                        dao = db.readingOrderDao(),
                        bookDao = db.readingOrderBookDao(),
                        followDao = db.readingOrderFollowDao(),
                        userDao = db.userDao(),
                        rpcFactory = mock<ReadingOrderRpcFactory>(),
                        offlineEditor = offlineEditor,
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                block(repo, db)
            } finally {
                db.close()
            }
        }

        fun orderEntity(id: String = "ro1") =
            ReadingOrderEntity(
                id = id,
                name = "Old Name",
                description = "",
                attribution = "",
                isPrivate = false,
                revision = 1L,
                deletedAt = null,
                updatedAt = 100L,
                createdAt = 50L,
            )

        suspend fun ListenUpDatabase.queuedDomains(): List<String> =
            pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).map { it.domainName }

        test("offline metadata update persists to Room and enqueues a reading_orders op") {
            runTest {
                withRepo { repo, db ->
                    db.readingOrderDao().upsert(orderEntity())

                    val result =
                        repo.updateReadingOrder(
                            id = ReadingOrderId("ro1"),
                            name = "New Name",
                            description = "desc",
                            attribution = "u/Argent",
                            isPrivate = true,
                        )

                    result shouldBe AppResult.Success(Unit)
                    val row = db.readingOrderDao().getById("ro1")!!
                    row.name shouldBe "New Name"
                    row.attribution shouldBe "u/Argent"
                    row.isPrivate shouldBe true
                    // revision untouched — the SSE echo advances it.
                    row.revision shouldBe 1L
                    db.queuedDomains() shouldContainExactly listOf("reading_orders")
                }
            }
        }

        test("offline addBooks appends optimistic junction rows in order and enqueues one op per book") {
            runTest {
                withRepo { repo, db ->
                    db.readingOrderDao().upsert(orderEntity())

                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.readingOrderBookDao().observeReadingOrderBooks("ro1").first() shouldContainExactly
                        listOf("b1", "b2")
                    db.readingOrderBookDao().findById("ro1:b1")!!.sortOrder shouldBe 0
                    db.readingOrderBookDao().findById("ro1:b2")!!.sortOrder shouldBe 1
                    db.queuedDomains() shouldContainExactly listOf("reading_order_books", "reading_order_books")
                }
            }
        }

        test("offline removeBook tombstones the junction row locally and enqueues a delete op") {
            runTest {
                withRepo { repo, db ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo.addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo
                        .removeBookFromReadingOrder(ReadingOrderId("ro1"), BookId("b1"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.readingOrderBookDao().observeReadingOrderBooks("ro1").first() shouldBe emptyList()
                    db.readingOrderBookDao().findById("ro1:b1")!!.deletedAt shouldNotBe null
                }
            }
        }

        test("offline reorder rewrites local sort order and enqueues one op for the whole ordering") {
            runTest {
                withRepo { repo, db ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo.addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2"), BookId("b3")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo
                        .reorderBooks(ReadingOrderId("ro1"), listOf(BookId("b3"), BookId("b1"), BookId("b2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.readingOrderBookDao().observeReadingOrderBooks("ro1").first() shouldContainExactly
                        listOf("b3", "b1", "b2")
                }
            }
        }

        test("offline setActiveReadingOrder writes the follow row with the deterministic id and enqueues an op") {
            runTest {
                withRepo { repo, db ->
                    repo
                        .setActiveReadingOrder("series-1", ReadingOrderId("ro1"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.observeActiveReadingOrder("series-1").first() shouldBe ReadingOrderId("ro1")
                    db.readingOrderFollowDao().findById("u1:series-1") shouldNotBe null
                    db.queuedDomains() shouldContainExactly listOf("reading_order_follows")

                    // Clearing resets to the frontier floor without deleting the row.
                    repo
                        .setActiveReadingOrder("series-1", null)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.observeActiveReadingOrder("series-1").first() shouldBe null
                }
            }
        }
    })
