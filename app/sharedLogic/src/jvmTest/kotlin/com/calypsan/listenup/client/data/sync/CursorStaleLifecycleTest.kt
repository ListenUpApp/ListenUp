package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

private const val SEEDED_COUNT = 300
private const val OPERATION_TIMEOUT_SECONDS = 30L

/**
 * Tier 3 e2e for the `CursorStale` lifecycle (review H1 + M1).
 *
 * Verifies that `SyncEngine.handleCursorStale` orchestrates the recovery
 * sequence end-to-end against a real `:server`:
 *
 *   1. disconnect the firehose — prevents catch-up from interleaving with live tail
 *   2. catch-up across every registered domain
 *   3. reseed the stream client's `lastEventId` from the cursor store
 *   4. reconnect the firehose
 *
 * Pre-fix: `lastEventId` was set only at start + per frame, so a CursorStale
 * cycle advanced the cursor store but not the stream client; reconnect looped
 * with the same stale cursor forever.
 *
 * Post-fix: after `handleCursorStale`, `syncStreamClient.currentLastEventId()`
 * reflects the highest cursor and reconnect succeeds without re-tripping
 * the stale check.
 */
class CursorStaleLifecycleTest :
    FunSpec({

        test("handleCursorStale: disconnect → catchUp → reseed lastEventId → reconnect") {
            withClientSyncEngineAgainstServer {
                // Pre-populate the server with SEEDED_COUNT tags. With a 256-entry
                // replay buffer, anything earlier than (SEEDED_COUNT - 256) is evicted —
                // realistically reproducing the production stale-cursor condition.
                repeat(SEEDED_COUNT) { i ->
                    tagRepo.upsert(Tag(id = "t$i", name = "name-$i", slug = "name-$i", revision = 0L, updatedAt = 0L))
                }

                val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    // Subscribe before start so emissions during catch-up reach the
                    // collector (the recording handler's flow has bounded replay).
                    val firstCatchUpDeferred =
                        collectorScope.async {
                            recording.catchUpObserved.take(SEEDED_COUNT).toList()
                        }

                    engine.start(currentUserId = "u1")

                    val firstCatchUp =
                        withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) { firstCatchUpDeferred.await() }
                    firstCatchUp shouldHaveSize SEEDED_COUNT

                    // The firehose connects asynchronously after catch-up; wait for the state
                    // to reach Connected rather than asserting on the immediate value.
                    withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) {
                        state.observe().filter { it.connection is ConnectionState.Connected }.first()
                    }
                    state.value.connection.shouldBeInstanceOf<ConnectionState.Connected>()
                    val cursorAfterInitial = syncStreamClient.currentLastEventId()
                    cursorAfterInitial.shouldNotBeNull()
                    cursorAfterInitial shouldBeGreaterThan 0L

                    // Simulate the production stale-cursor condition: the stream client
                    // is holding a lastEventId that the server's bus has already
                    // evicted past. We reseed it to 0 to make the next stream attach
                    // a `SyncControl.CursorStale` if the lifecycle were to run again.
                    // Then drive handleCursorStale directly — the dispatcher path on
                    // a real CursorStale frame.
                    syncStreamClient.reseed(0L)

                    engine.handleCursorStale()

                    // Lifecycle invariants post-handling:
                    //  - the firehose is reconnected, not in permanent Disconnected.
                    //  - The stream client's lastEventId was reseeded from the cursor store
                    //    (which still holds the post-initial-catch-up high-water mark),
                    //    not left at the stale 0L we forced moments ago. This is the
                    //    H1 fix: without `syncStreamClient.reseed(newCursor)` inside
                    //    handleCursorStale, the reconnect would loop forever with 0L.
                    withTimeout(OPERATION_TIMEOUT_SECONDS.seconds) {
                        state.observe().filter { it.connection is ConnectionState.Connected }.first()
                    }
                    state.value.connection.shouldBeInstanceOf<ConnectionState.Connected>()
                    val cursorAfterRecovery = syncStreamClient.currentLastEventId()
                    cursorAfterRecovery.shouldNotBeNull()
                    cursorAfterRecovery shouldBe cursorAfterInitial
                } finally {
                    collectorScope.cancel()
                }
            }
        }
    })
