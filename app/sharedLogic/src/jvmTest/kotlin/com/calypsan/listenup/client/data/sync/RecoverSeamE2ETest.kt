package com.calypsan.listenup.client.data.sync

import app.cash.turbine.test
import com.calypsan.listenup.client.data.sync.testing.awaitUntil
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Plan 005 — the unified recover seam. `SyncEngine.recoverRealtime()` (the firehose half of
 * [com.calypsan.listenup.client.data.repository.SyncRepository.recoverRealtime]) must:
 *  - re-open a firehose that died while the app was backgrounded — the "reconnects only on relaunch"
 *    gap, where no user action re-established a dead firehose stream — and
 *  - NOT churn an already-healthy connection (no flicker on a normal foreground).
 *
 * The harness wires no [com.calypsan.listenup.client.data.connection.ReconnectionSupervisor], so a
 * manual `syncStreamClient.disconnect()` stays down until the recover seam re-opens it — a
 * deterministic stand-in for a firehose that died while suspended.
 */
class RecoverSeamE2ETest :
    FunSpec({

        test("recoverRealtime re-opens a firehose that died while the app was backgrounded") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")
                awaitUntil { state.value.connection is ConnectionState.Connected }

                // The firehose dies while the app is backgrounded; nothing re-opens it on its own.
                syncStreamClient.disconnect()
                awaitUntil { state.value.connection is ConnectionState.Disconnected }

                // Foreground / pull-to-refresh / Retry all funnel here — it must re-establish the stream.
                engine.recoverRealtime()
                awaitUntil { state.value.connection is ConnectionState.Connected }
                state.value.connection.shouldBeInstanceOf<ConnectionState.Connected>()
            }
        }

        test("recoverRealtime does NOT churn an already-healthy firehose (no reconnect flicker)") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")
                awaitUntil { state.value.connection is ConnectionState.Connected }

                // Observe only connection-state transitions. A healthy recover must reconcile without
                // tearing the live firehose down — so no new connection state is emitted.
                state.observe().map { it.connection }.distinctUntilChanged().test {
                    awaitItem().shouldBeInstanceOf<ConnectionState.Connected>()
                    engine.recoverRealtime()
                    expectNoEvents()
                    cancelAndConsumeRemainingEvents()
                }
                state.value.connection.shouldBeInstanceOf<ConnectionState.Connected>()
            }
        }
    })
