package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.model.WorldEventAnchor
import com.calypsan.listenup.client.domain.model.WorldEventContent
import com.calypsan.listenup.client.domain.model.WorldEventDraft
import com.calypsan.listenup.client.domain.model.WorldEventEdit
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private val SAGA = SeriesId("s1")

private val JOINS_MARS =
    WorldEventDraft(
        content =
            WorldEventContent(
                type = WorldEventType.JOINS,
                detail = "Primus",
                anchor = WorldEventAnchor(BookId("b1"), 60_000L),
                subjectId = EntityId("darrow"),
                objectId = EntityId("mars"),
            ),
        homeSeriesId = SAGA,
    )

private fun note(text: String) = WorldEventDraft(WorldEventContent(type = WorldEventType.NOTE, text = text), homeSeriesId = SAGA)

private fun cast(
    id: String,
    kind: EntityKind,
    homeSeriesId: String? = SAGA.value,
    homeBookId: String? = null,
) = EntityEntity(id = id, kind = kind, name = id, homeSeriesId = homeSeriesId, homeBookId = homeBookId, createdAt = 0, updatedAt = 0)

/**
 * Offline-first contract: record, edit and delete write Room and queue one `world_events` batch with no server
 * present; the shared rules refuse offline exactly what the server would; undo is PR A's shape.
 */
class WorldEventEditRepositoryOfflineTest :
    FunSpec({
        test("recording writes Room with its mentions and queues a batch of one keyed by the new id") {
            runTest {
                val rig = newRig()
                val edit =
                    rig.repo
                        .recordEvent(JOINS_MARS)
                        .shouldBeInstanceOf<AppResult.Success<WorldEventEdit>>()
                        .data

                val saved =
                    rig.repo
                        .observeEvent(edit.eventId)
                        .first()
                        .shouldNotBeNull()
                saved.content shouldBe JOINS_MARS.content
                saved.mentionIds shouldBe setOf(EntityId("darrow"), EntityId("mars"))
                val op =
                    rig.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "world_events"
                op.entityId shouldBe edit.eventId.value
                op.opType shouldBe "upsert"
                val batch = contractJson.decodeFromString(EventsBatch.serializer(), op.payload)
                batch.ops
                    .single()
                    .shouldBeInstanceOf<WorldEventOp.Upsert>()
                    .upsert.detail shouldBe "Primus"
                rig.db.close()
            }
        }

        test("recording several events queues ONE batch keyed by the first id") {
            runTest {
                val rig = newRig()
                val edits =
                    rig.repo
                        .recordEvents(listOf(note("one"), note("two")))
                        .shouldBeInstanceOf<AppResult.Success<List<WorldEventEdit>>>()
                        .data

                val op =
                    rig.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.entityId shouldBe edits.first().eventId.value
                contractJson.decodeFromString(EventsBatch.serializer(), op.payload).ops.size shouldBe 2
                rig.repo
                    .observeEventsForSeries(SAGA)
                    .first()
                    .size shouldBe 2
                rig.db.close()
            }
        }

        test("an edit returns the earlier state, and undoing it offline restores it through the outbox") {
            runTest {
                val rig = newRig()
                val created = (rig.repo.recordEvent(note("first")) as AppResult.Success).data
                val edited =
                    rig.repo
                        .updateEvent(created.eventId, WorldEventContent(type = WorldEventType.NOTE, text = "second"))
                        .shouldBeInstanceOf<AppResult.Success<WorldEventEdit>>()
                        .data
                edited.before
                    .shouldNotBeNull()
                    .content.text shouldBe "first"

                rig.repo.undo(edited) shouldBe AppResult.Success(Unit)

                rig.repo
                    .observeEvent(created.eventId)
                    .first()
                    .shouldNotBeNull()
                    .content.text shouldBe "first"
                rig.db.close()
            }
        }

        test("a delete undone before it is sent is withdrawn: the row returns and nothing reaches the server") {
            runTest {
                val rig = newRig()
                val created = (rig.repo.recordEvent(note("kept")) as AppResult.Success).data
                rig.drain()
                val deletion = (rig.repo.deleteEvent(created.eventId) as AppResult.Success).data
                rig.repo
                    .observeEvent(created.eventId)
                    .first()
                    .shouldBeNull()

                rig.repo.undo(deletion) shouldBe AppResult.Success(Unit)

                rig.repo
                    .observeEvent(created.eventId)
                    .first()
                    .shouldNotBeNull()
                    .content.text shouldBe "kept"
                rig.db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .shouldBeEmpty()
                rig.sent.size shouldBe 1
                rig.db.close()
            }
        }

        test("the server's rules refuse offline: wrong kind, another world, missing parts, UNKNOWN, two homes") {
            runTest {
                val rig = newRig()
                val wrongKind = JOINS_MARS.copy(content = JOINS_MARS.content.copy(objectId = EntityId("darrow")))
                rig.repo
                    .recordEvent(
                        wrongKind,
                    ).shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<WorldEventError.WrongEntityKind>()
                val otherWorld = JOINS_MARS.copy(content = JOINS_MARS.content.copy(subjectId = EntityId("stranger")))
                rig.repo
                    .recordEvent(
                        otherWorld,
                    ).shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<WorldEventError.EntityNotInWorld>()
                rig.repo
                    .recordEvent(note("  "))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
                rig.repo
                    .recordEvent(WorldEventDraft(WorldEventContent(WorldEventType.UNKNOWN, text = "x"), homeSeriesId = SAGA))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
                rig.repo
                    .recordEvent(note("x").copy(homeBookId = BookId("b1")))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
                rig.db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .shouldBeEmpty()
                rig.db.close()
            }
        }

        test("undo of a sent delete reverts the user's own DELETE online and writes the revived row") {
            runTest {
                val service = mock<WorldEventService>()
                val rig = newRig(service)
                val created = (rig.repo.recordEvent(note("revived")) as AppResult.Success).data
                val deletion = (rig.repo.deleteEvent(created.eventId) as AppResult.Success).data
                rig.drain()
                val alive =
                    WorldEventSyncPayload(
                        id = created.eventId.value,
                        homeSeriesId = SAGA.value,
                        type = WorldEventType.NOTE,
                        text = "revived",
                        revision = 40,
                        updatedAt = 2,
                        createdAt = 1,
                    )
                val serverDelete =
                    WorldEventChange(
                        id = StoryWorldHistoryId("h-del"),
                        eventId = created.eventId,
                        op = StoryWorldOp.DELETE,
                        actorId = "u1",
                        occurredAt = 2,
                        before = alive,
                        after = alive.copy(deletedAt = 2),
                    )
                everySuspend { service.listHistory(any()) } returns AppResult.Success(listOf(serverDelete))
                everySuspend { service.revert(any()) } returns
                    AppResult.Success(
                        serverDelete.copy(id = StoryWorldHistoryId("h-rev"), op = StoryWorldOp.REVERT, after = alive.copy(revision = 41)),
                    )

                rig.repo.undo(deletion) shouldBe AppResult.Success(Unit)

                rig.repo
                    .observeEvent(created.eventId)
                    .first()
                    .shouldNotBeNull()
                    .content.text shouldBe "revived"
                rig.db.close()
            }
        }
    })

private class EventRig(
    service: WorldEventService = mock(),
    channel: RpcChannel<WorldEventService> = RpcChannel.forTest(service),
    sender: PendingOperationSender = PendingOperationSender { AppResult.Success(Unit) },
) {
    val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val authSession = FakeAuthSession(userId = "u1")
    val sent = mutableListOf<com.calypsan.listenup.client.data.sync.PendingOperation>()
    private val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = { op -> sender.send(op).also { sent += op } },
        )
    val repo =
        WorldEventEditRepositoryImpl(
            worldEventDao = db.worldEventDao(),
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

/** A [EventRig] whose entity mirror holds the cast: darrow and mars in the saga, a stranger in another world. */
private suspend fun newRig(service: WorldEventService = mock()): EventRig =
    EventRig(service = service).also { rig ->
        rig.db.entityDao().upsert(cast("darrow", EntityKind.CHARACTER))
        rig.db.entityDao().upsert(cast("mars", EntityKind.GROUP))
        rig.db.entityDao().upsert(cast("stranger", EntityKind.CHARACTER, homeSeriesId = null, homeBookId = "solo"))
    }
