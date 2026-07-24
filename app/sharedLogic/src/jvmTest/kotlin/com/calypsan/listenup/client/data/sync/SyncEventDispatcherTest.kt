package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
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
            hasBackstop: Boolean = true,
        ) : SyncDomainHandler<Tag> {
            override val domainName = "tags"
            override val payloadSerializer = Tag.serializer()
            override val hasDigestBackstop = hasBackstop

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
                    SyncFrame(
                        revision = revision,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
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
                val frame = SyncFrame(revision = 1L, domain = "books", json = "{}")
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), control),
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.ServerInfoChanged),
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.PreferencesChanged),
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json =
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.ActivityChanged),
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.LibraryDataChanged),
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), control),
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
                    SyncFrame(
                        revision = null,
                        domain = "control",
                        json = contractJson.encodeToString(SyncControl.serializer(), control),
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
                    SyncFrame(
                        revision = revision,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
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
                    SyncFrame(
                        revision = revision,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                dispatcher.handle(frame)

                state.value.recentErrorCount shouldBe 1
            }
        }

        test("control: malformed frame reports contract-mismatch evidence, doesn't throw, doesn't advance cursor") {
            runTest {
                var advanced = false
                val compatReports = mutableListOf<String>()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = ClientSyncDomainRegistry(),
                        state = SyncEngineState(),
                        cursorAdvance = { _, _ -> advanced = true },
                        reportCompat = { compatReports += it },
                    )
                val frame =
                    SyncFrame(
                        revision = 1L,
                        domain = "control",
                        json = "{not valid json for SyncControl}",
                    )
                dispatcher.handle(frame) // no throw
                compatReports shouldHaveSize 1
                advanced shouldBe false
            }
        }

        test("data event: malformed frame reports contract-mismatch evidence, doesn't throw, doesn't advance cursor") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                registry.register(RecordingHandler())
                var advanced = false
                val compatReports = mutableListOf<String>()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { _, _ -> advanced = true },
                        reportCompat = { compatReports += it },
                    )
                val frame =
                    SyncFrame(
                        revision = 1L,
                        domain = "tags",
                        json = "{not valid json for a Tag SyncEvent}",
                    )
                dispatcher.handle(frame) // no throw
                compatReports shouldHaveSize 1
                advanced shouldBe false
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
                    SyncFrame(
                        revision = 5L,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), failedEvent),
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
                    SyncFrame(
                        revision = 6L,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), succeededEvent),
                    )
                dispatcher.handle(succeededFrame)

                cursorAdvanced shouldBe ("tags" to 6L)
            }
        }

        test("OptOut domain: after a failed apply, a later book's event does NOT advance the cursor past the hole") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                // rev4 applies → cursor 4; rev5 fails → freeze at 4; rev6 (another book) succeeds
                // but must NOT advance the cursor to 6 — only the cursor can redeliver rev5, and
                // advancing past it would strand it forever (no digest backstop).
                val handler =
                    ScriptedHandler(
                        ArrayDeque(
                            listOf(
                                AppResult.Success(Unit),
                                AppResult.Failure(SyncError.SyncFailed()),
                                AppResult.Success(Unit),
                            ),
                        ),
                        hasBackstop = false,
                    )
                registry.register(handler)
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r -> cursorAdvanced = d to r },
                    )

                fun frameAt(rev: Long): SyncFrame {
                    val event =
                        SyncEvent.Created(
                            id = "b$rev",
                            revision = rev,
                            occurredAt = 100L,
                            clientOpId = null,
                            payload = Tag("b$rev", "n", "n", rev, 100L),
                        )
                    return SyncFrame(
                        revision = rev,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                }

                dispatcher.handle(frameAt(4L)) // applies, cursor → 4
                dispatcher.handle(frameAt(5L)) // fails, freezes at 4
                dispatcher.handle(frameAt(6L)) // succeeds but frozen — must not advance to 6

                // Held at the last safe revision (4), NOT stepped to 6.
                cursorAdvanced shouldBe ("tags" to 4L)
            }
        }

        test("OptOut domain: an undecodable payload freezes the cursor exactly like a failed apply") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                // rev5's frame is undecodable — that's functionally "apply did not happen" for the
                // hole it leaves; rev6 (another book) applies successfully but must NOT advance the
                // cursor past the hole, or rev5's revision can never be redelivered.
                val handler =
                    ScriptedHandler(
                        ArrayDeque(listOf(AppResult.Success(Unit))),
                        hasBackstop = false,
                    )
                registry.register(handler)
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r -> cursorAdvanced = d to r },
                    )

                val undecodableFrame =
                    SyncFrame(revision = 5L, domain = "tags", json = "{not valid json for a Tag SyncEvent}")
                dispatcher.handle(undecodableFrame) // undecodable — freezes at last-known-good

                val succeededEvent =
                    SyncEvent.Created(
                        id = "b6",
                        revision = 6L,
                        occurredAt = 100L,
                        clientOpId = null,
                        payload = Tag("b6", "n", "n", 6L, 100L),
                    )
                val succeededFrame =
                    SyncFrame(
                        revision = 6L,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), succeededEvent),
                    )
                dispatcher.handle(succeededFrame) // applies but frozen — must not advance to 6

                cursorAdvanced shouldBe null
            }
        }

        test("digest-backed domain: an undecodable payload does not freeze — a later event still advances") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                val handler =
                    ScriptedHandler(
                        ArrayDeque(listOf(AppResult.Success(Unit))),
                        hasBackstop = true,
                    )
                registry.register(handler)
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r -> cursorAdvanced = d to r },
                    )

                val undecodableFrame =
                    SyncFrame(revision = 5L, domain = "tags", json = "{not valid json for a Tag SyncEvent}")
                dispatcher.handle(undecodableFrame) // undecodable — but digest backstop, no freeze

                val succeededEvent =
                    SyncEvent.Created(
                        id = "b6",
                        revision = 6L,
                        occurredAt = 100L,
                        clientOpId = null,
                        payload = Tag("b6", "n", "n", 6L, 100L),
                    )
                val succeededFrame =
                    SyncFrame(
                        revision = 6L,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), succeededEvent),
                    )
                dispatcher.handle(succeededFrame)

                cursorAdvanced shouldBe ("tags" to 6L)
            }
        }

        test("OptOut domain: the freeze lifts once catch-up advances the cursor past the hole, and re-arms on a new failure") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                // Frames 4,5,6,7,8,9 → results below. 4 applies; 5 fails (freeze@4); 6 succeeds but
                // frozen; then catch-up advances the persisted cursor to 6; 7 succeeds and, seeing the
                // cursor now above the watermark, LIFTS the freeze and advances; 8 fails (re-freeze@7);
                // 9 succeeds but frozen again → not advanced.
                val handler =
                    ScriptedHandler(
                        ArrayDeque(
                            listOf(
                                AppResult.Success(Unit),
                                AppResult.Failure(SyncError.SyncFailed()),
                                AppResult.Success(Unit),
                                AppResult.Success(Unit),
                                AppResult.Failure(SyncError.SyncFailed()),
                                AppResult.Success(Unit),
                            ),
                        ),
                        hasBackstop = false,
                    )
                registry.register(handler)
                var persistedCursor: Long? = null
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r ->
                            persistedCursor = r
                            cursorAdvanced = d to r
                        },
                        cursorOf = { persistedCursor },
                    )

                fun frameAt(rev: Long): SyncFrame {
                    val event =
                        SyncEvent.Created(
                            id = "b$rev",
                            revision = rev,
                            occurredAt = 100L,
                            clientOpId = null,
                            payload = Tag("b$rev", "n", "n", rev, 100L),
                        )
                    return SyncFrame(
                        revision = rev,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                }

                dispatcher.handle(frameAt(4L)) // applies → cursor 4
                dispatcher.handle(frameAt(5L)) // fails → freeze@4
                dispatcher.handle(frameAt(6L)) // frozen — not advanced
                cursorAdvanced shouldBe ("tags" to 4L)

                persistedCursor = 6L // catch-up re-pulled from 4 and healed the hole
                dispatcher.handle(frameAt(7L)) // freeze lifts → advances to 7
                cursorAdvanced shouldBe ("tags" to 7L)

                dispatcher.handle(frameAt(8L)) // fails → re-freeze@7
                dispatcher.handle(frameAt(9L)) // frozen again — not advanced past 7
                cursorAdvanced shouldBe ("tags" to 7L)
            }
        }

        test("digest-backed domain: a failed apply does not freeze — a later event still advances (unchanged)") {
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
                        hasBackstop = true,
                    )
                registry.register(handler)
                var cursorAdvanced: Pair<String, Long>? = null
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        state = SyncEngineState(),
                        cursorAdvance = { d, r -> cursorAdvanced = d to r },
                    )

                fun frameAt(rev: Long): SyncFrame {
                    val event =
                        SyncEvent.Created(
                            id = "b$rev",
                            revision = rev,
                            occurredAt = 100L,
                            clientOpId = null,
                            payload = Tag("b$rev", "n", "n", rev, 100L),
                        )
                    return SyncFrame(
                        revision = rev,
                        domain = "tags",
                        json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event),
                    )
                }

                dispatcher.handle(frameAt(5L)) // fails — but digest backstop, no freeze
                dispatcher.handle(frameAt(6L)) // succeeds, advances

                cursorAdvanced shouldBe ("tags" to 6L)
            }
        }
    })
