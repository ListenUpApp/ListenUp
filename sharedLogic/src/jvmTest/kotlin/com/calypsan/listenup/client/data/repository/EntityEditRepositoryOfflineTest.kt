package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Every [EntityEditRepositoryImpl] op lands in Room and enqueues a pending op with no network —
 * the offline-first invariant, mirroring [SeriesEditRepositoryOfflineTest].
 */
class EntityEditRepositoryOfflineTest :
    FunSpec({
        test("createEntity persists a series-homed stub to Room and enqueues a pending op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.createEntity(EntityKind.CHARACTER, "Kaladin", homeSeriesId = "series1")

                val id = result.shouldBeInstanceOf<AppResult.Success<String>>().data
                val row = db.entityDao().getById(id)
                row.shouldNotBeNull()
                row.name shouldBe "Kaladin"
                row.kind shouldBe EntityKind.CHARACTER
                row.homeSeriesId shouldBe "series1"
                row.homeBookId.shouldBeNull()
                row.revision shouldBe 0L
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "entities"
                db.close()
            }
        }

        test("createEntity persists a book-homed stub to Room and enqueues a pending op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.createEntity(EntityKind.LOCATION, "The Shattered Plains", homeBookId = "book1")

                val id = result.shouldBeInstanceOf<AppResult.Success<String>>().data
                val row = db.entityDao().getById(id)
                row.shouldNotBeNull()
                row.name shouldBe "The Shattered Plains"
                row.kind shouldBe EntityKind.LOCATION
                row.homeSeriesId.shouldBeNull()
                row.homeBookId shouldBe "book1"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "entities"
                db.close()
            }
        }

        test("createEntity fails validation when both homeSeriesId and homeBookId are set, with no Room write or queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result =
                    repo.createEntity(EntityKind.CHARACTER, "Kaladin", homeSeriesId = "series1", homeBookId = "book1")

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("createEntity fails validation when neither homeSeriesId nor homeBookId is set, with no Room write or queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.createEntity(EntityKind.CHARACTER, "Kaladin")

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("updateCore persists the name/image change and carries the kind/home forward unchanged") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.entityDao().upsert(entityRow(id = "e1", name = "Old Name", homeSeriesId = "series1"))
                val repo = buildRepo(db)

                val result = repo.updateCore(id = "e1", name = "New Name", imageRef = "cover.jpg")

                result shouldBe AppResult.Success(Unit)
                val row = db.entityDao().getById("e1")
                row.shouldNotBeNull()
                row.name shouldBe "New Name"
                row.imageRef shouldBe "cover.jpg"
                row.kind shouldBe EntityKind.CHARACTER
                row.homeSeriesId shouldBe "series1"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .single()
                    .domainName shouldBe "entities"
                db.close()
            }
        }

        test("deleteEntity soft-deletes the entity and enqueues an entities delete op") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.entityDao().upsert(entityRow(id = "e1", revision = 3))
                val repo = buildRepo(db)

                val result = repo.deleteEntity("e1")

                result shouldBe AppResult.Success(Unit)
                db.entityDao().getById("e1").shouldBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "entities"
                op.entityId shouldBe "e1"
                op.opType shouldBe "delete"
                db.close()
            }
        }

        test("updateCore fails with a not-found error when the entity is absent from Room, with no queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.updateCore(id = "missing", name = "X", imageRef = null)

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("a concurrent updateCore never resurrects an entity a deleteEntity is tombstoning") {
            runTest {
                // Room queries ride the test scheduler so the interleaving below is deterministic.
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                db.entityDao().upsert(entityRow(id = "e1", name = "Old Name", revision = 3))

                // The lost-update mechanism this test pins is asymmetric between the two writers:
                // updateCore reads `existing` and closes over it BEFORE the gated transaction —
                // `existing.copy(name = name, imageRef = imageRef)` silently carries forward
                // whatever `deletedAt` that snapshot had. deleteEntity reads INSIDE the gated
                // transaction. WITHOUT the repository's edit mutex, both calls' gate.await() calls
                // are registered back-to-back before either commits — updateCore's `existing` is
                // captured pre-delete (deletedAt = null) — so if deleteEntity's transaction happens
                // to run first (tombstoning the row) and updateCore's runs second, updateCore's
                // stale-snapshot copy() overwrites deletedAt back to null: the just-deleted entity
                // is silently RESURRECTED, carrying the rename. WITH the mutex, deleteEntity (launched
                // first) fully completes — including its gated transaction — before updateCore can
                // even acquire the lock to perform its own `existing` read; that read then correctly
                // observes the tombstone (Room's `getById` filters `deletedAt IS NULL`) and updateCore
                // fails with a not-found error instead of resurrecting the row.
                val gate = CompletableDeferred<Unit>()
                val sentPayloads = mutableListOf<String>()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender =
                            PendingOperationSender { op ->
                                sentPayloads += op.payload
                                AppResult.Success(Unit)
                            },
                    )
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner =
                            object : TransactionRunner {
                                override suspend fun <R> atomically(block: suspend () -> R): R {
                                    gate.await()
                                    return block()
                                }
                            },
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                val repo =
                    EntityEditRepositoryImpl(
                        entityDao = db.entityDao(),
                        offlineEditor = offlineEditor,
                    )

                lateinit var renameResult: AppResult<Unit>
                val delete = launch { repo.deleteEntity("e1") shouldBe AppResult.Success(Unit) }
                val rename =
                    launch {
                        renameResult = repo.updateCore(id = "e1", name = "New Name", imageRef = null)
                    }
                // Run both calls as far as they can go while the gate is closed, then open it.
                advanceUntilIdle()
                gate.complete(Unit)
                delete.join()
                rename.join()

                withClue("updateCore's stale pre-delete read resurrected the entity deleteEntity just tombstoned") {
                    db.entityDao().getById("e1").shouldBeNull()
                }
                renameResult.shouldBeInstanceOf<AppResult.Failure>()

                // Only the delete's op is ever queued — the mutex stops updateCore from reaching
                // its own transaction at all (it fails the not-found check first).
                queue.drain()
                sentPayloads shouldHaveSize 1
                contractJson
                    .decodeFromString(OutboxChannels.Entities.serializer, sentPayloads.single())
                    .shouldBeInstanceOf<EntityMutation.Delete>()
                db.close()
            }
        }
    })

private fun buildRepo(db: ListenUpDatabase): EntityEditRepositoryImpl {
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
    return EntityEditRepositoryImpl(
        entityDao = db.entityDao(),
        offlineEditor = offlineEditor,
    )
}

private fun entityRow(
    id: String,
    name: String = "Kaladin",
    homeSeriesId: String? = "series1",
    homeBookId: String? = null,
    revision: Long = 0,
) = EntityEntity(
    id = id,
    kind = EntityKind.CHARACTER,
    name = name,
    homeSeriesId = homeSeriesId,
    homeBookId = homeBookId,
    revision = revision,
    createdAt = 0L,
    updatedAt = 0L,
)
