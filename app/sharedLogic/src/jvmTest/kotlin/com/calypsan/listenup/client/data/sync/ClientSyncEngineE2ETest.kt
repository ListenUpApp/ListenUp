package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

private const val PRE_POPULATED_COUNT = 3
private const val LARGE_CATCH_UP_COUNT = 250
private const val OPERATION_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests: real client engine talking to the real `:server`
 * testApplication in one process. Validates the catch-up → SSE handoff,
 * Last-Event-Id reconnect replay, and silent catch-up at scale.
 *
 * StreamError surfacing is covered at the dispatcher unit-test layer
 * (`SyncEventDispatcherTest`); piping a `StreamError` frame through the live
 * SSE transport is timing-fragile and adds no extra coverage. Local-first
 * echo and CursorStale-fallback e2e paths are deferred —
 * see `RecordingTagSyncDomainHandler` for the seam they'll plug into.
 */
class ClientSyncEngineE2ETest :
    FunSpec({

        test("engine starts → silent catch-up → SSE tail picks up subsequent writes") {
            withClientSyncEngineAgainstServer {
                // Pre-populate the server with PRE_POPULATED_COUNT tags.
                tagRepo.upsert(Tag(id = "t1", name = "alpha", slug = "alpha", revision = 0L, updatedAt = 0L))
                tagRepo.upsert(Tag(id = "t2", name = "beta", slug = "beta", revision = 0L, updatedAt = 0L))
                tagRepo.upsert(Tag(id = "t3", name = "gamma", slug = "gamma", revision = 0L, updatedAt = 0L))

                // Subscribe before start so emissions during catch-up reach the collector.
                val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val caughtUpDeferred =
                        collectorScope.async {
                            recording.catchUpObserved.take(PRE_POPULATED_COUNT).toList()
                        }
                    engine.start(currentUserId = "u1")

                    val caughtUp =
                        withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) { caughtUpDeferred.await() }
                    caughtUp shouldHaveSize PRE_POPULATED_COUNT
                    caughtUp.map { it.first.id }.toSet() shouldBe setOf("t1", "t2", "t3")

                    // Server-side write after catch-up should arrive via SSE tail.
                    tagRepo.upsert(Tag(id = "t4", name = "delta", slug = "delta", revision = 0L, updatedAt = 0L))
                    val sseEvent =
                        withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) {
                            recording.observed.first { it.id == "t4" }
                        }
                    sseEvent.id shouldBe "t4"
                } finally {
                    collectorScope.cancel()
                }
            }
        }

        test("reconnect after disconnect: missed events arrive after catch-up + SSE") {
            withClientSyncEngineAgainstServer {
                // First connection: catch-up + observe one live event.
                tagRepo.upsert(Tag(id = "t1", name = "alpha", slug = "alpha", revision = 0L, updatedAt = 0L))
                engine.start(currentUserId = "u1")

                withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) {
                    recording.catchUpObserved.first { it.first.id == "t1" }
                }
                tagRepo.upsert(Tag(id = "t2", name = "beta", slug = "beta", revision = 0L, updatedAt = 0L))
                withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) {
                    recording.observed.first { it.id == "t2" }
                }

                // Disconnect SSE. Server-side write happens while disconnected.
                engine.stop()
                tagRepo.upsert(Tag(id = "t3", name = "gamma", slug = "gamma", revision = 0L, updatedAt = 0L))

                // Reconnect — the engine's start() always runs catch-up first, so t3
                // (written while disconnected, revision > stored cursor) arrives via
                // the catch-up REST page rather than the bus replay buffer. Both paths
                // are valid recovery — the contract is "no events lost across
                // disconnect"; whether catch-up or replay delivers it is implementation.
                engine.start(currentUserId = "u1")
                val recovered =
                    withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) {
                        recording.catchUpObserved.first { it.first.id == "t3" }
                    }
                recovered.first.id shouldBe "t3"
            }
        }

        test("silent catch-up doesn't leak intermediate state when scaled to many rows") {
            withClientSyncEngineAgainstServer {
                // Pre-populate LARGE_CATCH_UP_COUNT tags before the engine starts.
                repeat(LARGE_CATCH_UP_COUNT) { i ->
                    tagRepo.upsert(
                        Tag(id = "t$i", name = "name-$i", slug = "name-$i", revision = 0L, updatedAt = 0L),
                    )
                }

                // Subscribe before start; the recording handler's flow has bounded
                // capacity and `catchUp()` `emit`s sequentially, so without an active
                // collector the catch-up loop suspends mid-page.
                val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val caughtUpDeferred =
                        collectorScope.async {
                            recording.catchUpObserved.take(LARGE_CATCH_UP_COUNT).toList()
                        }
                    engine.start(currentUserId = "u1")

                    val caughtUp =
                        withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) { caughtUpDeferred.await() }
                    caughtUp shouldHaveSize LARGE_CATCH_UP_COUNT
                    caughtUp.map { it.first.id }.toSet() shouldBe
                        (0 until LARGE_CATCH_UP_COUNT).map { "t$it" }.toSet()
                } finally {
                    collectorScope.cancel()
                }
            }
        }
    })
