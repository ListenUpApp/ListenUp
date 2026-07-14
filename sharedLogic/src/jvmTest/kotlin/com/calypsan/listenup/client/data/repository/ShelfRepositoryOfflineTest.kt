package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.dto.shelf.Shelf as ShelfDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Offline-first contract for the shelf write surface. `updateShelf`, `deleteShelf`,
 * `addBooksToShelf`, and `removeBookFromShelf` must write Room and enqueue a durable outbox op
 * with no server present. `createShelf` (server-minted id) and `reorderBooks` (whole-shelf
 * sortOrder reconcile) stay online and enqueue nothing.
 */
class ShelfRepositoryOfflineTest :
    FunSpec({
        fun shelf(id: String) =
            ShelfEntity(
                id = id,
                name = "Reading",
                description = "desc",
                isPrivate = false,
                revision = 1,
                deletedAt = null,
                updatedAt = 100L,
                createdAt = 50L,
            )

        fun junction(
            shelfId: String,
            bookId: String,
            sortOrder: Int,
        ) = ShelfBookEntity(
            id = "$shelfId:$bookId",
            shelfId = shelfId,
            bookId = bookId,
            sortOrder = sortOrder,
            revision = 1,
            deletedAt = null,
            updatedAt = 100L,
            createdAt = 50L,
        )

        test("updateShelf applies the edit to Room and enqueues a shelves op keyed by the shelf id") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.shelfDao().upsert(shelf("s1"))
                val repo = repo(db)

                val result = repo.updateShelf(ShelfId("s1"), "Renamed", "new desc", isPrivate = true)

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val row = db.shelfDao().getById("s1").shouldNotBeNull()
                row.name shouldBe "Renamed"
                row.isPrivate shouldBe true
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "shelves"
                op.entityId shouldBe "s1"
                op.opType shouldBe "update"
                db.close()
            }
        }

        test("deleteShelf soft-deletes the shelf, cascade-tombstones shelf_books, and enqueues a shelves delete op") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.shelfDao().upsert(shelf("s1"))
                db.shelfBookDao().upsert(junction("s1", "b1", 0))
                db.shelfBookDao().upsert(junction("s1", "b2", 1))
                val repo = repo(db)

                val result = repo.deleteShelf(ShelfId("s1"))

                result shouldBe AppResult.Success(Unit)
                db.shelfDao().getById("s1").shouldBeNull() // getById excludes tombstones
                db
                    .shelfBookDao()
                    .findById("s1:b1")
                    ?.deletedAt
                    .shouldNotBeNull()
                db
                    .shelfBookDao()
                    .findById("s1:b2")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "shelves"
                op.entityId shouldBe "s1"
                op.opType shouldBe "delete"
                db.close()
            }
        }

        test("removeBookFromShelf tombstones the junction and enqueues a shelf_books op keyed by \$shelfId:\$bookId") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.shelfBookDao().upsert(junction("s1", "b1", 0))
                val repo = repo(db)

                val result = repo.removeBookFromShelf(ShelfId("s1"), BookId("b1"))

                result shouldBe AppResult.Success(Unit)
                db
                    .shelfBookDao()
                    .findById("s1:b1")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "shelf_books"
                op.entityId shouldBe "s1:b1"
                op.opType shouldBe "delete"
                db.close()
            }
        }

        test("addBooksToShelf upserts each junction appended after the current max and enqueues one create op per book") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.shelfBookDao().upsert(junction("s1", "b1", 0)) // existing member at sortOrder 0
                val repo = repo(db)

                val result = repo.addBooksToShelf(ShelfId("s1"), listOf(BookId("b2"), BookId("b3")))

                result shouldBe AppResult.Success(Unit)
                db
                    .shelfBookDao()
                    .findById("s1:b2")
                    .shouldNotBeNull()
                    .sortOrder shouldBe 1
                db
                    .shelfBookDao()
                    .findById("s1:b3")
                    .shouldNotBeNull()
                    .sortOrder shouldBe 2
                val ops = db.pendingOperationV2Dao().nextDispatchable()
                ops.map { it.entityId }.toSet() shouldBe setOf("s1:b2", "s1:b3")
                ops.forEach {
                    it.domainName shouldBe "shelf_books"
                    it.opType shouldBe "create"
                }
                db.close()
            }
        }

        test("createShelf stays online — it dispatches to the RPC and enqueues nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                val service = mock<ShelfService>()
                everySuspend { service.createShelf(any(), any(), any()) } returns
                    AppResult.Success(
                        ShelfDto(
                            id = ShelfId("s-new"),
                            name = "New",
                            description = "",
                            isPrivate = false,
                            bookCount = 0,
                            updatedAt = 0L,
                        ),
                    )
                val repo = repo(db, service)

                val result = repo.createShelf("New", null, isPrivate = false)

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("reorderBooks stays online — it dispatches to the RPC and enqueues nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                val service = mock<ShelfService>()
                everySuspend { service.reorderShelfBooks(any(), any()) } returns AppResult.Success(Unit)
                val repo = repo(db, service)

                val result = repo.reorderBooks(ShelfId("s1"), listOf(BookId("b2"), BookId("b1")))

                result shouldBe AppResult.Success(Unit)
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }
    })

private fun repo(
    db: ListenUpDatabase,
    service: ShelfService = mock(),
): ShelfRepositoryImpl {
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
            authSession = FakeAuthSession(userId = "u1"),
        )
    val userDao = mock<UserDao> { everySuspend { getCurrentUser() } returns null }
    return ShelfRepositoryImpl(
        dao = db.shelfDao(),
        shelfBookDao = db.shelfBookDao(),
        userDao = userDao,
        channel = RpcChannel.forTest(service),
        offlineEditor = offlineEditor,
    )
}
