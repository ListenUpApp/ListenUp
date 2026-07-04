package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.preferencesDomain
import com.calypsan.listenup.client.data.sync.domains.presenceDomain
import com.calypsan.listenup.client.data.sync.domains.serverInfoDomain
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer

// "tags" is not a real outbox channel — a minimal local fixture for a hypothetical
// un-mirrored domain, matching the queue's payload-agnostic contract.
private val tagsChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert))

class SyncEventDispatcherTest :
    FunSpec({

        class RecordingHandler : SyncDomainHandler<Tag> {
            override val domainName = "tags"
            override val payloadSerializer = Tag.serializer()

            override fun syncId(item: Tag): String = item.id

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

            override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
        }

        class ScriptedHandler(
            private val results: ArrayDeque<AppResult<Unit>>,
        ) : SyncDomainHandler<Tag> {
            override val domainName = "tags"
            override val payloadSerializer = Tag.serializer()

            override fun syncId(item: Tag): String = item.id

            override suspend fun onEvent(
                event: SyncEvent<Tag>,
                isOwnEcho: Boolean,
            ): AppResult<Unit> = results.removeFirst()

            override suspend fun onCatchUpItem(
                item: Tag,
                isTombstone: Boolean,
            ) = AppResult.Success(Unit)

            override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
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
                        payload = Tag("t1", "alpha", "alpha", revision, occurredAt),
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
                val opId = queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")
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
                        payload = Tag("t1", "alpha", "alpha", revision, occurredAt),
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

        test("control: SyncControl.CursorStale triggers the recovery callback") {
            runTest {
                val db = createInMemoryTestDatabase()
                var recoveryTriggered = false
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
                        onCursorStale = { recoveryTriggered = true },
                    )
                val control = SyncControl.CursorStale(lastKnownRevision = 1_000L)
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), control),
                    )
                dispatcher.handle(frame)
                recoveryTriggered shouldBe true
                db.close()
            }
        }

        test("control: ServerInfoChanged runs the refreshed-domain refetch strategy") {
            runTest {
                val db = createInMemoryTestDatabase()
                var refetched = false
                val router =
                    RefreshedDomainRouter(
                        listOf(serverInfoDomain(refetch = { refetched = true })),
                    )
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
                        refreshedRouter = router,
                    )
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), SyncControl.ServerInfoChanged),
                    )
                dispatcher.handle(frame)
                refetched shouldBe true
                db.close()
            }
        }

        test("control: PreferencesChanged runs the refreshed-domain refetch strategy") {
            runTest {
                val db = createInMemoryTestDatabase()
                var refetched = false
                val router =
                    RefreshedDomainRouter(
                        listOf(preferencesDomain(refetch = { refetched = true })),
                    )
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
                        refreshedRouter = router,
                    )
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), SyncControl.PreferencesChanged),
                    )
                dispatcher.handle(frame)
                refetched shouldBe true
                db.close()
            }
        }

        test("control: ActiveSessionsChanged runs the refreshed-domain ping strategy") {
            runTest {
                val db = createInMemoryTestDatabase()
                var pinged = false
                val router =
                    RefreshedDomainRouter(
                        listOf(presenceDomain(ping = { pinged = true })),
                    )
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
                        refreshedRouter = router,
                    )
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data =
                            contractJson.encodeToString(
                                SyncControl.serializer(),
                                SyncControl.ActiveSessionsChanged,
                            ),
                    )
                dispatcher.handle(frame)
                pinged shouldBe true
                db.close()
            }
        }

        test("control: ActivityChanged is unclaimed and handled generically (activities now sync as a data domain)") {
            runTest {
                val db = createInMemoryTestDatabase()
                // A router with a DIFFERENT refresh entry: activities are no longer a refresh trigger, so an
                // ActivityChanged frame must NOT trigger any refresh strategy and must not crash.
                var otherPinged = false
                val router =
                    RefreshedDomainRouter(
                        listOf(presenceDomain(ping = { otherPinged = true })),
                    )
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
                        refreshedRouter = router,
                    )
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), SyncControl.ActivityChanged),
                    )
                // Completes without throwing; no unrelated refresh fires.
                dispatcher.handle(frame)
                otherPinged shouldBe false
                db.close()
            }
        }

        test("control: SyncControl.LibraryDataChanged invokes onLibraryDataChanged") {
            runTest {
                val db = createInMemoryTestDatabase()
                var reconciled = false
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
                        onLibraryDataChanged = { reconciled = true },
                    )
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), SyncControl.LibraryDataChanged),
                    )
                dispatcher.handle(frame)
                reconciled shouldBe true
                db.close()
            }
        }

        test("control: SyncControl.UserDeleted invokes onUserDeleted with the reason") {
            runTest {
                val db = createInMemoryTestDatabase()
                var deletedReason: String? = "UNSET"
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
                        onUserDeleted = { reason -> deletedReason = reason },
                    )
                val control = SyncControl.UserDeleted(reason = "removed by admin")
                val frame =
                    ParsedSseFrame(
                        id = null,
                        event = "control",
                        data = contractJson.encodeToString(SyncControl.serializer(), control),
                    )
                dispatcher.handle(frame)
                deletedReason shouldBe "removed by admin"
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

        test("data event whose apply fails does not advance the cursor") {
            runTest {
                val db = createInMemoryTestDatabase()
                val registry = ClientSyncDomainRegistry()
                val handler = ScriptedHandler(ArrayDeque(listOf(AppResult.Failure(SyncError.SyncFailed()))))
                registry.register(handler)
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue = queue,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r -> cursorAdvanced = d to r },
                    )

                val revision = 5L
                val occurredAt = 100L
                val event =
                    SyncEvent.Created(
                        id = "t1",
                        revision = revision,
                        occurredAt = occurredAt,
                        clientOpId = null,
                        payload = Tag("t1", "alpha", "alpha", revision, occurredAt),
                    )
                val frame =
                    ParsedSseFrame(
                        id = revision,
                        event = "tags",
                        data = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                dispatcher.handle(frame)

                cursorAdvanced shouldBe null
                db.close()
            }
        }

        test("data event whose apply fails records the error in SyncEngineState") {
            runTest {
                val db = createInMemoryTestDatabase()
                val registry = ClientSyncDomainRegistry()
                val handler = ScriptedHandler(ArrayDeque(listOf(AppResult.Failure(SyncError.SyncFailed()))))
                registry.register(handler)
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue = queue,
                        state = state,
                        cursorAdvance = { _, _ -> },
                    )

                val revision = 5L
                val occurredAt = 100L
                val event =
                    SyncEvent.Created(
                        id = "t1",
                        revision = revision,
                        occurredAt = occurredAt,
                        clientOpId = null,
                        payload = Tag("t1", "alpha", "alpha", revision, occurredAt),
                    )
                val frame =
                    ParsedSseFrame(
                        id = revision,
                        event = "tags",
                        data = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                dispatcher.handle(frame)

                state.value.recentErrorCount shouldBe 1
                db.close()
            }
        }

        test("a failed apply does not stall the stream — a later successful event still advances the cursor") {
            runTest {
                val db = createInMemoryTestDatabase()
                val registry = ClientSyncDomainRegistry()
                val handler =
                    ScriptedHandler(
                        ArrayDeque(
                            listOf(
                                AppResult.Failure(SyncError.SyncFailed()),
                                AppResult.Success(Unit),
                            ),
                        ),
                    )
                registry.register(handler)
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue = queue,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r -> cursorAdvanced = d to r },
                    )

                val occurredAt = 100L
                val failedEvent =
                    SyncEvent.Created(
                        id = "t1",
                        revision = 5L,
                        occurredAt = occurredAt,
                        clientOpId = null,
                        payload = Tag("t1", "alpha", "alpha", 5L, occurredAt),
                    )
                val failedFrame =
                    ParsedSseFrame(
                        id = 5L,
                        event = "tags",
                        data = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), failedEvent),
                    )
                dispatcher.handle(failedFrame)

                val succeededEvent =
                    SyncEvent.Updated(
                        id = "t1",
                        revision = 6L,
                        occurredAt = occurredAt,
                        clientOpId = null,
                        payload = Tag("t1", "alpha", "alpha", 6L, occurredAt),
                    )
                val succeededFrame =
                    ParsedSseFrame(
                        id = 6L,
                        event = "tags",
                        data = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), succeededEvent),
                    )
                dispatcher.handle(succeededFrame)

                cursorAdvanced shouldBe ("tags" to 6L)
                db.close()
            }
        }
    })
