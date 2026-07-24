package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.CollectionId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Offline-first contract for the collection write surface. `rename`, `delete`, `addBook`, and
 * `removeBook` must write Room and enqueue a durable outbox op with no server present. `create`
 * (server-minted id) and the ACL surfaces (`share`/`revokeShare`) stay online.
 */
class CollectionRepositoryOfflineTest :
    FunSpec({
        fun collection(id: String) =
            CollectionEntity(
                id = id,
                libraryId = "lib1",
                ownerId = "owner1",
                name = "Faves",
                isInbox = false,
                revision = 1,
                deletedAt = null,
                updatedAt = 100L,
            )

        fun junction(
            collectionId: String,
            bookId: String,
        ) = CollectionBookEntity(
            collectionId = collectionId,
            bookId = bookId,
            syncId = "$collectionId:$bookId",
            createdAt = 50L,
            revision = 1,
            deletedAt = null,
        )

        test("rename applies the new name to Room and enqueues a collections op keyed by the collection id") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.collectionDao().upsert(collection("c1"))
                val repo = repo(db)

                val result = repo.rename("c1", "Renamed")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                db
                    .collectionDao()
                    .getById("c1")
                    .shouldNotBeNull()
                    .name shouldBe "Renamed"
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "collections"
                op.entityId shouldBe "c1"
                op.opType shouldBe "update"
                db.close()
            }
        }

        test("delete soft-deletes the collection, cascade-tombstones collection_books, and enqueues a delete op") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.collectionDao().upsert(collection("c1"))
                db.collectionBookDao().upsert(junction("c1", "b1"))
                db.collectionBookDao().upsert(junction("c1", "b2"))
                val repo = repo(db)

                val result = repo.delete("c1")

                result shouldBe AppResult.Success(Unit)
                db.collectionDao().getById("c1").shouldBeNull() // getById excludes tombstones
                db
                    .collectionBookDao()
                    .findByKey("c1", "b1")
                    ?.deletedAt
                    .shouldNotBeNull()
                db
                    .collectionBookDao()
                    .findByKey("c1", "b2")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "collections"
                op.entityId shouldBe "c1"
                op.opType shouldBe "delete"
                db.close()
            }
        }

        test("addBook upserts the junction as a revision-0 stub and enqueues a collection_books create op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)

                val result = repo.addBook("c1", "b1")

                result shouldBe AppResult.Success(Unit)
                val row = db.collectionBookDao().findByKey("c1", "b1").shouldNotBeNull()
                row.deletedAt.shouldBeNull()
                row.revision shouldBe 0
                // SERVER-SYNC-04: the optimistic row already carries a non-blank, client-minted
                // opaque syncId — never a "$collectionId:$bookId" composite (satisfies the syncId
                // NOT NULL constraint before any server round-trip).
                row.syncId.isBlank() shouldBe false
                row.syncId shouldNotBe "c1:b1"
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "collection_books"
                op.entityId shouldBe "c1:b1"
                op.opType shouldBe "create"
                db.close()
            }
        }

        test("addBook's optimistic syncId self-heals to the server's authoritative id on echo") {
            // SERVER-SYNC-04 (Locked Decision #3): the client mints a placeholder syncId offline-first;
            // the server mints its own authoritative id independently. Room's @Upsert is keyed on the
            // NATURAL pair (collectionId, bookId) — not syncId — so the server's Created echo (carrying
            // the real id) overwrites the local row in place instead of creating a duplicate.
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = repo(db)
                repo.addBook("c1", "b1")
                val clientMintedSyncId = db.collectionBookDao().findByKey("c1", "b1")!!.syncId

                val handler =
                    collectionBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                val serverSyncId = "server-authoritative-id"
                handler.onEvent(
                    SyncEvent.Created(
                        id = serverSyncId,
                        revision = 1L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload =
                            CollectionBookSyncPayload(
                                id = serverSyncId,
                                collectionId = "c1",
                                bookId = "b1",
                                createdAt = 200L,
                                revision = 1L,
                            ),
                    ),
                )

                val row = db.collectionBookDao().findByKey("c1", "b1")!!
                row.syncId shouldBe serverSyncId
                row.syncId shouldNotBe clientMintedSyncId
                row.revision shouldBe 1L
                // Still exactly one row for the natural pair — no duplicate.
                db.collectionBookDao().liveSyncIds() shouldBe listOf(serverSyncId)
                db.close()
            }
        }

        test("removeBook tombstones the junction and enqueues a collection_books delete op keyed by \$collectionId:\$bookId") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.collectionBookDao().upsert(junction("c1", "b1"))
                val repo = repo(db)

                val result = repo.removeBook("c1", "b1")

                result shouldBe AppResult.Success(Unit)
                db
                    .collectionBookDao()
                    .findByKey("c1", "b1")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "collection_books"
                op.entityId shouldBe "c1:b1"
                op.opType shouldBe "delete"
                db.close()
            }
        }

        test("create stays online — it dispatches to the RPC and enqueues nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                val service = mock<CollectionService>()
                everySuspend { service.createCollection("lib1", "New") } returns
                    AppResult.Success(
                        CollectionSummary(
                            id = CollectionId("c-new"),
                            name = "New",
                            ownerId = UserId("owner1"),
                            isInbox = false,
                            bookCount = 0L,
                            callerPermission = SharePermission.Write,
                            isOwner = true,
                        ),
                    )
                val repo = repo(db, service)

                val result = repo.create("lib1", "New")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("revokeShare stays online — it dispatches to the RPC and enqueues nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                val service = mock<CollectionService>()
                everySuspend { service.revokeShare(any(), any()) } returns AppResult.Success(Unit)
                val repo = repo(db, service)

                val result = repo.revokeShare("c1", "u2")

                result shouldBe AppResult.Success(Unit)
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }
    })

private fun repo(
    db: ListenUpDatabase,
    service: CollectionService = mock(),
): CollectionRepositoryImpl {
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
    return CollectionRepositoryImpl(
        collectionDao = db.collectionDao(),
        collectionBookDao = db.collectionBookDao(),
        collectionShareDao = db.collectionShareDao(),
        channel = RpcChannel.forTest(service),
        offlineEditor = offlineEditor,
    )
}
