package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.remote.forTestScripted
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperation
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.model.EntityEdit
import com.calypsan.listenup.client.domain.model.WorldEntityChange
import com.calypsan.listenup.client.domain.model.WorldEntityChangeOp
import com.calypsan.listenup.client.domain.model.WorldEntityDraft
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.StoryWorldHistoryId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

private val DRAFT =
    WorldEntityDraft(kind = EntityKind.CHARACTER, name = "Darrow", homeBookId = BookId("b1"))

/**
 * Offline-first contract: create, edit and delete write Room and queue an `entities` op with no server
 * present. Undo of a create or an edit is a forward write through the same outbox. Undo of a delete
 * cancels the Delete while it is unsent, and otherwise reverts the server's DELETE online.
 */
class EntityEditRepositoryOfflineTest :
    FunSpec({
        test("create writes Room and queues an upsert keyed by the new id") {
            runTest {
                val rig = Rig()
                val edit =
                    rig.repo
                        .createEntity(DRAFT)
                        .shouldBeInstanceOf<AppResult.Success<EntityEdit>>()
                        .data

                edit.before.shouldBeNull()
                rig.db
                    .entityDao()
                    .getById(edit.entityId.value)
                    .shouldNotBeNull()
                    .name shouldBe "Darrow"
                val op =
                    rig.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "entities"
                op.entityId shouldBe edit.entityId.value
                op.opType shouldBe "upsert"
                rig.db.close()
            }
        }

        test("an edit returns the earlier state, and undoing it offline restores it through the outbox") {
            runTest {
                val rig = Rig()
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data
                val renamed =
                    rig.repo
                        .updateEntity(created.entityId, name = "Reaper", descriptor = "Helldiver", parentId = null)
                        .shouldBeInstanceOf<AppResult.Success<EntityEdit>>()
                        .data
                renamed.before.shouldNotBeNull().name shouldBe "Darrow"

                rig.repo.undo(renamed) shouldBe AppResult.Success(Unit)

                rig.db
                    .entityDao()
                    .getById(created.entityId.value)
                    .shouldNotBeNull()
                    .name shouldBe "Darrow"
                val queued =
                    rig.db
                        .pendingOperationV2Dao()
                        .latestQueuedPayload("entities", created.entityId.value, "upsert")
                        .shouldNotBeNull()
                val upsert =
                    contractJson
                        .decodeFromString(EntityMutation.serializer(), queued)
                        .shouldBeInstanceOf<EntityMutation.Upsert>()
                upsert.upsert.name shouldBe "Darrow"
                upsert.upsert.descriptor.shouldBeNull()
                rig.db.pendingOperationV2Dao().countDispatchable() shouldBe 3
                rig.db.close()
            }
        }

        test("immediately undoing your own create deletes it locally and queues the Delete") {
            runTest {
                val rig = Rig()
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data

                rig.repo.undo(created) shouldBe AppResult.Success(Unit)

                rig.db
                    .entityDao()
                    .getById(created.entityId.value)
                    .shouldBeNull()
                rig.db
                    .pendingOperationV2Dao()
                    .latestQueuedPayload("entities", created.entityId.value, "delete")
                    .shouldNotBeNull()
                rig.drain()
                rig.sent.map { it.opType } shouldBe listOf("upsert", "delete")
                rig.db.close()
            }
        }

        test("undoing a still-queued delete cancels it, restores the row, and nothing reaches the server") {
            runTest {
                val service = mock<EntityService>()
                val rig = Rig(service)
                val other = (rig.repo.createEntity(DRAFT.copy(name = "Sevro")) as AppResult.Success).data
                val deleted = (rig.repo.deleteEntity(other.entityId) as AppResult.Success).data
                rig.db
                    .entityDao()
                    .getById(other.entityId.value)
                    .shouldBeNull()

                rig.repo.undo(deleted) shouldBe AppResult.Success(Unit)

                rig.db
                    .entityDao()
                    .getById(other.entityId.value)
                    .shouldNotBeNull()
                    .name shouldBe "Sevro"
                rig.drain()
                rig.sent.map { it.opType } shouldBe listOf("upsert")
                // The revert path is chosen by "the Delete was sent", and it wasn't: no server call at all.
                verifySuspend(VerifyMode.not) { service.listHistory(any()) }
                verifySuspend(VerifyMode.not) { service.revert(any()) }
                rig.db.close()
            }
        }

        test("undoing a sent delete online reverts the server's DELETE and the entity comes back") {
            runTest {
                val service = mock<EntityService>()
                val rig = Rig(service)
                val created = (rig.repo.createEntity(DRAFT.copy(name = "Sevro")) as AppResult.Success).data
                val id = created.entityId
                val deleted = (rig.repo.deleteEntity(id) as AppResult.Success).data
                rig.drain()
                rig.sent.map { it.opType } shouldBe listOf("upsert", "delete")

                val live = payload(id, name = "Sevro", revision = 3)
                val tomb = live.copy(revision = 4, deletedAt = 40)
                everySuspend { service.listHistory(id) } returns
                    AppResult.Success(
                        listOf(
                            change("h2", id, StoryWorldOp.DELETE, before = live, after = tomb),
                            change("h1", id, StoryWorldOp.CREATE, before = null, after = live),
                        ),
                    )
                everySuspend { service.revert(StoryWorldHistoryId("h2")) } returns
                    AppResult.Success(change("h3", id, StoryWorldOp.REVERT, before = tomb, after = live.copy(revision = 5)))

                rig.repo.undo(deleted) shouldBe AppResult.Success(Unit)

                verifySuspend(VerifyMode.exactly(1)) { service.revert(StoryWorldHistoryId("h2")) }
                rig.db
                    .entityDao()
                    .getById(id.value)
                    .shouldNotBeNull()
                    .revision shouldBe 5
                rig.db.close()
            }
        }

        test("undoing a sent delete offline fails with a typed error and the row stays deleted") {
            runTest {
                val rig = Rig(channel = RpcChannel.forTestScripted(mock<EntityService>(), faults = listOf(IOException("offline"))))
                val created = (rig.repo.createEntity(DRAFT.copy(name = "Sevro")) as AppResult.Success).data
                val deleted = (rig.repo.deleteEntity(created.entityId) as AppResult.Success).data
                rig.drain()

                rig.repo
                    .undo(deleted)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.NetworkUnavailable>()

                rig.db
                    .entityDao()
                    .getById(created.entityId.value)
                    .shouldBeNull()
                rig.db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .shouldBeEmpty()
                rig.db.close()
            }
        }

        test("a sent delete someone has since built on is not reverted blindly") {
            runTest {
                val service = mock<EntityService>()
                val rig = Rig(service)
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data
                val id = created.entityId
                val deleted = (rig.repo.deleteEntity(id) as AppResult.Success).data
                rig.drain()
                val live = payload(id, name = "Darrow", revision = 3)
                everySuspend { service.listHistory(id) } returns
                    AppResult.Success(
                        listOf(
                            change("h3", id, StoryWorldOp.REVERT, before = live.copy(deletedAt = 40), after = live),
                            change("h2", id, StoryWorldOp.DELETE, before = live, after = live.copy(deletedAt = 40)),
                        ),
                    )

                rig.repo
                    .undo(deleted)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<EntityError.HistoryNotFound>()
                verifySuspend(VerifyMode.not) { service.revert(any()) }
                rig.db.close()
            }
        }

        test("a delete parked by an unreachable server never left the device, so undo still withdraws it") {
            runTest {
                val service = mock<EntityService>()
                val rig =
                    Rig(service, sender = { op ->
                        if (op.opType == "delete") AppResult.Failure(TransportError.NetworkUnavailable()) else AppResult.Success(Unit)
                    })
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data
                val deleted = (rig.repo.deleteEntity(created.entityId) as AppResult.Success).data
                rig.drain()

                rig.repo.undo(deleted) shouldBe AppResult.Success(Unit)

                rig.db
                    .entityDao()
                    .getById(created.entityId.value)
                    .shouldNotBeNull()
                    .name shouldBe "Darrow"
                rig.db
                    .pendingOperationV2Dao()
                    .latestQueuedPayload("entities", created.entityId.value, "delete")
                    .shouldBeNull()
                verifySuspend(VerifyMode.not) { service.listHistory(any()) }
                rig.db.close()
            }
        }

        test("a delete whose send was lost without a verdict can't be undone yet, and stays queued") {
            runTest {
                val rig =
                    Rig(sender = { op ->
                        if (op.opType == "delete") AppResult.Failure(TransportError.OutcomeUnknown()) else AppResult.Success(Unit)
                    })
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data
                val deleted = (rig.repo.deleteEntity(created.entityId) as AppResult.Success).data
                rig.drain()

                rig.repo
                    .undo(deleted)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                rig.db
                    .entityDao()
                    .getById(created.entityId.value)
                    .shouldBeNull()
                rig.db
                    .pendingOperationV2Dao()
                    .latestQueuedPayload("entities", created.entityId.value, "delete")
                    .shouldNotBeNull()
                rig.db.close()
            }
        }

        test("undoing a delete the server refused withdraws the dead letter and restores the row locally") {
            runTest {
                val service = mock<EntityService>()
                val rig =
                    Rig(service, sender = { op ->
                        if (op.opType == "delete") {
                            AppResult.Failure(TransportError.Server4xx(statusCode = 403))
                        } else {
                            AppResult.Success(Unit)
                        }
                    })
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data
                val deleted = (rig.repo.deleteEntity(created.entityId) as AppResult.Success).data
                rig.drain()
                rig.db
                    .pendingOperationV2Dao()
                    .observeDeadLetterCount()
                    .first() shouldBe 1

                rig.repo.undo(deleted) shouldBe AppResult.Success(Unit)

                rig.db
                    .entityDao()
                    .getById(created.entityId.value)
                    .shouldNotBeNull()
                    .name shouldBe "Darrow"
                rig.db
                    .pendingOperationV2Dao()
                    .observeDeadLetterCount()
                    .first() shouldBe 0
                // The server never accepted the delete, so there is nothing to revert there.
                verifySuspend(VerifyMode.not) { service.listHistory(any()) }
                verifySuspend(VerifyMode.not) { service.revert(any()) }
                rig.db.close()
            }
        }

        test("a sent delete whose newest DELETE is someone else's is never reverted through your undo") {
            runTest {
                val service = mock<EntityService>()
                val rig = Rig(service)
                val created = (rig.repo.createEntity(DRAFT) as AppResult.Success).data
                val id = created.entityId
                val deleted = (rig.repo.deleteEntity(id) as AppResult.Success).data
                rig.drain()
                val live = payload(id, name = "Darrow", revision = 3)
                everySuspend { service.listHistory(id) } returns
                    AppResult.Success(
                        listOf(
                            change("h3", id, StoryWorldOp.DELETE, before = live, after = live.copy(deletedAt = 50), actorId = "u2"),
                            change("h2", id, StoryWorldOp.DELETE, before = live, after = live.copy(deletedAt = 40)),
                        ),
                    )

                rig.repo
                    .undo(deleted)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<EntityError.HistoryNotFound>()
                verifySuspend(VerifyMode.not) { service.revert(any()) }
                rig.db.close()
            }
        }

        test("a draft with no home, or with both, fails locally with nothing queued") {
            runTest {
                val rig = Rig()
                rig.repo
                    .createEntity(DRAFT.copy(homeBookId = null))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
                rig.db.pendingOperationV2Dao().countDispatchable() shouldBe 0
                rig.db.close()
            }
        }

        test("history is read online and mapped to domain changes") {
            runTest {
                val service = mock<EntityService>()
                everySuspend { service.listHistory(EntityId("e1")) } returns
                    AppResult.Success(
                        listOf(
                            EntityChange(
                                id = StoryWorldHistoryId("h1"),
                                entityId = EntityId("e1"),
                                op = StoryWorldOp.CREATE,
                                actorId = "u1",
                                occurredAt = 7,
                            ),
                        ),
                    )
                val rig = Rig(service)
                val history =
                    rig.repo
                        .listHistory(EntityId("e1"))
                        .shouldBeInstanceOf<AppResult.Success<List<WorldEntityChange>>>()
                        .data
                history.single().op shouldBe WorldEntityChangeOp.CREATE
                history.single().actorId?.value shouldBe "u1"
                rig.db.close()
            }
        }
    })

/** A real in-memory database and outbox; [sent] records every op the drain hands the sender. */
private class Rig(
    service: EntityService = mock(),
    channel: RpcChannel<EntityService> = RpcChannel.forTest(service),
    sender: PendingOperationSender = PendingOperationSender { AppResult.Success(Unit) },
) {
    val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val authSession = FakeAuthSession(userId = "u1")
    val sent = mutableListOf<PendingOperation>()
    private val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = { op -> sender.send(op).also { sent += op } },
        )
    val repo =
        EntityEditRepositoryImpl(
            entityDao = db.entityDao(),
            offlineEditor =
                OfflineEditor(
                    pendingQueue = queue,
                    transactionRunner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R = block()
                        },
                    authSession = authSession,
                ),
            channel = channel,
            authSession = authSession,
        )

    /** Drains until the queue is idle: per-entity FIFO sends one op per entity per wave. */
    suspend fun drain() {
        repeat(DRAIN_WAVES) { queue.drain() }
    }
}

private const val DRAIN_WAVES = 4

private fun payload(
    id: EntityId,
    name: String,
    revision: Long,
) = EntitySyncPayload(
    id = id.value,
    kind = EntityKind.CHARACTER,
    name = name,
    homeBookId = "b1",
    createdBy = "u1",
    updatedBy = "u1",
    revision = revision,
    createdAt = 1,
    updatedAt = 1,
)

private fun change(
    id: String,
    entityId: EntityId,
    op: StoryWorldOp,
    before: EntitySyncPayload?,
    after: EntitySyncPayload?,
    actorId: String = "u1",
) = EntityChange(StoryWorldHistoryId(id), entityId, op, actorId = actorId, occurredAt = 1, before = before, after = after)
