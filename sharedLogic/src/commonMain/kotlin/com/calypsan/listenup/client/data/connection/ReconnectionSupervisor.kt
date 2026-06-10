package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncEngineState
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
 * (2) probes it with the unauthenticated [InstanceRepository.getServerInfo], and (3) either kicks an
 * immediate reconnect ([SseClient.reconnectNow]) when it's the same instance, or triggers a clean
 * re-auth when the server's instanceId changed.
 *
 * Offline-first / never-stranded: never blocks, never clears the configured URL; the manual
 * "Change Server" path remains the explicit fallback.
 *
 * @param reevaluate re-points the active URL at a reachable address (wired to
 *   `ConnectionCoordinator.reevaluate`); a lambda so this stays unit-testable.
 */
class ReconnectionSupervisor(
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
                .map { it.connection }
                .distinctUntilChanged()
                .collectLatest { connection ->
                    // collectLatest cancels the running recovery loop the moment the connection
                    // state changes (e.g. back to Connected) — clean stop.
                    if (connection !is ConnectionState.Connected) {
                        recoveryLoop()
                    }
                }
        }
    }

    private suspend fun recoveryLoop() {
        while (currentCoroutineContext().isActive) {
            serverConfig.getActiveUrl() ?: return // no server configured — nothing to recover

            reevaluate()

            when (val probe = instanceRepository.getServerInfo(forceRefresh = true)) {
                is AppResult.Success -> {
                    val connectedId = serverConfig.getConnectedServerId()
                    if (connectedId != null && probe.data.instanceId != connectedId) {
                        logger.info {
                            "Server instance changed ($connectedId -> ${probe.data.instanceId}); re-auth required"
                        }
                        authSession.clearAuthTokens()
                        errorBus.emit(AuthError.ServerInstanceChanged())
                        return // stop hammering a server we can't use this session against
                    }
                    sseClient.reconnectNow()
                }

                is AppResult.Failure -> {
                    logger.debug { "Reconnect probe failed: ${probe.error.code}" }
                }
            }

            delay(probeIntervalMillis)
        }
    }

    private companion object {
        const val DEFAULT_PROBE_INTERVAL_MS = 5_000L
    }
}
