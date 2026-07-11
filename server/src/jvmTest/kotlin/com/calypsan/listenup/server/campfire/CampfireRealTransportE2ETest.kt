@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.campfire

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.CampfireServiceImpl
import com.calypsan.listenup.server.api.RecordingPushNotifier
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.routes.registerScoped
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.scheduler.CampfireReaperTask
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.roleOf
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testAuth
import com.calypsan.listenup.server.testing.withSqlDatabase
import app.cash.turbine.test
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService

private const val USER_A = "user-a"
private const val USER_B = "user-b"
private const val BOOK_ID = "book-1"
private const val ALL_BOOKS_ID = "all-books"

private val everyoneSettings = CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)

/**
 * Task 6 of the co-listening (Campfire) implementation plan: the server soak/E2E over a REAL
 * transport — an actual `embeddedServer(CIO)` on a real socket, and real kotlinx.rpc WebSocket
 * clients (CIO engine + `installKrpc()`), never `testApplication`.
 *
 * **Why real transport.** This repo has a documented "black-hole WebSocket" lesson (see
 * `docs/beta-bug-bash-2026-06-30.md` and MEMORY `contributor_merge_fixes`): `testApplication`'s
 * in-memory WebSocket bridge never surfaced a dead/half-open socket that produced an infinite
 * client-side spinner in production. kotlinx.rpc is also pinned to a dev-channel build
 * (0.11.0-grpc-189) — long-lived bidirectional streaming flow cancellation (Campfire's
 * `observeSession`) has to be proven against the actual pinned version over a real socket, not
 * the test-host's simulated transport.
 *
 * **Harness precedent followed:**
 * - [com.calypsan.listenup.server.foundation.FoundationSmokeTest] (`server/src/commonTest`) is
 *   this repo's canonical "real embeddedServer(CIO) + real `HttpClient(CIO) { install(WebSockets);
 *   installKrpc() }` + `.rpc("ws://…")`" recipe — the exact shape [buildCampfireEnv] and
 *   [campfireClient] below mirror, extended from a single unguarded ping to the full guarded,
 *   principal-scoped [CampfireService] mount ([registerScoped] + [guard], the same call shape as
 *   production `RpcRoutes.jvm.kt`).
 * - `RpcReconnectE2ETest` (`:sharedLogic` jvmTest) is this repo's precedent for simulating true
 *   socket death (as opposed to a graceful `leaveSession()` call or a client-driven flow cancel):
 *   a small duplex TCP relay whose `cut()` severs the live socket without a WebSocket close
 *   handshake. [TcpRelay] below is a minimal, file-local duplicate (that class is `private` to a
 *   different Gradle module, so it isn't importable) used only in the socket-death scenario.
 * - `CampfireServiceImplTest` (`server/src/jvmTest`) is the precedent for wiring
 *   [CampfireServiceImpl] over a real migrated SQLite database and for granting book access via
 *   the ALL_BOOKS pure-union collection (`grantAllBooksAccess` below mirrors its
 *   `makeBookAccessible` helper). [com.calypsan.listenup.server.testing.testAuth]
 *   (`server/src/jvmTest/testing/TestAuthProvider.kt`) is reused verbatim for auth: its bearer
 *   token IS the user id, so two real WebSocket clients bearing `Authorization: Bearer user-a` /
 *   `user-b` genuinely authenticate as two distinct principals over the real upgrade handshake —
 *   a real auth wall, not a stub that ignores the token.
 *
 * **jvmTest ONLY.** `:server` also has a `linuxX64Test` target, but real-socket, real-clock,
 * multi-client soak infrastructure like this file is JVM-only test tooling (matching every other
 * `*E2ETest`/`*RpcTest` in this package) — it never needs to prove anything about the native
 * *production* binary's own transport (that's `FoundationSmokeTest`'s narrow "compiles and serves
 * on linuxX64" job). Running a TCP relay, `Runtime.getRuntime()` heap sampling, and a 5,000-frame
 * timing-sensitive soak against K/N's test runner would be disproportionate infrastructure for no
 * additional coverage.
 *
 * **STOP conditions this file exists to catch** (see the plan's STOP conditions section):
 * 1. Flow cancellation misbehaving under the pinned kotlinx.rpc (frames delivered after cancel,
 *    a leaked server coroutine, or a hung cancel) — see the cancellation-semantics test.
 * 2. Socket death not detected within the away-grace window (the black-hole problem resurfacing
 *    at the RPC layer) — see the socket-death test.
 *
 * Uses `runBlocking`, not Kotest's `runTest`: every scenario does real socket I/O and real-clock
 * waits (the away grace and reaper interval are genuinely shortened constructor parameters, not a
 * fake clock) — a virtual-time dispatcher would auto-advance past those waits and prove nothing.
 */
class CampfireRealTransportE2ETest :
    FunSpec({

        test("commands and chat propagate to B as real frames within 1s (scenarios 1-3)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser(USER_A)
                sql.seedTestUser(USER_B)
                sql.seedTestBook(BOOK_ID)
                runBlocking {
                    grantAllBooksAccess(sql, driver, USER_A)
                    grantAllBooksAccess(sql, driver, USER_B)
                    val env = buildCampfireEnv(sql, driver)
                    try {
                        withCampfireClients(env) { serviceA, serviceB ->
                            val sessionId = serviceA.createSession(BOOK_ID, everyoneSettings).value().id
                            serviceB.joinSession(sessionId).value()

                            serviceB.observeSession(sessionId).test(timeout = 15.seconds) {
                                // Warm-up: the room's SharedFlow has no replay, so a command fired before
                                // B's subscribe frame has actually landed server-side is silently missed.
                                // Retry a throwaway (always-emitting) SeekTo until one is observed.
                                var warmupPos = 1L
                                var warmedUp = false
                                while (!warmedUp) {
                                    serviceA
                                        .sendCommand(
                                            sessionId,
                                            PlaybackCommand.SeekTo(positionMs = warmupPos, commandId = "warmup-$warmupPos"),
                                        ).value()
                                    warmedUp = withTimeoutOrNull(500.milliseconds) { awaitItem() } != null
                                    warmupPos += 1_000
                                }

                                // The room starts paused (isPlaying = false at creation) — establish a
                                // playing baseline first so the Pause below is a real transition, not a NoOp.
                                serviceA.sendCommand(sessionId, PlaybackCommand.Play(commandId = "cmd-play")).value()
                                withTimeout(1.seconds) { awaitItem() }

                                // Scenario 1: Pause -> AnchorChanged within 1s.
                                serviceA.sendCommand(sessionId, PlaybackCommand.Pause(commandId = "cmd-pause")).value()
                                val pauseFrame =
                                    withTimeout(1.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.AnchorChanged>()
                                pauseFrame.anchor.isPlaying shouldBe false

                                // Scenario 2: SeekTo -> AnchorChanged with the re-anchored position, within 1s.
                                serviceA
                                    .sendCommand(
                                        sessionId,
                                        PlaybackCommand.SeekTo(positionMs = 42_000L, commandId = "cmd-seek"),
                                    ).value()
                                val seekFrame =
                                    withTimeout(1.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.AnchorChanged>()
                                seekFrame.anchor.positionMs shouldBe 42_000L

                                // Scenario 3: chat -> Chat frame, content matches, within 1s.
                                serviceA.sendChat(sessionId, "hello from A").value()
                                val chatFrame =
                                    withTimeout(1.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.Chat>()
                                chatFrame.message.text shouldBe "hello from A"
                                chatFrame.message.senderId shouldBe USER_A

                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        env.close()
                    }
                }
            }
        }

        test("B's socket death is detected via MemberAway then MemberLeft after the grace window (scenario 4)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser(USER_A)
                sql.seedTestUser(USER_B)
                sql.seedTestBook(BOOK_ID)
                runBlocking {
                    grantAllBooksAccess(sql, driver, USER_A)
                    grantAllBooksAccess(sql, driver, USER_B)
                    // Real short durations over a real transport (never a fake clock): a 2s away grace
                    // and a 500ms reaper sweep keep this test fast without pretending time passed.
                    val env = buildCampfireEnv(sql, driver, awayGrace = 2.seconds, reaperInterval = 500.milliseconds)
                    val relay = TcpRelay.start("127.0.0.1", env.port)
                    try {
                        val clientA = campfireClient(USER_A)
                        // B connects THROUGH the relay so its TCP connection can be severed
                        // independently of the server's listening socket -- genuine socket death,
                        // never a graceful leaveSession() call.
                        val clientB = campfireClient(USER_B)
                        try {
                            val serviceA = clientA.connectService(env.wsUrl)
                            val serviceB = clientB.connectService("ws://127.0.0.1:${relay.port}/api/rpc/authed")

                            val sessionId = serviceA.createSession(BOOK_ID, everyoneSettings).value().id
                            serviceB.joinSession(sessionId).value()

                            serviceA.observeSession(sessionId).test(timeout = 20.seconds) {
                                // A subscribes fresh here (after B already joined), so replay=0 means A
                                // never sees B's MemberJoined frame -- only what happens from here on.
                                val bCollected = AtomicInteger(0)
                                val bJob =
                                    launch(Dispatchers.Default) {
                                        runCatching { serviceB.observeSession(sessionId).collect { bCollected.incrementAndGet() } }
                                    }
                                // Settle window for B's subscribe frame to travel through the relay and
                                // register server-side before we sever the connection. A is ALSO
                                // subscribed to this room (it's observing itself), so every settle
                                // command below also enqueues a frame on A's OWN Turbine collector --
                                // drain it immediately each iteration so it never gets mistaken for the
                                // MemberAway/MemberLeft frames asserted below.
                                var settlePos = 1L
                                while (bCollected.get() == 0) {
                                    serviceA
                                        .sendCommand(
                                            sessionId,
                                            PlaybackCommand.SeekTo(positionMs = settlePos, commandId = "settle-$settlePos"),
                                        ).value()
                                    withTimeout(2.seconds) { awaitItem() } // A's own copy of the settle frame
                                    settlePos += 1
                                    delay(50)
                                }

                                relay.cut()

                                val awayFrame =
                                    withTimeout(10.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.MemberAway>()
                                awayFrame.member.userId shouldBe USER_B

                                val leftFrame =
                                    withTimeout(10.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.MemberLeft>()
                                leftFrame.member.userId shouldBe USER_B

                                bJob.cancel()
                                cancelAndIgnoreRemainingEvents()
                            }
                        } finally {
                            clientA.close()
                            clientB.close()
                        }
                    } finally {
                        relay.shutdown()
                        env.close()
                    }
                }
            }
        }

        test("soak: 5,000 mixed command/chat frames sustain without leak or flow death (scenario 5)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser(USER_A)
                sql.seedTestUser(USER_B)
                sql.seedTestBook(BOOK_ID)
                runBlocking {
                    grantAllBooksAccess(sql, driver, USER_A)
                    grantAllBooksAccess(sql, driver, USER_B)
                    val env = buildCampfireEnv(sql, driver)
                    try {
                        withCampfireClients(env) { serviceA, serviceB ->
                            val sessionId = serviceA.createSession(BOOK_ID, everyoneSettings).value().id
                            serviceB.joinSession(sessionId).value()

                            val received = AtomicInteger(0)
                            val bJob =
                                launch(Dispatchers.Default) {
                                    serviceB.observeSession(sessionId).collect { event ->
                                        if (event is RpcEvent.Data) received.incrementAndGet()
                                    }
                                }
                            eventually(5.seconds) {
                                serviceA
                                    .sendCommand(sessionId, PlaybackCommand.SeekTo(positionMs = 0, commandId = "warmup"))
                                    .value()
                                received.get() shouldBeGreaterThan 0
                            }
                            val baseline = received.get()

                            // Loose canary: heap before the soak, after a forced GC.
                            val runtime = Runtime.getRuntime()
                            System.gc()
                            val before = runtime.totalMemory() - runtime.freeMemory()

                            val frameCount = 5_000
                            // Cycle play/pause/seek/chat: play<->pause always alternates (never a NoOp
                            // that would silently skip a broadcast), seek and chat always emit -- every
                            // iteration below is guaranteed to produce exactly one frame.
                            repeat(frameCount) { i ->
                                when (i % 4) {
                                    0 -> serviceA.sendCommand(sessionId, PlaybackCommand.Play(commandId = "soak-$i"))
                                    1 -> serviceA.sendCommand(sessionId, PlaybackCommand.Pause(commandId = "soak-$i"))
                                    2 ->
                                        serviceA.sendCommand(
                                            sessionId,
                                            PlaybackCommand.SeekTo(positionMs = i.toLong(), commandId = "soak-$i"),
                                        )
                                    else -> serviceA.sendChat(sessionId, "soak-$i")
                                }.value()
                            }

                            eventually(30.seconds) { received.get() shouldBe (baseline + frameCount) }

                            // Flows still alive: B's collector job hasn't died, and a fresh command after
                            // the soak still arrives.
                            bJob.isActive shouldBe true
                            serviceA
                                .sendCommand(sessionId, PlaybackCommand.SeekTo(positionMs = 999_999, commandId = "post-soak"))
                                .value()
                            eventually(5.seconds) { received.get() shouldBe (baseline + frameCount + 1) }

                            System.gc()
                            val after = runtime.totalMemory() - runtime.freeMemory()
                            val growthMb = (after - before) / (1024 * 1024)
                            // Loose leak canary, deliberately generous: 5,000 frames over one WS session
                            // should not grow the heap materially. 100MB only catches a gross leak (e.g.
                            // an unbounded per-frame allocation that never gets collected), not a tight
                            // budget -- see the plan's Task 6 KDoc on keeping this bound loose.
                            growthMb shouldBeLessThan 100L

                            bJob.cancel()
                        }
                    } finally {
                        env.close()
                    }
                }
            }
        }

        test("B's collection cancellation doesn't kill the room; rejoin observes fresh frames (scenario 6)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser(USER_A)
                sql.seedTestUser(USER_B)
                sql.seedTestBook(BOOK_ID)
                runBlocking {
                    grantAllBooksAccess(sql, driver, USER_A)
                    grantAllBooksAccess(sql, driver, USER_B)
                    val env = buildCampfireEnv(sql, driver)
                    try {
                        withCampfireClients(env) { serviceA, serviceB ->
                            val sessionId = serviceA.createSession(BOOK_ID, everyoneSettings).value().id
                            serviceB.joinSession(sessionId).value()

                            val bReceivedFirst = CompletableDeferred<Unit>()
                            val bJob =
                                launch(Dispatchers.Default) {
                                    serviceB.observeSession(sessionId).collect { event ->
                                        if (event is RpcEvent.Data && !bReceivedFirst.isCompleted) bReceivedFirst.complete(Unit)
                                    }
                                }
                            eventually(5.seconds) {
                                serviceA
                                    .sendCommand(sessionId, PlaybackCommand.SeekTo(positionMs = 1, commandId = "warmup"))
                                    .value()
                                bReceivedFirst.isCompleted shouldBe true
                            }

                            // Client-driven cancel (NOT socket death) mid-stream.
                            bJob.cancel()
                            bJob.join()

                            // Server-side room continues: A (as its own observer) still receives frames.
                            serviceA.observeSession(sessionId).test(timeout = 10.seconds) {
                                var warmedUp = false
                                var pos = 2L
                                while (!warmedUp) {
                                    serviceA
                                        .sendCommand(
                                            sessionId,
                                            PlaybackCommand.SeekTo(positionMs = pos, commandId = "a-warmup-$pos"),
                                        ).value()
                                    warmedUp = withTimeoutOrNull(500.milliseconds) { awaitItem() } != null
                                    pos += 1
                                }
                                serviceA
                                    .sendCommand(
                                        sessionId,
                                        PlaybackCommand.SeekTo(positionMs = 12_345L, commandId = "after-b-cancel"),
                                    ).value()
                                val frame =
                                    withTimeout(1.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.AnchorChanged>()
                                frame.anchor.positionMs shouldBe 12_345L
                                cancelAndIgnoreRemainingEvents()
                            }

                            // B rejoins observeSession as a FRESH collection and receives frames going
                            // forward -- proving per-collector lifecycle under the pinned kotlinx.rpc (no
                            // stale replay, no dead second subscription after the first was cancelled).
                            serviceB.observeSession(sessionId).test(timeout = 10.seconds) {
                                var warmedUp = false
                                var pos = 100L
                                while (!warmedUp) {
                                    serviceA
                                        .sendCommand(
                                            sessionId,
                                            PlaybackCommand.SeekTo(positionMs = pos, commandId = "b-rejoin-warmup-$pos"),
                                        ).value()
                                    warmedUp = withTimeoutOrNull(500.milliseconds) { awaitItem() } != null
                                    pos += 1
                                }
                                serviceA
                                    .sendCommand(
                                        sessionId,
                                        PlaybackCommand.SeekTo(positionMs = 54_321L, commandId = "b-rejoin-real"),
                                    ).value()
                                val frame =
                                    withTimeout(1.seconds) { awaitItem() }
                                        .dataOrFail()
                                        .shouldBeInstanceOf<CampfireFrame.AnchorChanged>()
                                frame.anchor.positionMs shouldBe 54_321L
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        env.close()
                    }
                }
            }
        }
    })

private fun <T> AppResult<T>.value(): T {
    this.shouldBeInstanceOf<AppResult.Success<T>>()
    return data
}

/** Unwraps a streamed [RpcEvent], failing loudly on [RpcEvent.Error] or an unexpected [RpcEvent.Complete]. */
private fun RpcEvent<CampfireFrame>.dataOrFail(): CampfireFrame =
    when (this) {
        is RpcEvent.Data -> value
        is RpcEvent.Error -> error("frame delivered as RpcEvent.Error: $error")
        RpcEvent.Complete -> error("flow completed with RpcEvent.Complete unexpectedly")
    }

/**
 * Grants [viewer] access to [bookId] via the pure-union ALL_BOOKS system collection — the same
 * mechanism `CampfireServiceImplTest.makeBookAccessible` uses. Safe to call once per viewer against
 * the same book: the underlying collection/collection-book rows are idempotently upserted.
 */
private suspend fun grantAllBooksAccess(
    sql: ListenUpDatabase,
    driver: SqlDriver,
    viewer: String,
    bookId: String = BOOK_ID,
) {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    collectionRepo.upsert(
        CollectionSyncPayload(
            id = ALL_BOOKS_ID,
            libraryId = "test-library",
            ownerId = "system",
            name = "All Books",
            isInbox = false,
            revision = 0L,
            updatedAt = 0L,
        ),
    )
    collectionBookRepo.upsert(
        CollectionBookSyncPayload(collectionId = ALL_BOOKS_ID, bookId = bookId, createdAt = 0L, revision = 0L),
    )
    grantRepo.upsert(
        CollectionShareSyncPayload(
            id = "grant-$viewer-$bookId",
            collectionId = ALL_BOOKS_ID,
            sharedWithUserId = viewer,
            sharedByUserId = "system",
            permission = SharePermission.Read,
            revision = 0L,
            updatedAt = 0L,
        ),
    )
}

/** The live resources for one real-transport scenario: a real embedded server + its Campfire wiring. */
private class CampfireEnv(
    private val server: EmbeddedServer<*, *>,
    val port: Int,
    private val reaperScope: CoroutineScope,
) {
    val wsUrl: String get() = "ws://127.0.0.1:$port/api/rpc/authed"

    fun close() {
        reaperScope.cancel()
        @Suppress("MagicNumber")
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    }
}

/**
 * Boots a real `embeddedServer(CIO)` on an OS-chosen port exposing ONLY the [CampfireService] RPC
 * mount, wired exactly like production (`registerScoped` + `guard`, the same call shape as
 * `RpcRoutes.jvm.kt`'s `registerScoped<CampfireService> { guard((services.campfireService as
 * CampfireServiceImpl).copyWith(it)) }`) over a real, migrated SQLite database. [awayGrace] and
 * [reaperInterval] are real (non-fake-clock) constructor parameters, shortened here so the
 * away/reap scenarios finish in seconds instead of minutes.
 */
private suspend fun buildCampfireEnv(
    sql: ListenUpDatabase,
    driver: SqlDriver,
    awayGrace: Duration = 2.seconds,
    reaperInterval: Duration = 500.milliseconds,
): CampfireEnv {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val registry = CampfireRegistry(clock = Clock.System, awayGrace = awayGrace)
    val activityRecorder =
        ActivityRecorder(syncRepo = ActivitySyncRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver))
    val baseService =
        CampfireServiceImpl(
            registry = registry,
            bookAccessPolicy = BookAccessPolicy(sql, driver),
            playbackPositions = PlaybackPositionRepository(db = sql, bus = bus, registry = syncRegistry),
            publicProfiles = PublicProfileRepository(db = sql, bus = bus, registry = syncRegistry),
            db = sql,
            bus = bus,
            userRoleLookup = UserRoleLookup(db = sql),
            inviteNotifier = CampfireInviteNotifier(pushNotifier = RecordingPushNotifier()),
            activityRecorder = activityRecorder,
        )
    val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    CampfireReaperTask(
        registry = registry,
        bus = bus,
        activityRecorder = activityRecorder,
        interval = reaperInterval,
    ).start(reaperScope)

    val module: Application.() -> Unit = {
        install(ServerKrpc)
        install(Authentication) { testAuth(roleResolver = { sql.roleOf(it) }) }
        routing {
            authenticate(JWT_PROVIDER) {
                serverRpc("/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                    registerScoped<CampfireService> { guard(baseService.copyWith(it)) }
                }
            }
        }
    }
    val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1", module = module)
    server.start(wait = false)
    val port = server.engine.resolvedConnectors().first().port
    return CampfireEnv(server, port, reaperScope)
}

/** A real kotlinx.rpc WebSocket client (CIO engine) authenticating as [userId] via [testAuth]'s bearer-is-userid convention. */
private fun campfireClient(userId: String): HttpClient =
    HttpClient(ClientCIO) {
        install(ClientWebSockets)
        installKrpc()
        defaultRequest { bearerAuth(userId) }
    }

/** Opens a [CampfireService] RPC proxy over [wsUrl] on this real client. */
private suspend fun HttpClient.connectService(wsUrl: String): CampfireService =
    this
        .rpc(wsUrl) { rpcConfig { serialization { krpcJson(contractJson) } } }
        .withService<CampfireService>()

/** Builds real clients for user A and B against [env], running [block], then closing both clients. */
private suspend fun withCampfireClients(
    env: CampfireEnv,
    block: suspend (serviceA: CampfireService, serviceB: CampfireService) -> Unit,
) {
    val clientA = campfireClient(USER_A)
    val clientB = campfireClient(USER_B)
    try {
        block(clientA.connectService(env.wsUrl), clientB.connectService(env.wsUrl))
    } finally {
        clientA.close()
        clientB.close()
    }
}

/**
 * A minimal duplex TCP relay — forwards bytes between one accepted client connection and
 * [targetPort]. [cut] closes both sides of the live connection abruptly (no WebSocket close
 * handshake reaches the server), reproducing genuine socket death rather than a graceful
 * disconnect. This is the same technique `RpcReconnectE2ETest` (`:sharedLogic` jvmTest) uses to
 * simulate a dead connection; duplicated here in miniature (only [cut]/[shutdown], no reconnect
 * probing) because that class is `private` to a different Gradle module and isn't importable.
 */
private class TcpRelay private constructor(
    private val listener: ServerSocket,
    private val targetHost: String,
    private val targetPort: Int,
) {
    val port: Int get() = listener.localPort
    private val liveSockets = CopyOnWriteArrayList<Socket>()

    @Volatile private var accepting = true

    init {
        thread(isDaemon = true, name = "campfire-relay-accept") {
            while (accepting) {
                val downstream = runCatching { listener.accept() }.getOrNull() ?: break
                val upstream = runCatching { Socket(targetHost, targetPort) }.getOrNull()
                if (upstream == null) {
                    runCatching { downstream.close() }
                    continue
                }
                liveSockets += downstream
                liveSockets += upstream
                pump(downstream, upstream)
                pump(upstream, downstream)
            }
        }
    }

    private fun pump(
        from: Socket,
        to: Socket,
    ) {
        thread(isDaemon = true, name = "campfire-relay-pump") {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
        }
    }

    /** Severs the live connection abruptly — socket death, not a graceful close. */
    fun cut() {
        val snapshot = liveSockets.toList()
        liveSockets.clear()
        snapshot.forEach { runCatching { it.close() } }
    }

    /** Full teardown: stop accepting and close everything. */
    fun shutdown() {
        accepting = false
        cut()
        runCatching { listener.close() }
    }

    companion object {
        fun start(
            targetHost: String,
            targetPort: Int,
        ): TcpRelay = TcpRelay(ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), targetHost, targetPort)
    }
}
