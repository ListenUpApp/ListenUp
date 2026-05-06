package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Manages Server-Sent Events (SSE) connection for real-time library updates.
 *
 * SSE Connection Lifecycle:
 * 1. connect() opens SSE stream to GET /api/v1/sync/events
 * 2. Parses incoming SSE events and emits to eventFlow
 * 3. Automatically reconnects on connection loss (exponential backoff)
 * 4. disconnect() closes stream and cancels reconnection
 *
 * Event Flow Architecture:
 * - Events emitted as [SSEChannelMessage] — a wire-decoded [SSEEvent] or a synthetic
 *   [SSEChannelMessage.Reconnected] signal.
 * - SharedFlow allows multiple collectors (e.g., SyncManager + UI)
 * - Hot flow: Events are broadcast even if no collectors (replay=0)
 *
 * Thread Safety:
 * - All operations are coroutine-safe
 * - Connection job tracked via nullable Job field
 * - Reconnection uses exponential backoff to avoid server overload
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 * @property serverConfig For retrieving server URL
 * @property scope CoroutineScope for SSE connection lifecycle
 */
class SSEManager(
    private val clientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
    private val scope: CoroutineScope,
    private val downloadRepository: DownloadRepository,
) : SSEManagerContract {
    private val _eventFlow =
        MutableSharedFlow<SSEChannelMessage>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    override val eventFlow: SharedFlow<SSEChannelMessage> = _eventFlow.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)

    /** Observable connection state for UI/monitoring. Thread-safe. */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var connectionJob: Job? = null

    /** Tracks if we've been connected before - used to detect reconnection vs initial connection. */
    private var hasBeenConnected = false

    /** RFC3339 timestamp of when the SSE connection was last lost. Used for ?since= on reconnect. */
    private var disconnectedAt: String? = null

    // JSON parser for polymorphic SSE event decode
    private val json = appJson

    companion object {
        private const val SSE_ENDPOINT = "/api/v1/sync/events"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val RECONNECT_BACKOFF_MULTIPLIER = 2.0
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }

    /**
     * Connects to SSE stream and begins emitting events.
     * Safe to call multiple times - will not create duplicate connections.
     *
     * Prerequisites:
     * - Server URL must be configured
     * - User must be authenticated (has tokens)
     *
     * Call this after successful login or sync to start receiving real-time updates.
     */
    override fun connect() {
        if (connectionJob?.isActive == true) {
            return // Already connected
        }

        connectionJob =
            scope.launch {
                var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                var keepReconnecting = true

                while (isActive && keepReconnecting) {
                    keepReconnecting = runConnectionAttempt()
                    if (keepReconnecting && isActive) {
                        logger.debug { "Reconnecting in ${reconnectDelay}ms..." }
                        delay(reconnectDelay)
                        reconnectDelay =
                            (reconnectDelay * RECONNECT_BACKOFF_MULTIPLIER)
                                .toLong()
                                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                    }
                }
            }
    }

    /**
     * Run one SSE connection attempt.
     *
     * @return `true` if the caller should reconnect after backoff, `false` if the
     *   outer loop should exit (graceful close, missing config, or auth failure).
     */
    @Suppress("ReturnCount")
    private suspend fun runConnectionAttempt(): Boolean =
        try {
            val serverUrl = serverConfig.getServerUrl()
            if (serverUrl == null) {
                logger.debug { "Server URL not configured, skipping SSE connection" }
                return false
            }
            logger.info { "Connecting to SSE stream..." }
            streamEvents()
            logger.debug { "Connection ended gracefully" }
            false
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: ResponseException) {
            _isConnected.value = false
            disconnectedAt = Timestamp.now().toIsoString()
            logger.debug { "SSE disconnected at $disconnectedAt" }
            val statusCode = e.response.status.value
            if (statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN) {
                logger.warn { "SSE connection failed with auth error ($statusCode), not retrying" }
                return false
            }
            ErrorBus.emit(SyncError.RealtimeDisconnected(debugInfo = e.message))
            logger.warn(e) { "Connection error" }
            true
        } catch (e: Exception) {
            _isConnected.value = false
            disconnectedAt = Timestamp.now().toIsoString()
            logger.debug { "SSE disconnected at $disconnectedAt" }
            ErrorBus.emit(SyncError.RealtimeDisconnected(debugInfo = e.message))
            logger.warn(e) { "Connection error" }
            true
        }

    /**
     * Disconnects from SSE stream and stops emitting events.
     */
    override fun disconnect() {
        logger.debug { "Disconnecting..." }
        connectionJob?.cancel()
        connectionJob = null
        _isConnected.value = false
    }

    /**
     * Opens SSE stream and parses events.
     * Throws exception on connection failure (caught by connect() for reconnection).
     */
    private suspend fun streamEvents() {
        logger.trace { "Getting server URL..." }
        val serverUrl =
            serverConfig.getServerUrl()
                ?: error("Server URL not configured")
        logger.trace { "Server URL: $serverUrl" }

        logger.trace { "Getting streaming HTTP client (no timeouts)..." }
        val httpClient = clientFactory.getStreamingClient()
        logger.trace { "Streaming HTTP client ready" }

        // Build URL with ?since= param for reconnection replay
        val sinceParam = disconnectedAt
        val url =
            if (sinceParam != null) {
                "$serverUrl$SSE_ENDPOINT?since=$sinceParam"
            } else {
                "$serverUrl$SSE_ENDPOINT"
            }
        logger.debug { "Opening streaming connection to: $url" }

        // Use prepareGet + execute for streaming responses that never complete
        httpClient.prepareGet(url).execute { response ->
            logger.debug { "Got HTTP response: ${response.status}" }
            logger.trace { "Response Content-Type: ${response.headers["Content-Type"]}" }

            val channel = response.bodyAsChannel()
            logger.trace { "Got body channel, starting to read events..." }

            try {
                // Check if this is a reconnection (we were previously connected)
                val isReconnection = hasBeenConnected

                _isConnected.value = true
                hasBeenConnected = true

                // Emit reconnection event so SyncManager can trigger delta sync
                if (isReconnection && sinceParam != null) {
                    logger.info {
                        "SSE reconnected after disconnect at $sinceParam - emitting Reconnected event for delta sync"
                    }
                    _eventFlow.emit(SSEChannelMessage.Reconnected(disconnectedAt = sinceParam))
                    // Clear disconnectedAt after successful reconnection with replay
                    disconnectedAt = null
                    // Bug 4 (W8 Phase D): re-check WAITING_FOR_SERVER rows after reconnect to catch
                    // transcode.complete events that fired during disconnect.
                    scope.launch {
                        downloadRepository.recheckWaitingForServer()
                    }
                } else if (isReconnection) {
                    logger.info { "SSE reconnected (no disconnectedAt recorded) - emitting Reconnected event" }
                    _eventFlow.emit(SSEChannelMessage.Reconnected(disconnectedAt = Timestamp.now().toIsoString()))
                    // Bug 4 (W8 Phase D): re-check WAITING_FOR_SERVER rows after reconnect to catch
                    // transcode.complete events that fired during disconnect.
                    scope.launch {
                        downloadRepository.recheckWaitingForServer()
                    }
                }

                parseSSEStream(channel)
                logger.debug { "Stream reading ended gracefully" }
            } finally {
                logger.trace { "Cleaning up channel..." }
                channel.cancel(null)
            }
        }
    }

    /**
     * Parses SSE event stream format.
     *
     * SSE Format (from server):
     * ```
     * event: book.created
     * data: {"timestamp":"...","type":"book.created","data":{...}}
     *
     * event: heartbeat
     * data: {"timestamp":"...","type":"heartbeat","data":{...}}
     *
     * ```
     *
     * Each event consists of "event:" and "data:" lines, followed by double newline.
     */
    private suspend fun parseSSEStream(channel: ByteReadChannel) {
        var currentEventData = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break

            when {
                line.isEmpty() -> {
                    // Empty line marks end of event
                    if (currentEventData.isNotEmpty()) {
                        processEvent(currentEventData.toString())
                        currentEventData = StringBuilder()
                    }
                }

                line.startsWith("event: ") -> {
                    // SSE event type line (we don't use this; the JSON payload carries `type`)
                }

                line.startsWith("data: ") -> {
                    // SSE data line
                    currentEventData.append(line.removePrefix("data: "))
                }

                line.startsWith(":") -> {
                    // SSE comment line, ignore
                }

                line.startsWith("id: ") || line.startsWith("retry: ") -> {
                    // SSE id/retry lines, ignore
                }
            }
        }
    }

    /**
     * Processes a complete SSE event JSON string.
     *
     * Decodes the envelope polymorphically via [appJson]; unknown discriminators
     * produce an [SSEEvent.Unknown] sentinel that consumers log without crashing.
     * Malformed JSON is logged and dropped; the SSE channel continues.
     */
    private suspend fun processEvent(eventJson: String) {
        val event =
            try {
                json.decodeFromString<SSEEvent>(eventJson)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log a bounded prefix + exception message instead of the full payload.
                // Raw eventJson can contain PII (user profiles, reading sessions) and can be large;
                // preserve debuggability without dumping the full body.
                logger.warn(e) { "Failed to parse SSE event: ${e.message} (head='${eventJson.take(120)}')" }
                return
            }
        _eventFlow.emit(SSEChannelMessage.Wire(event))
    }
}
