package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import dev.mokkery.mock
import dev.mokkery.verifyNoMoreCalls
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

        suspend fun withRepo(block: suspend (ReadingOrderRepository, ListenUpDatabase, ReadingOrderService) -> Unit) {
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
                val service = mock<ReadingOrderService>()
                val repo =
                    ReadingOrderRepositoryImpl(
                        dao = db.readingOrderDao(),
                        bookDao = db.readingOrderBookDao(),
                        followDao = db.readingOrderFollowDao(),
                        userDao = db.userDao(),
                        channel = RpcChannel.forTest(service),
                        offlineEditor = offlineEditor,
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                block(repo, db, service)
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

        suspend fun ListenUpDatabase.queuedOps() = pendingOperationV2Dao().nextDispatchable(maxAttempts = 5)

        test("offline metadata update persists to Room and enqueues a reading_orders op") {
            runTest {
                withRepo { repo, db, service ->
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
                    db.queuedOps().map { it.domainName } shouldContainExactly listOf("reading_orders")
                    // Explicit: the offline path never touches the RPC surface.
                    verifyNoMoreCalls(service)
                }
            }
        }

        test("offline addBooks appends optimistic junction rows in order and enqueues one op per book") {
            runTest {
                withRepo { repo, db, service ->
                    db.readingOrderDao().upsert(orderEntity())

                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.readingOrderBookDao().observeReadingOrderBooks("ro1").first() shouldContainExactly
                        listOf("b1", "b2")
                    db.readingOrderBookDao().findById("ro1:b1")!!.sortOrder shouldBe 0
                    db.readingOrderBookDao().findById("ro1:b2")!!.sortOrder shouldBe 1
                    db.queuedOps().map { it.domainName } shouldContainExactly listOf("reading_order_books", "reading_order_books")
                    verifyNoMoreCalls(service)
                }
            }
        }

        test("offline removeBook tombstones the junction row locally and enqueues a delete op") {
            runTest {
                withRepo { repo, db, service ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo
                        .removeBookFromReadingOrder(ReadingOrderId("ro1"), BookId("b1"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.readingOrderBookDao().observeReadingOrderBooks("ro1").first() shouldBe emptyList()
                    db.readingOrderBookDao().findById("ro1:b1")!!.deletedAt shouldNotBe null
                    verifyNoMoreCalls(service)
                }
            }
        }

        test("offline reorder rewrites local sort order and enqueues one op for the whole ordering") {
            runTest {
                withRepo { repo, db, service ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2"), BookId("b3")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo
                        .reorderBooks(ReadingOrderId("ro1"), listOf(BookId("b3"), BookId("b1"), BookId("b2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.readingOrderBookDao().observeReadingOrderBooks("ro1").first() shouldContainExactly
                        listOf("b3", "b1", "b2")
                    verifyNoMoreCalls(service)
                }
            }
        }

        test("offline setActiveReadingOrder writes the follow row with the deterministic id and enqueues an op") {
            runTest {
                withRepo { repo, db, service ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo
                        .setActiveReadingOrder("series-1", ReadingOrderId("ro1"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.observeActiveReadingOrder("series-1").first() shouldBe ReadingOrderId("ro1")
                    db.readingOrderFollowDao().findById("u1:series-1") shouldNotBe null
                    db.queuedOps().map { it.domainName } shouldContainExactly listOf("reading_order_follows")

                    // Clearing resets to the frontier floor without deleting the row.
                    repo
                        .setActiveReadingOrder("series-1", null)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.observeActiveReadingOrder("series-1").first() shouldBe null
                    verifyNoMoreCalls(service)
                }
            }
        }

        test("observeReadingOrderBookIds emits seeded junction rows ordered by sortOrder") {
            runTest {
                withRepo { repo, db, _ ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2"), BookId("b3")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.observeReadingOrderBookIds(ReadingOrderId("ro1")).first() shouldContainExactly
                        listOf(BookId("b1"), BookId("b2"), BookId("b3"))
                }
            }
        }

        test("observeReadingOrderBookIds emits the new order after reorderBooks") {
            runTest {
                withRepo { repo, db, _ ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2"), BookId("b3")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo
                        .reorderBooks(ReadingOrderId("ro1"), listOf(BookId("b3"), BookId("b1"), BookId("b2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.observeReadingOrderBookIds(ReadingOrderId("ro1")).first() shouldContainExactly
                        listOf(BookId("b3"), BookId("b1"), BookId("b2"))
                }
            }
        }

        test("observeReadingOrderBookIds excludes soft-deleted junction rows") {
            runTest {
                withRepo { repo, db, _ ->
                    db.readingOrderDao().upsert(orderEntity())
                    repo
                        .addBooksToReadingOrder(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo
                        .removeBookFromReadingOrder(ReadingOrderId("ro1"), BookId("b1"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.observeReadingOrderBookIds(ReadingOrderId("ro1")).first() shouldContainExactly
                        listOf(BookId("b2"))
                }
            }
        }

        test("observeActiveReadingOrder falls back to null when the followed order is absent or tombstoned") {
            runTest {
                withRepo { repo, db, service ->
                    // Follow points at an order the local mirror has never seen — the
                    // graceful per-book-frontier floor, never a dangling pointer.
                    repo
                        .setActiveReadingOrder("series-1", ReadingOrderId("ghost"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.observeActiveReadingOrder("series-1").first() shouldBe null

                    // The order arrives in the mirror — the follow resolves again.
                    db.readingOrderDao().upsert(orderEntity(id = "ghost"))
                    repo.observeActiveReadingOrder("series-1").first() shouldBe ReadingOrderId("ghost")

                    // The order is tombstoned (e.g. deleted by its owner) — back to the floor.
                    db.readingOrderDao().softDelete(id = "ghost", deletedAt = 999L, revision = 2L)
                    repo.observeActiveReadingOrder("series-1").first() shouldBe null
                }
            }
        }
    })
