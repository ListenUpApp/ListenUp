@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Impl-level coverage for [SyncStreamServiceImpl] — the RPC firehose — against the service
 * directly: a real [ChangeBus] fed by real SQLDelight repositories, no transport in the loop.
 * Covers delivery ordering, resume, cursor-stale (pre-check AND the attach-time re-check),
 * heartbeat cadence, access gating, control routing, and the null-principal refusal. The
 * `bookAccessPolicy` thunk deliberately throws — the domains driven here (`tags`,
 * `library_folders`) must never resolve it.
 */
class SyncStreamServiceTest :
    FunSpec({

        test("hello: the first frame is a CONTROL Heartbeat") {
            runTest {
                val first = streamService(ChangeBus()).observeEvents(sinceRevision = null).first()
                val frame = first.shouldBeInstanceOf<RpcEvent.Data<SyncFrame>>().value
                frame.domain shouldBe SyncFrame.CONTROL
                frame.revision.shouldBeNull()
                decodeControl(frame.json) shouldBe SyncControl.Heartbeat
            }
        }

        test("resume: events at or below sinceRevision are skipped, newer ones replay in order") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val tags = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                    tags.upsert(Tag("a", "alpha", "alpha", 0, 0)) // revision 1
                    tags.upsert(Tag("b", "beta", "beta", 0, 0)) // revision 2
                    tags.upsert(Tag("c", "gamma", "gamma", 0, 0)) // revision 3

                    val frames =
                        streamService(bus)
                            .observeEvents(sinceRevision = 1L)
                            .domainFrames()
                            .take(2)
                            .toList()

                    frames[0].domain shouldBe "tags"
                    frames[0].revision shouldBe 2L
                    frames[0].json shouldContain """"name":"beta""""
                    frames[1].revision shouldBe 3L
                    frames[1].json shouldContain """"name":"gamma""""
                }
            }
        }

        test("behind-floor sinceRevision yields CursorStale and completes the flow") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val tags = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                    // Overflow the 256-event replay buffer so DROP_OLDEST evicts revision 1.
                    repeat(300) { i -> tags.upsert(Tag("tag-$i", "n$i", "n$i", 0, 0)) }

                    // toList() proves completion: a live stream would never end.
                    val events = streamService(bus).observeEvents(sinceRevision = 1L).toList()

                    events.size shouldBe 1
                    val frame = events[0].shouldBeInstanceOf<RpcEvent.Data<SyncFrame>>().value
                    frame.domain shouldBe SyncFrame.CONTROL
                    val control = decodeControl(frame.json).shouldBeInstanceOf<SyncControl.CursorStale>()
                    control.lastKnownRevision shouldBe bus.oldestRetainedRevision()!!
                }
            }
        }

        test("access gating: library_folders events are withheld from a member, delivered to an admin") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val folders = LibraryFolderRepository(db = sql, bus = bus, registry = registry, driver = driver)
                    val tags = TagRepository(db = sql, bus = bus, registry = registry)
                    folders.upsert(folderFixture("secret-folder")) // revision 1 — admin-only
                    tags.upsert(Tag("a", "alpha", "alpha", 0, 0)) // revision 2 — visible sentinel

                    // The member's first domain frame skips straight to the tags sentinel: the
                    // hidden folder event never reaches the stream.
                    val memberFrame =
                        streamService(bus, memberPrincipal("member-1"))
                            .observeEvents(sinceRevision = null)
                            .domainFrames()
                            .first()
                    memberFrame.domain shouldBe "tags"
                    memberFrame.revision shouldBe 2L

                    // The admin sees both, in publish order.
                    val adminFrames =
                        streamService(bus, rootPrincipal())
                            .observeEvents(sinceRevision = null)
                            .domainFrames()
                            .take(2)
                            .toList()
                    adminFrames[0].domain shouldBe "library_folders"
                    adminFrames[0].revision shouldBe 1L
                    adminFrames[0].json shouldContain "secret-folder"
                    adminFrames[1].domain shouldBe "tags"
                }
            }
        }

        test("heartbeat: a CONTROL Heartbeat frame arrives after the interval (virtual time)") {
            runTest {
                val service = streamService(ChangeBus(), heartbeatIntervalMillis = 25_000L)
                val frames = mutableListOf<SyncFrame>()
                val job =
                    launch {
                        service.observeEvents(sinceRevision = null).collect {
                            frames += it.shouldBeInstanceOf<RpcEvent.Data<SyncFrame>>().value
                        }
                    }
                runCurrent()
                frames.size shouldBe 1 // the hello only

                advanceTimeBy(25_001)
                runCurrent()
                frames.size shouldBe 2
                frames[1].domain shouldBe SyncFrame.CONTROL
                decodeControl(frames[1].json) shouldBe SyncControl.Heartbeat
                job.cancel()
            }
        }

        test("live events after replay are delivered with domain and revision") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val tags = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                    tags.upsert(Tag("a", "alpha", "alpha", 0, 0)) // revision 1 — replayed

                    val collected =
                        async {
                            streamService(bus)
                                .observeEvents(sinceRevision = null)
                                .domainFrames()
                                .take(2)
                                .toList()
                        }
                    runCurrent() // subscription attached; replay of revision 1 consumed
                    tags.upsert(Tag("b", "beta", "beta", 0, 0)) // revision 2 — live tail

                    val frames = collected.await()
                    frames[0].domain shouldBe "tags"
                    frames[0].revision shouldBe 1L
                    frames[1].domain shouldBe "tags"
                    frames[1].revision shouldBe 2L
                    frames[1].json shouldContain """"name":"beta""""
                }
            }
        }

        test("a missing principal is refused with a typed PermissionDenied error") {
            runTest {
                val events =
                    SyncStreamServiceImpl(
                        bus = ChangeBus(),
                        bookAccessPolicy = { error("must not resolve the policy without a caller") },
                        principal = PrincipalProvider.None,
                    ).observeEvents(sinceRevision = null).toList()

                events.size shouldBe 1
                val error = events[0].shouldBeInstanceOf<RpcEvent.Error>().error
                error.shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }

        test("a floor that advances between pre-check and bus attach yields CursorStale, not a silent gap") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val tags = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                    tags.upsert(Tag("t1", "n1", "n1", 0, 0)) // revision 1 — the resume cursor
                    tags.upsert(Tag("t2", "n2", "n2", 0, 0)) // revision 2 — keeps the pre-check fresh

                    // The eviction race, staged deterministically: the hello frame is emitted AFTER
                    // the pre-check passed and BEFORE the merged tail's ChangeBus subscription
                    // attaches. `emit` suspends into this collector, so a burst published while
                    // handling the hello lands exactly in that pre-check → attach gap — DROP_OLDEST
                    // evicts past sinceRevision=1 with no live signal. The `onSubscription` re-check
                    // in the data tail is what must catch it (the port of SERVER-SYNC-02's SSE test).
                    val frames = mutableListOf<SyncFrame>()
                    streamService(bus).observeEvents(sinceRevision = 1L).collect { event ->
                        val frame = event.shouldBeInstanceOf<RpcEvent.Data<SyncFrame>>().value
                        if (frames.isEmpty()) {
                            repeat(300) { i -> tags.upsert(Tag("burst-$i", "b$i", "b$i", 0, 0)) }
                        }
                        frames += frame
                    }

                    // collect returning proves the stream terminated — CursorStale is terminal,
                    // never a silent subscription to a gapped tail.
                    frames.size shouldBe 2
                    decodeControl(frames[0].json) shouldBe SyncControl.Heartbeat
                    val stale = decodeControl(frames[1].json).shouldBeInstanceOf<SyncControl.CursorStale>()
                    stale.lastKnownRevision shouldBe bus.oldestRetainedRevision()!!
                }
            }
        }

        test("control frames route to the addressed user; broadcasts reach everyone") {
            runTest {
                val bus = ChangeBus()
                val collected =
                    async {
                        streamService(bus, memberPrincipal("user-a"))
                            .observeEvents(sinceRevision = null)
                            .map { it.shouldBeInstanceOf<RpcEvent.Data<SyncFrame>>().value }
                            .filter { it.domain == SyncFrame.CONTROL }
                            .map { decodeControl(it.json) }
                            .first { it != SyncControl.Heartbeat }
                    }
                runCurrent() // control subscription attached (no replay on the control channel)

                // Addressed to another user — must NOT reach user-a; the broadcast must.
                bus.publishControl(SyncControl.PreferencesChanged, userId = "user-b")
                bus.broadcastControl(SyncControl.ServerInfoChanged)

                collected.await() shouldBe SyncControl.ServerInfoChanged
            }
        }
    })

/** Builds the service under test over [bus]; the policy thunk throws — no driven domain is book-gated. */
private fun streamService(
    bus: ChangeBus,
    principal: PrincipalProvider = rootPrincipal(),
    heartbeatIntervalMillis: Long = 25_000L,
): SyncStreamServiceImpl =
    SyncStreamServiceImpl(
        bus = bus,
        bookAccessPolicy = { error("BookAccessPolicy must not be resolved for ungated domains") },
        principal = principal,
        heartbeatIntervalMillis = heartbeatIntervalMillis,
    )

/** The non-control (domain data) frames of the stream, unwrapped from their [RpcEvent.Data] envelope. */
private fun Flow<RpcEvent<SyncFrame>>.domainFrames(): Flow<SyncFrame> =
    map { (it as RpcEvent.Data<SyncFrame>).value }
        .filter { it.domain != SyncFrame.CONTROL }

private fun decodeControl(json: String): SyncControl = contractJson.decodeFromString(SyncControl.serializer(), json)

private fun folderFixture(id: String): LibraryFolderSyncPayload =
    LibraryFolderSyncPayload(
        id = id,
        libraryId = "test-library",
        rootPath = "/srv/audiobooks/$id",
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
