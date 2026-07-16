package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.WorldEventEntity
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.NewWorldEvent
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
 * Every [WorldEventEditRepositoryImpl] op lands in Room and enqueues a pending op with no
 * network — the offline-first invariant, mirroring [EntityEditRepositoryOfflineTest]. Additional
 * coverage specific to this domain: a single write's mention rows land alongside its event row,
 * and [WorldEventEditRepositoryImpl.recordBatch] queues exactly ONE outbox row for N applied events.
 */
class WorldEventEditRepositoryOfflineTest :
    FunSpec({
        test("record persists a series-homed event and its mentions, and enqueues exactly one pending op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)
                val text = "${MentionTokens.token("kaladin", "Kaladin")} enters the plains."

                val result =
                    repo.record(
                        NewWorldEvent(
                            type = WorldEventType.ENTERS_SCENE,
                            text = text,
                            homeSeriesId = "series1",
                            subjectEntityId = "kaladin",
                        ),
                    )

                val id = result.shouldBeInstanceOf<AppResult.Success<String>>().data
                val row = db.worldEventDao().getById(id)
                row.shouldNotBeNull()
                row.text shouldBe text
                row.type shouldBe WorldEventType.ENTERS_SCENE
                row.homeSeriesId shouldBe "series1"
                row.homeBookId.shouldBeNull()
                row.subjectEntityId shouldBe "kaladin"
                row.source shouldBe WorldEventSource.MANUAL
                row.revision shouldBe 0L
                db
                    .worldEventDao()
                    .mentionsForEventRaw(id)
                    .map { it.entityId }
                    .toSet() shouldBe setOf("kaladin")
                val ops = db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5)
                ops shouldHaveSize 1
                ops.single().domainName shouldBe "world_events"
                db.close()
            }
        }

        test("record persists a book-homed event with no mentions") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result =
                    repo.record(
                        NewWorldEvent(
                            type = WorldEventType.NOTE,
                            text = "A quiet moment.",
                            homeBookId = "book1",
                        ),
                    )

                val id = result.shouldBeInstanceOf<AppResult.Success<String>>().data
                val row = db.worldEventDao().getById(id)
                row.shouldNotBeNull()
                row.homeSeriesId.shouldBeNull()
                row.homeBookId shouldBe "book1"
                db.worldEventDao().mentionsForEventRaw(id).shouldBeEmpty()
                db.close()
            }
        }

        test("record fails validation when both homeSeriesId and homeBookId are set, with no Room write or queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result =
                    repo.record(
                        NewWorldEvent(
                            type = WorldEventType.NOTE,
                            text = "x",
                            homeSeriesId = "series1",
                            homeBookId = "book1",
                        ),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("record fails validation when neither homeSeriesId nor homeBookId is set, with no Room write or queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.record(NewWorldEvent(type = WorldEventType.NOTE, text = "x"))

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("recordBatch applies N events to Room but enqueues exactly one pending op carrying an N-op batch") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result =
                    repo.recordBatch(
                        listOf(
                            NewWorldEvent(
                                type = WorldEventType.ENTERS_SCENE,
                                text = "Kaladin enters.",
                                homeSeriesId = "series1",
                                subjectEntityId = "kaladin",
                            ),
                            NewWorldEvent(
                                type = WorldEventType.DEPARTS,
                                text = "Shallan departs.",
                                homeSeriesId = "series1",
                                subjectEntityId = "shallan",
                            ),
                        ),
                    )

                val ids = result.shouldBeInstanceOf<AppResult.Success<List<String>>>().data
                ids shouldHaveSize 2
                ids.forEach { id -> db.worldEventDao().getById(id).shouldNotBeNull() }
                val ops = db.pendingOperationV2Dao().nextDispatchable()
                ops shouldHaveSize 1
                ops.single().domainName shouldBe "world_events"
                val batch = contractJson.decodeFromString(OutboxChannels.WorldEvents.serializer, ops.single().payload)
                batch.ops shouldHaveSize 2
                batch.ops.forEach { it.shouldBeInstanceOf<WorldEventOp.Upsert>() }
                db.close()
            }
        }

        test("recordBatch with an empty list is a no-op success with no Room write or queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result = repo.recordBatch(emptyList())

                result shouldBe AppResult.Success(emptyList())
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("recordBatch fails whole-batch when any event violates the dual-home rule, applying nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result =
                    repo.recordBatch(
                        listOf(
                            NewWorldEvent(type = WorldEventType.NOTE, text = "ok", homeSeriesId = "series1"),
                            NewWorldEvent(type = WorldEventType.NOTE, text = "bad"),
                        ),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("update persists the full snapshot, carries revision/createdAt forward, and replaces mentions") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.worldEventDao().upsert(eventRow(id = "ev1", text = "Old text", revision = 3))
                db.worldEventDao().replaceMentions("ev1", setOf("kaladin"))
                val repo = buildRepo(db)
                val newText = "New text ${MentionTokens.token("shallan", "Shallan")}"

                val result =
                    repo.update(
                        WorldEventUpsert(
                            id = "ev1",
                            homeSeriesId = "series1",
                            type = WorldEventType.MOVES_TO,
                            text = newText,
                            subjectEntityId = "shallan",
                        ),
                    )

                result shouldBe AppResult.Success(Unit)
                val row = db.worldEventDao().getById("ev1")
                row.shouldNotBeNull()
                row.text shouldBe newText
                row.type shouldBe WorldEventType.MOVES_TO
                row.subjectEntityId shouldBe "shallan"
                row.revision shouldBe 3L
                db
                    .worldEventDao()
                    .mentionsForEventRaw("ev1")
                    .map { it.entityId }
                    .toSet() shouldBe setOf("shallan")
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .single()
                    .domainName shouldBe "world_events"
                db.close()
            }
        }

        test("update fails with a not-found error when the event is absent from Room, with no queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val repo = buildRepo(db)

                val result =
                    repo.update(
                        WorldEventUpsert(id = "missing", homeSeriesId = "series1", type = WorldEventType.NOTE, text = "x"),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }

        test("delete soft-deletes the event, clears its mentions, and enqueues a world_events delete op") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.worldEventDao().upsert(eventRow(id = "ev1", revision = 3))
                db.worldEventDao().replaceMentions("ev1", setOf("kaladin"))
                val repo = buildRepo(db)

                val result = repo.delete("ev1")

                result shouldBe AppResult.Success(Unit)
                db.worldEventDao().getById("ev1").shouldBeNull()
                db.worldEventDao().mentionsForEventRaw("ev1").shouldBeEmpty()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "world_events"
                val batch = contractJson.decodeFromString(OutboxChannels.WorldEvents.serializer, op.payload)
                batch.ops.single().shouldBeInstanceOf<WorldEventOp.Delete>()
                db.close()
            }
        }

        test("a concurrent update never resurrects an event a delete is tombstoning") {
            runTest {
                // Room queries ride the test scheduler so the interleaving below is deterministic.
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                db.worldEventDao().upsert(eventRow(id = "ev1", text = "Old text", revision = 3))

                // Same asymmetric mechanism EntityEditRepositoryOfflineTest pins for updateCore/deleteEntity:
                // update() reads `existing` and closes over it INSIDE editMutex.withLock but BEFORE the
                // gated transaction — `existing.copy(...)` silently carries forward whatever `deletedAt`
                // that snapshot had. delete() reads `existing` INSIDE the gated transaction. WITHOUT the
                // mutex, both calls could reach their own `existing` read before either commits, so a
                // delete that lands first could still be resurrected by update's stale pre-delete copy.
                // WITH the mutex (production behaviour under test here), delete (launched first) holds
                // the lock through its whole gated transaction before update can even acquire the lock to
                // perform its own `existing` read — that read then correctly observes the tombstone
                // (Room's `getById` filters `deletedAt IS NULL`) and update fails not-found instead of
                // resurrecting the row.
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
                    WorldEventEditRepositoryImpl(
                        worldEventDao = db.worldEventDao(),
                        offlineEditor = offlineEditor,
                    )

                lateinit var updateResult: AppResult<Unit>
                val delete = launch { repo.delete("ev1") shouldBe AppResult.Success(Unit) }
                val update =
                    launch {
                        updateResult =
                            repo.update(
                                WorldEventUpsert(
                                    id = "ev1",
                                    homeSeriesId = "series1",
                                    type = WorldEventType.NOTE,
                                    text = "New text",
                                ),
                            )
                    }
                // Run both calls as far as they can go while the gate is closed, then open it.
                advanceUntilIdle()
                gate.complete(Unit)
                delete.join()
                update.join()

                withClue("update's stale pre-delete read resurrected the event delete just tombstoned") {
                    db.worldEventDao().getById("ev1").shouldBeNull()
                }
                updateResult.shouldBeInstanceOf<AppResult.Failure>()

                // Only the delete's op is ever queued — the mutex stops update from reaching its own
                // transaction at all (it fails the not-found check first).
                queue.drain()
                sentPayloads shouldHaveSize 1
                val batch = contractJson.decodeFromString(OutboxChannels.WorldEvents.serializer, sentPayloads.single())
                batch.ops.single().shouldBeInstanceOf<WorldEventOp.Delete>()
                db.close()
            }
        }
    })

private fun buildRepo(db: ListenUpDatabase): WorldEventEditRepositoryImpl {
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
    return WorldEventEditRepositoryImpl(
        worldEventDao = db.worldEventDao(),
        offlineEditor = offlineEditor,
    )
}

private fun eventRow(
    id: String,
    text: String = "Kaladin enters the plains.",
    homeSeriesId: String? = "series1",
    homeBookId: String? = null,
    revision: Long = 0,
) = WorldEventEntity(
    id = id,
    homeSeriesId = homeSeriesId,
    homeBookId = homeBookId,
    type = WorldEventType.NOTE,
    text = text,
    source = WorldEventSource.MANUAL,
    revision = revision,
    createdAt = 0L,
    updatedAt = 0L,
)
