package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncStreamClient
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Drives automatic reconnection while the SSE firehose is down.
 *
 * Reachability is derived purely from the SSE connection, and the SSE loop only ever retries the
 * *stored* URL — so a server that returns at a new IP/port (or after a restart) can leave the client
 * stuck offline. This supervisor closes that gap: whenever the firehose is not connected, it
 * periodically (1) re-resolves the live server URL ([reevaluate] → mDNS relocation + remote fallback),
 * (2) probes the resolved active URL with the unauthenticated [InstanceRepository.verifyServer], and
 * (3) either kicks an immediate reconnect ([SyncStreamClient.reconnectNow]) when it's the same instance, or
 * triggers a clean re-auth when the server's instanceId changed.
 *
 * Offline-first / never-stranded: never blocks, never clears the configured URL; the manual
 * "Change Server" path remains the explicit fallback.
 *
 * The loop is gated on a boolean "is connected" (not the raw [ConnectionState]) so the SSE client's
 * Connecting↔Disconnected backoff oscillation doesn't restart it; the probe interval escalates on
 * sustained failure so a long outage doesn't sweep mDNS every few seconds.
 *
 * @param reevaluate re-points the active URL at a reachable address (wired to
 *   `ConnectionCoordinator.reevaluate`); a lambda so this stays unit-testable.
 * @param reportProbe reports probe reachability into the health store's oracle; a lambda to stay
 *   unit-testable.
 * @param rebuildStreamingClient drops the cached SSE streaming client so the next reconnect dials a
 *   fresh one (wired to `ApiClientFactory.invalidateStreamingClientOnly`); a lambda to stay
 *   unit-testable. See [recoveryLoop] for the wedged-socket rationale.
 */
internal class ReconnectionSupervisor(
    private val engineState: SyncEngineState,
    private val instanceRepository: InstanceRepository,
    private val serverConfig: ServerConfig,
    private val sseClient: SyncStreamClient,
    private val authSession: AuthSession,
    private val errorBus: ErrorBus,
    private val reevaluate: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val probeIntervalMillis: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val reportProbe: (Boolean) -> Unit = {},
    private val rebuildStreamingClient: suspend () -> Unit = {},
) {
    /** Start observing connection state. Call once at app start. */
    fun start() {
        scope.launch {
            engineState
                .observe()
                .map { it.connection is ConnectionState.Connected }
                .distinctUntilChanged()
                .collectLatest { connected ->
                    // collectLatest cancels the running recovery loop the moment we become Connected.
                    // Gating on the Boolean (not the raw ConnectionState) means the SSE client's
                    // Connecting<->Disconnected backoff flapping does NOT keep restarting the loop.
                    try {
                        if (!connected) recoveryLoop()
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Reconnection recovery failed; supervisor continues" }
                    }
                }
        }
    }

    private suspend fun recoveryLoop() {
        var interval = probeIntervalMillis
        // Consecutive reconnect kicks against a *reachable* server that haven't produced a Connected
        // firehose. A live server that just needs a moment reconnects in one or two kicks (then
        // collectLatest cancels this loop, resetting the counter to 0 on the next Disconnected edge);
        // a count that keeps climbing means the cached streaming client is wedged, not the server down.
        var reachableKicks = 0
        while (currentCoroutineContext().isActive) {
            try {
                val active =
                    run {
                        reevaluate() // re-point the active URL at a reachable address first
                        serverConfig.getActiveUrl()
                    } ?: return // no server configured — nothing to recover

                when (val probe = instanceRepository.verifyServer(active.value)) {
                    is AppResult.Success -> {
                        reportProbe(true)
                        val connectedId = serverConfig.getConnectedServerId()
                        val serverId = probe.data.serverInfo.instanceId
                        if (connectedId != null && serverId != connectedId) {
                            logger.info { "Server instance changed ($connectedId -> $serverId); re-auth required" }
                            authSession.clearAuthTokens()
                            errorBus.emit(AuthError.ServerInstanceChanged())
                            return // stop hammering a server we can't use this session against
                        }
                        if (authSession.authState.value is AuthState.SessionLapsed) {
                            // A lapsed session can't ride reconnectNow() to recovery — the SSE connect
                            // would only 401 again (this call was the spam amplifier, resetting the SSE
                            // backoff on every successful unauthenticated probe). Keep probing slowly;
                            // the engine's auth gate resumes the firehose on re-auth.
                            interval = MAX_PROBE_INTERVAL_MS
                        } else {
                            // Same instance (or no stored id to compare): server is live — kick the SSE
                            // loop now. On success the connection flips to Connected and collectLatest
                            // cancels us.
                            reachableKicks = kickReachableServer(reachableKicks)
                            interval = probeIntervalMillis // reachable again → probe promptly until Connected
                        }
                    }

                    is AppResult.Failure -> {
                        reportProbe(false)
                        logger.debug { "Reconnect probe failed: ${probe.error.code}" }
                        interval = (interval * 2).coerceAtMost(MAX_PROBE_INTERVAL_MS) // back off while down
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failure INSIDE a recovery iteration (most likely reevaluate's mDNS/IO sweep
                // throwing during a network change) must NOT end the loop. The collector re-invokes
                // this only on a fresh Disconnected->Connected edge — which recovery itself is what
                // produces — so a propagated throw latches recovery off for the whole process until
                // relaunch. Log, back off on the same escalating schedule as a failed probe, and keep
                // trying (the escalating delay below prevents a hot-spin on a persistently-throwing
                // reevaluate).
                logger.warn(e) { "Reconnect recovery iteration failed; backing off and retrying" }
                interval = (interval * 2).coerceAtMost(MAX_PROBE_INTERVAL_MS)
            }

            delay(interval)
        }
    }

    /**
     * Kick an immediate reconnect against a reachable, same-instance server. If [reachableKicks]
     * (successive kicks that never produced a Connected firehose) crosses [STREAMING_REBUILD_AFTER_KICKS],
     * the cached streaming client is wedged — an OS-suspended dead socket on an unchanged URL, which
     * the moved-URL rebuild path never covers — so drop it BEFORE the kick (rebuild-before-connect,
     * self-teardown-safe) so the loop's next `getStreamingClient()` dials a fresh client. Gated on
     * repeated failure so it fires once per wedged episode, not as a per-reconnect teardown loop.
     * Returns the updated counter: reset to 0 after a rebuild so a still-wedged client earns another
     * rebuild after the same interval.
     */
    private suspend fun kickReachableServer(reachableKicks: Int): Int {
        val kicks = reachableKicks + 1
        val wedged = kicks >= STREAMING_REBUILD_AFTER_KICKS
        if (wedged) {
            logger.info { "Firehose still down after $kicks reachable kicks; rebuilding streaming client" }
            rebuildStreamingClient()
        }
        sseClient.reconnectNow()
        return if (wedged) 0 else kicks
    }

    private companion object {
        /**
         * Probe floor: how soon after the firehose drops we first re-check reachability, and the
         * cadence we snap back to the moment the server answers again. Kept tight so recovery feels
         * near-instant while the app is open; the interval escalates to [MAX_PROBE_INTERVAL_MS] on
         * sustained failure so a long outage doesn't sweep mDNS every couple of seconds.
         */
        const val DEFAULT_PROBE_INTERVAL_MS = 2_000L
        const val MAX_PROBE_INTERVAL_MS = 60_000L

        /**
         * Consecutive reachable-but-still-disconnected reconnect kicks before we rebuild the streaming
         * client. Small enough that a genuinely-wedged socket recovers in a handful of seconds
         * (kicks pace at [DEFAULT_PROBE_INTERVAL_MS]); large enough that a server that just needs a
         * moment reconnects on the first kick or two and never triggers a rebuild.
         */
        const val STREAMING_REBUILD_AFTER_KICKS = 3
    }
}
