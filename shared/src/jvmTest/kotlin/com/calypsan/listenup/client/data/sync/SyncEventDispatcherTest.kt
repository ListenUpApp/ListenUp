package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SyncEventDispatcherTest :
    FunSpec({

        class RecordingHandler : SyncDomainHandler<Tag> {
            override val domainName = "tags"
            override val payloadSerializer = Tag.serializer()
            val seen = mutableListOf<Pair<SyncEvent<Tag>, Boolean>>()

            override suspend fun onEvent(
                event: SyncEvent<Tag>,
                isOwnEcho: Boolean,
            ): AppResult<Unit> {
                seen += event to isOwnEcho
                return AppResult.Success(Unit)
            }

            override suspend fun onCatchUpItem(
                item: Tag,
                isTombstone: Boolean,
            ) = AppResult.Success(Unit)
        }

        test("data event for known domain dispatches with isOwnEcho = false when no matching pending op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val registry = ClientSyncDomainRegistry()
                val handler = RecordingHandler()
                registry.register(handler)
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                var cursorAdvanced: Pair<String, Long>? = null
                val cursorAdvance: suspend (String, Long) -> Unit = { d, r -> cursorAdvanced = d to r }
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue = queue,
                        state = SyncEngineState(),
                        cursorAdvance = cursorAdvance,
                    )

                val revision = 5L
                val occurredAt = 100L
                val event =
                    SyncEvent.Created(
                        id = "t1",
                        revision = revision,
                        occurredAt = occurredAt,
                        clientOpId = null,
                        payload = Tag("t1", "alpha", revision, occurredAt),
                    )
                val frame =
                    ParsedSseFrame(
                        id = revision,
                        event = "tags",
                        data = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                dispatcher.handle(frame)

                handler.seen shouldHaveSize 1
                handler.seen[0].second shouldBe false
                cursorAdvanced shouldBe ("tags" to revision)
                db.close()
            }
        }

        test("data event with clientOpId matching a pending op dispatches with isOwnEcho = true and acks") {
            runTest {
                val db = createInMemoryTestDatabase()
                val registry = ClientSyncDomainRegistry()
                val handler = RecordingHandler()
                registry.register(handler)
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val opId = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue = queue,
                        state = SyncEngineState(),
                        cursorAdvance = { _, _ -> },
                    )

                val revision = 6L
                val occurredAt = 200L
                val event =
                    SyncEvent.Updated(
                        id = "t1",
                        revision = revision,
                        occurredAt = occurredAt,
                        clientOpId = opId,
                        payload = Tag("t1", "alpha", revision, occurredAt),
                    )
                val frame =
                    ParsedSseFrame(
                        id = revision,
                        event = "tags",
                        data = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                dispatcher.handle(frame)

                handler.seen shouldHaveSize 1
                handler.seen[0].second shouldBe true
                db.pendingOperationV2Dao().get(opId) shouldBe null
                db.close()
            }
        }

        test("event for unknown domain logs warning, doesn't throw, doesn't advance cursor") {
            runTest {
                val db = createInMemoryTestDatabase()
                val registry = ClientSyncDomainRegistry()
                val state = SyncEngineState()
                var advanced = false
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue =
                            PendingOperationQueue(
                                dao = db.pendingOperationV2Dao(),
                                sender = PendingOperationSender { AppResult.Success(Unit) },
                            ),
                        state = state,
                        cursorAdvance = { _, _ -> advanced = true },
                    )
                val frame = ParsedSseFrame(id = 1L, event = "books", data = "{}")
                dispatcher.handle(frame) // no throw
                advanced shouldBe false
                db.close()
            }
        }

        test("control: SyncControl.CursorStale sets state and triggers callback") {
            runTest {
                val db = createInMemoryTestDatabase()
                var staleSeen = false
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
                        queue =
                            PendingOperationQueue(
                                dao = db.pendingOperationV2Dao(),
                                sender = PendingOperationSender { AppResult.Success(Unit) },
                            ),
                        state = SyncEngineState(),
                        cursorAdvance = { _, _ -> },
                        onCursorStale = { staleSeen = true },
                    )
                val lastKnownRevision = 1_000L
                val control = SyncControl.CursorStale(lastKnownRevision = lastKnownRevision)
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), control),
                    )
                dispatcher.handle(frame)
                staleSeen shouldBe true
                db.close()
            }
        }

        test("control: SyncControl.StreamError records error in state") {
            runTest {
                val db = createInMemoryTestDatabase()
                val state = SyncEngineState()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
                        queue =
                            PendingOperationQueue(
                                dao = db.pendingOperationV2Dao(),
                                sender = PendingOperationSender { AppResult.Success(Unit) },
                            ),
                        state = state,
                        cursorAdvance = { _, _ -> },
                    )
                val control = SyncControl.StreamError(error = SyncError.RealtimeDisconnected())
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), control),
                    )
                dispatcher.handle(frame)
                state.value.recentErrorCount shouldBe 1
                db.close()
            }
        }
    })
