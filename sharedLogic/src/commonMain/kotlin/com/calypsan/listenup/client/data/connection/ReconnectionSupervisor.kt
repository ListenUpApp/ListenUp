package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SseClient
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
 * (3) either kicks an immediate reconnect ([SseClient.reconnectNow]) when it's the same instance, or
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
 */
internal class ReconnectionSupervisor(
    private val engineState: SyncEngineState,
    private val instanceRepository: InstanceRepository,
    private val serverConfig: ServerConfig,
    private val sseClient: SseClient,
    private val authSession: AuthSession,
    private val errorBus: ErrorBus,
    private val reevaluate: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val probeIntervalMillis: Long = DEFAULT_PROBE_INTERVAL_MS,
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
        while (currentCoroutineContext().isActive) {
            val active =
                run {
                    reevaluate() // re-point the active URL at a reachable address first
                    serverConfig.getActiveUrl()
                } ?: return // no server configured — nothing to recover

            when (val probe = instanceRepository.verifyServer(active.value)) {
                is AppResult.Success -> {
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
                        sseClient.reconnectNow()
                        interval = probeIntervalMillis // reachable again → probe promptly until Connected
                    }
                }

                is AppResult.Failure -> {
                    logger.debug { "Reconnect probe failed: ${probe.error.code}" }
                    interval = (interval * 2).coerceAtMost(MAX_PROBE_INTERVAL_MS) // back off while down
                }
            }

            delay(interval)
        }
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
    }
}
