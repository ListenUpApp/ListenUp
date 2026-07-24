package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP_TIMEOUT = 30.seconds

/**
 * Invariant-level proof that [SyncEngine.lifecycleReconcile] self-heals a live event dropped ABOVE
 * the client cursor — the `FirehoseSuppressed` gap that a digest-only reconcile is structurally
 * blind to. This is the ordering guarantee (forward catch-up FIRST, then digest) made observable.
 *
 * Gap arrangement:
 *   1. Start the engine; write "VisibleSeries" normally so the client receives its SSE event and
 *      advances its `series` cursor to that revision.
 *   2. Write "GapSeries" under [FirehoseSuppressed] — the server bumps the revision (now ABOVE the
 *      client cursor) and commits the row, but publishes NO SSE event. Because no later visible
 *      write follows, the client cursor stays below the gap revision.
 *   3. Assert the gap: "GapSeries" is absent from client Room.
 *   4. [SyncEngine.forceReconcile] (digest-only, AT the cursor) must FAIL to land it — the gap row
 *      is above the cursor, excluded from both sides' digests. This is why forceReconcile was the
 *      wrong tool for LibraryDataChanged.
 *   5. [SyncEngine.lifecycleReconcile] (force = true) runs forward catch-up FIRST, draining the
 *      above-cursor row, then digests. "GapSeries" lands in Room — no restart, no reconnect.
 *
 * The "absent after forceReconcile, present after lifecycleReconcile" pair is the whole point — it
 * proves the catch-up-then-digest ordering is load-bearing. Do not weaken either half.
 */
class LifecycleReconcileInvariantTest :
    FunSpec({

        test("lifecycleReconcile lands an above-cursor suppressed row that forceReconcile cannot")
            .config(timeout = 2.seconds * 60) {
                withClientSyncEngineAgainstServer {

                    // ── Step 1: start + a normal series write that advances the client cursor ─────
                    engine.start(currentUserId = "u1")
                    val visibleId = serverSeriesRepository.resolveOrCreate("VisibleSeries").value
                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.seriesDao().getById(visibleId) == null) {
                            delay(10)
                        }
                    }

                    // ── Step 2: write the GAP series suppressed, ABOVE the client cursor ─────────
                    // Its revision bumps past the visible one and the row commits, but ChangeBus is
                    // never published, so no SSE event reaches the client and the cursor does not move.
                    val gapId =
                        withContext(FirehoseSuppressed) {
                            serverSeriesRepository.resolveOrCreate("GapSeries")
                        }.value

                    // ── Step 3: assert the gap exists ───────────────────────────────────────────
                    clientDatabase.seriesDao().getById(gapId).shouldBeNull()

                    // ── Step 4: digest-only reconcile CANNOT see the above-cursor row ────────────
                    engine.forceReconcile()
                    clientDatabase.seriesDao().getById(gapId).shouldBeNull()

                    // ── Step 5: lifecycle reconcile's forward catch-up drains it ─────────────────
                    engine.lifecycleReconcile(force = true)
                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.seriesDao().getById(gapId) == null) {
                            delay(10)
                        }
                    }
                    clientDatabase.seriesDao().getById(gapId).shouldNotBeNull()
                }
            }
    })
