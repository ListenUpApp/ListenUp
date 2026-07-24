package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP_TIMEOUT = 30.seconds

/**
 * Invariant-level proof that the new `activities` [MirroredDomain] self-heals a live SSE frame that
 * was never delivered — the "never restart" property, made observable for the social feed exactly as
 * [LifecycleReconcileInvariantTest] proves it for `series`.
 *
 * Gap arrangement (activities-flavoured):
 *   1. Start the engine; record a VISIBLE activity so the client receives its SSE frame and advances
 *      the `activities` cursor to that revision.
 *   2. Record a second activity under [FirehoseSuppressed] — the server commits the row and bumps the
 *      revision (now ABOVE the client cursor) but publishes NO SSE frame, so the client's live tail
 *      never sees it. This is the "missed live frame" the offline-first contract must recover from.
 *   3. Assert the gap: the client still holds exactly one activity.
 *   4. [SyncEngine.forceReconcile] (digest-only, AT the cursor) CANNOT land it — the gap row is above
 *      the cursor, excluded from both sides' digests.
 *   5. [SyncEngine.lifecycleReconcile] (force = true) runs forward catch-up FIRST, draining the
 *      above-cursor row into client Room — no restart, no reconnect.
 *
 * The "absent after forceReconcile, present after lifecycleReconcile" pair is the whole point: it
 * proves the cursored activity mirror recovers a dropped frame through the same catch-up-then-digest
 * ordering every other domain relies on. Both activities are non-book ([ActivityType.USER_JOINED]),
 * so the firehose ACL gate never withholds them — this test isolates the self-heal property.
 */
class ActivityLifecycleReconcileInvariantTest :
    FunSpec({

        test("lifecycleReconcile lands an above-cursor suppressed activity that forceReconcile cannot")
            .config(timeout = 2.seconds * 60) {
                withClientSyncEngineAgainstServer {

                    // ── Step 1: start + a normal activity write that advances the client cursor ──
                    engine.start(currentUserId = "u1")
                    serverActivityRecorder.record(userId = "u1", type = ActivityType.USER_JOINED)
                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.activityDao().count() == 0) {
                            delay(10)
                        }
                    }
                    clientDatabase.activityDao().count() shouldBe 1

                    // ── Step 2: record the GAP activity suppressed, ABOVE the client cursor ──────
                    // The row commits + bumps the revision, but ChangeBus is never published, so no
                    // SSE frame reaches the client and the cursor does not move.
                    withContext(FirehoseSuppressed) {
                        serverActivityRecorder.record(userId = "u1", type = ActivityType.USER_JOINED)
                    }

                    // ── Step 3: assert the gap — still just the one visible activity ─────────────
                    clientDatabase.activityDao().count() shouldBe 1

                    // ── Step 4: digest-only reconcile CANNOT see the above-cursor row ────────────
                    engine.forceReconcile()
                    clientDatabase.activityDao().count() shouldBe 1

                    // ── Step 5: lifecycle reconcile's forward catch-up drains it ────────────────
                    engine.lifecycleReconcile(force = true)
                    withTimeout(ROUND_TRIP_TIMEOUT) {
                        while (clientDatabase.activityDao().count() < 2) {
                            delay(10)
                        }
                    }
                    clientDatabase.activityDao().count() shouldBe 2
                }
            }
    })
