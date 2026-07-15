package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BioEntryMode
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.data.local.db.BioEntryEntity
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.BioEntry
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
        test("createEntity persists to Room as a not-yet-synced stub and enqueues a pending op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.createEntity(EntityKind.CHARACTER, "Kaladin", "series1")

                val id = result.shouldBeInstanceOf<AppResult.Success<String>>().data
                val row = db.entityDao().getById(id)
                row.shouldNotBeNull()
                row.name shouldBe "Kaladin"
                row.kind shouldBe EntityKind.CHARACTER
                row.homeSeriesId shouldBe "series1"
                row.revision shouldBe 0L
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "entities"
                db.close()
            }
        }

        test("updateCore persists the name/image change and carries the kind/series forward unchanged") {
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

        test("upsertBioEntry mints an id for a blank-id entry and persists it") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.entityDao().upsert(entityRow(id = "e1"))
                val repo = buildRepo(db)

                val result =
                    repo.upsertBioEntry(
                        entityId = "e1",
                        entry = BioEntry(id = "", mode = BioEntryMode.APPEND, text = "A soldier.", sortKey = 0),
                    )

                result shouldBe AppResult.Success(Unit)
                val entries = db.bioEntryDao().getForEntity("e1")
                entries shouldHaveSize 1
                entries.single().id.shouldNotBeNull()
                entries.single().text shouldBe "A soldier."
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .single()
                    .domainName shouldBe "entities"
                db.close()
            }
        }

        test("upsertBioEntry with an existing id replaces that entry, leaving others untouched") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.entityDao().upsert(entityRow(id = "e1"))
                db.bioEntryDao().upsertAll(
                    listOf(
                        bioEntryRow(id = "b1", entityId = "e1", text = "First.", sortKey = 0),
                        bioEntryRow(id = "b2", entityId = "e1", text = "Second.", sortKey = 1),
                    ),
                )
                val repo = buildRepo(db)

                val result =
                    repo.upsertBioEntry(
                        entityId = "e1",
                        entry = BioEntry(id = "b1", mode = BioEntryMode.REPLACE, text = "Revised.", sortKey = 0),
                    )

                result shouldBe AppResult.Success(Unit)
                val entries = db.bioEntryDao().getForEntity("e1")
                entries.map { it.id to it.text }.toSet() shouldBe setOf("b1" to "Revised.", "b2" to "Second.")
                db.close()
            }
        }

        test("removeBioEntry deletes only the targeted entry and enqueues a pending op") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.entityDao().upsert(entityRow(id = "e1"))
                db.bioEntryDao().upsertAll(
                    listOf(
                        bioEntryRow(id = "b1", entityId = "e1", text = "First.", sortKey = 0),
                        bioEntryRow(id = "b2", entityId = "e1", text = "Second.", sortKey = 1),
                    ),
                )
                val repo = buildRepo(db)

                val result = repo.removeBioEntry(entityId = "e1", entryId = "b1")

                result shouldBe AppResult.Success(Unit)
                db.bioEntryDao().getForEntity("e1").map { it.id } shouldBe listOf("b2")
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

        test("concurrent upsertBioEntry calls to the same entity never queue a payload missing the other's entry") {
            runTest {
                // Room queries ride the test scheduler so the interleaving below is deterministic.
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                db.entityDao().upsert(entityRow(id = "e1"))

                // The lost-update mechanism this test pins: each write READS current Room state,
                // builds a whole-aggregate EntityUpsert, then enqueues it inside the transaction.
                // This gate parks every transaction until the test opens it — so WITHOUT the
                // repository's edit mutex, BOTH calls complete their reads against the pre-edit
                // (empty) bio set before either write commits, and the op carrying "b" queues a
                // whole-aggregate payload that omits "a" (whose server apply + ServerWins echo
                // would then silently erase "a" everywhere). WITH the mutex, the second call
                // cannot start reading until the first one's read→edit sequence fully completes
                // (the first holds the lock while parked at the gate), so its payload carries both.
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
                        bioEntryDao = db.bioEntryDao(),
                        offlineEditor = offlineEditor,
                    )

                val first =
                    launch {
                        repo.upsertBioEntry(
                            "e1",
                            BioEntry(id = "a", mode = BioEntryMode.APPEND, text = "First.", sortKey = 0),
                        ) shouldBe AppResult.Success(Unit)
                    }
                val second =
                    launch {
                        repo.upsertBioEntry(
                            "e1",
                            BioEntry(id = "b", mode = BioEntryMode.APPEND, text = "Second.", sortKey = 1),
                        ) shouldBe AppResult.Success(Unit)
                    }
                // Run both calls as far as they can go while the gate is closed, then open it.
                advanceUntilIdle()
                gate.complete(Unit)
                first.join()
                second.join()

                // Room converges either way (per-row upserts) — the queue payloads are the seam
                // where the unfixed race loses data.
                db
                    .bioEntryDao()
                    .getForEntity("e1")
                    .map { it.id }
                    .toSet() shouldBe setOf("a", "b")

                // Drain both ops (per-entity FIFO dispatches one op per wave) and decode what the
                // server would actually receive.
                queue.drain()
                queue.drain()
                sentPayloads shouldHaveSize 2
                val upserts =
                    sentPayloads
                        .map { contractJson.decodeFromString(OutboxChannels.Entities.serializer, it) }
                        .map { it.shouldBeInstanceOf<EntityMutation.Upsert>().upsert }
                val opCarryingB = upserts.single { upsert -> upsert.bioEntries.any { it.id == "b" } }
                withClue(
                    "the op that added entry \"b\" queued a whole-aggregate payload missing entry \"a\" — " +
                        "its server apply (and ServerWins echo) would silently erase \"a\"",
                ) {
                    opCarryingB.bioEntries.map { it.id }.toSet() shouldBe setOf("a", "b")
                }
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
        bioEntryDao = db.bioEntryDao(),
        offlineEditor = offlineEditor,
    )
}

private fun entityRow(
    id: String,
    name: String = "Kaladin",
    homeSeriesId: String = "series1",
    revision: Long = 0,
) = EntityEntity(
    id = id,
    kind = EntityKind.CHARACTER,
    name = name,
    homeSeriesId = homeSeriesId,
    revision = revision,
    createdAt = 0L,
    updatedAt = 0L,
)

private fun bioEntryRow(
    id: String,
    entityId: String,
    text: String,
    sortKey: Int,
) = BioEntryEntity(
    id = id,
    entityId = entityId,
    mode = BioEntryMode.APPEND,
    text = text,
    sortKey = sortKey,
)
