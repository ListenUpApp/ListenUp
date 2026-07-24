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
 * Proof-of-correctness E2E test for digest reconciliation.
 *
 * Proves that [SyncReconciler.reconcileAll] repairs the firehose gap: a server row
 * whose `revision <= highestCursor` that was NEVER delivered via SSE (because it
 * was written under [FirehoseSuppressed]) is detected by the digest divergence and
 * re-pulled into client Room by [SyncReconciler.reconcileAll].
 *
 * Gap arrangement:
 *   1. Start the engine so it can connect and track the SSE stream.
 *   2. Write "GapSeries" under [FirehoseSuppressed] — the server bumps the revision
 *      and commits the row, but does NOT publish to [ChangeBus], so no SSE event
 *      reaches the client. The client's Room has no row for this id.
 *   3. Write "VisibleSeries" normally — the server publishes the SSE event; the
 *      client receives it and advances its `highestCursor` above the gap revision.
 *   4. Assert the gap: "GapSeries" is absent from client Room (it was never delivered).
 *   5. Trigger repair: call [SyncReconciler.reconcileAll]. The local digest at
 *      `highestCursor` diverges from the server's (because "GapSeries" is in the
 *      server's digest but not the client's), so the reconciler re-pulls the `series`
 *      domain from zero, landing "GapSeries" in Room.
 *   6. Assert the repair: "GapSeries" is now present in client Room.
 *
 * The "absent before, present after" pair is the whole point — do not weaken either half.
 */
class DigestReconcileGapE2ETest :
    FunSpec({

        test("reconcileAll repairs a sub-floor gap row that was never delivered via SSE")
            .config(timeout = 2.seconds * 60) {
                withClientSyncEngineAgainstServer {

                    // ── Step 1: start the engine ──────────────────────────────────────────────
                    engine.start(currentUserId = "u1")

                    // ── Step 2: write the GAP series with the firehose suppressed ─────────────
                    // The revision counter bumps and the row commits server-side, but
                    // ChangeBus.publish is skipped — no SSE event reaches the client.
                    val gapId =
                        withContext(FirehoseSuppressed) {
                            serverSeriesRepository.resolveOrCreate("GapSeries")
                        }.value

                    // ── Step 3: write a normal series that DOES publish via SSE ───────────────
                    // This advances the global revision above the gap revision and gives the
                    // client a real SSE event to receive. Once the client Room has "VisibleSeries",
                    // we know the client's highestCursor is above the gap revision.
                    val visibleId = serverSeriesRepository.resolveOrCreate("VisibleSeries").value

                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.seriesDao().getById(visibleId) == null) {
                            delay(10)
                        }
                    }

                    // ── Step 4: assert the gap exists ────────────────────────────────────────
                    // "GapSeries" was written under FirehoseSuppressed — no SSE event was ever
                    // published, so the client Room must NOT contain it at this point.
                    clientDatabase.seriesDao().getById(gapId).shouldBeNull()

                    // ── Step 5: trigger reconciliation ───────────────────────────────────────
                    // The local digest at highestCursor is missing "GapSeries"; the server
                    // digest includes it. Divergence → full re-pull of the series domain.
                    reconciler.reconcileAll()

                    // ── Step 6: assert the repair ─────────────────────────────────────────────
                    // After reconcileAll, the series domain was re-pulled from zero and
                    // "GapSeries" must now be present in client Room.
                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.seriesDao().getById(gapId) == null) {
                            delay(10)
                        }
                    }
                    clientDatabase.seriesDao().getById(gapId).shouldNotBeNull()
                }
            }
    })
