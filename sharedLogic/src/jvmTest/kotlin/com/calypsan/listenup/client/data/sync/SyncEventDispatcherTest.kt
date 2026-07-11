package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.preferencesDomain
import com.calypsan.listenup.client.data.sync.domains.presenceDomain
import com.calypsan.listenup.client.data.sync.domains.serverInfoDomain
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SyncEventDispatcherTest :
    FunSpec({

        class RecordingHandler : SyncDomainHandler<Tag> {
            override val domainName = "tags"
            override val payloadSerializer = Tag.serializer()

            override fun syncId(item: Tag): String = item.id

            val seen = mutableListOf<SyncEvent<Tag>>()

            override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> {
                seen += event
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

            override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> = results.removeFirst()

            override suspend fun onCatchUpItem(
                item: Tag,
                isTombstone: Boolean,
            ) = AppResult.Success(Unit)

            override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
        }

        test("data event for a known domain is routed to its handler and advances the cursor") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                val handler = RecordingHandler()
                registry.register(handler)
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
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

                handler.seen shouldHaveSize 1
                handler.seen[0].id shouldBe "t1"
                cursorAdvanced shouldBe ("tags" to revision)
            }
        }

        test("event for unknown domain logs warning, doesn't throw, doesn't advance cursor") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                var advanced = false
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { _, _ -> advanced = true },
                    )
                val frame = ParsedSseFrame(id = 1L, event = "books", data = "{}")
                dispatcher.handle(frame) // no throw
                advanced shouldBe false
            }
        }

        test("control: SyncControl.CursorStale triggers the recovery callback") {
            runTest {
                var recoveryTriggered = false
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("control: ServerInfoChanged runs the refreshed-domain refetch strategy") {
            runTest {
                var refetched = false
                val router =
                    RefreshedDomainRouter(
                        listOf(serverInfoDomain(refetch = { refetched = true })),
                    )
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("control: PreferencesChanged runs the refreshed-domain refetch strategy") {
            runTest {
                var refetched = false
                val router =
                    RefreshedDomainRouter(
                        listOf(preferencesDomain(refetch = { refetched = true })),
                    )
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("control: ActiveSessionsChanged runs the refreshed-domain ping strategy") {
            runTest {
                var pinged = false
                val router =
                    RefreshedDomainRouter(
                        listOf(presenceDomain(ping = { pinged = true })),
                    )
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("control: ActivityChanged is unclaimed and handled generically (activities now sync as a data domain)") {
            runTest {
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
            }
        }

        test("control: SyncControl.LibraryDataChanged invokes onLibraryDataChanged") {
            runTest {
                var reconciled = false
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("control: SyncControl.UserDeleted invokes onUserDeleted with the reason") {
            runTest {
                var deletedReason: String? = "UNSET"
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("control: SyncControl.StreamError records error in state") {
            runTest {
                val state = SyncEngineState()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
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
            }
        }

        test("data event whose apply fails does not advance the cursor") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                val handler = ScriptedHandler(ArrayDeque(listOf(AppResult.Failure(SyncError.SyncFailed()))))
                registry.register(handler)
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
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
            }
        }

        test("data event whose apply fails records the error in SyncEngineState") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                val handler = ScriptedHandler(ArrayDeque(listOf(AppResult.Failure(SyncError.SyncFailed()))))
                registry.register(handler)
                val state = SyncEngineState()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
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
            }
        }

        test("a failed apply does not stall the stream — a later successful event still advances the cursor") {
            runTest {
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
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
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
            }
        }
    })
