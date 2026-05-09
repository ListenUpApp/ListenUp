@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readLine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

internal const val INITIAL_RECONNECT_DELAY_MS = 1_000L
internal const val MAX_RECONNECT_DELAY_MS = 60_000L
internal const val RECONNECT_BACKOFF_MULTIPLIER = 2.0
private const val SSE_ENDPOINT = "/api/v1/sync/events"
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val FRAME_BUFFER_CAPACITY = 256

/** 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s, 60s, ... — caps at [MAX_RECONNECT_DELAY_MS]. */
internal fun reconnectDelayMillis(attempt: Int): Long {
    val raw =
        (
            INITIAL_RECONNECT_DELAY_MS.toDouble() *
                RECONNECT_BACKOFF_MULTIPLIER.pow(attempt.toDouble())
        ).toLong()
    return raw.coerceAtMost(MAX_RECONNECT_DELAY_MS)
}

/**
 * Parse a sequence of raw SSE lines into [ParsedSseFrame]s. Handles `id:`,
 * `event:`, multi-line `data:`, and comment lines (lines starting with `:`).
 * A blank line terminates a frame.
 */
internal fun parseSseStream(lines: Sequence<String>): List<ParsedSseFrame> {
    val frames = mutableListOf<ParsedSseFrame>()
    var id: Long? = null
    var event: String? = null
    val data = StringBuilder()

    for (line in lines) {
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty() || id != null || event != null) {
                    frames += ParsedSseFrame(id = id, event = event, data = data.toString())
                }
                id = null
                event = null
                data.clear()
            }

            line.startsWith(":") -> {
                // SSE comment line — ignored.
            }

            line.startsWith("id:") -> {
                id = line.substringAfter("id:").trim().toLongOrNull()
            }

            line.startsWith("event:") -> {
                event = line.substringAfter("event:").trim()
            }

            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter("data:").trimStart())
            }
        }
    }
    return frames
}

/**
 * Manages the SSE connection to `/api/v1/sync/events`. One connection at a
 * time, exponential reconnect backoff, `Last-Event-Id` resume.
 *
 * Emits a hot [SharedFlow] of parsed frames; consumers (dispatcher) collect.
 * State changes go to [SyncEngineState] so UI ambient indicators can react.
 */
class SyncSseClient(
    private val clientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
    private val state: SyncEngineState,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SseClient {
    private val frameBus =
        MutableSharedFlow<ParsedSseFrame>(
            replay = 0,
            extraBufferCapacity = FRAME_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /** Hot stream of parsed frames; consumers (the dispatcher) collect from here. */
    override val frames: SharedFlow<ParsedSseFrame> = frameBus.asSharedFlow()

    private var connectionJob: Job? = null

    /** Highest `id:` seen this session; sent as `Last-Event-Id` on reconnect. */
    private var lastEventId: Long? = null

    /** Seed [lastEventId] from the persisted highest cursor before the first connect. */
    override fun seedLastEventId(initial: Long?) {
        if (initial != null) lastEventId = initial
    }

    /** Open an SSE connection (or no-op if one is already active). */
    override fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob =
            scope.launch {
                var attempt = 0
                while (isActive) {
                    state.setConnection(ConnectionState.Connecting)
                    when (runOnce()) {
                        ConnectAttempt.GracefulClose -> {
                            return@launch
                        }

                        ConnectAttempt.AuthFailed -> {
                            state.setConnection(ConnectionState.Disconnected("auth"))
                            return@launch
                        }

                        ConnectAttempt.Reconnect -> {
                            state.setConnection(ConnectionState.Disconnected("reconnecting"))
                            state.recordError(SyncError.RealtimeDisconnected())
                            val delayMs = reconnectDelayMillis(attempt)
                            logger.debug { "SSE reconnect in ${delayMs}ms (attempt $attempt)" }
                            attempt++
                            delay(delayMs)
                        }

                        ConnectAttempt.Connected -> {
                            attempt = 0
                            state.recordSuccess(nowMillis())
                        }
                    }
                }
            }
    }

    /** Close the SSE connection and stop the reconnect loop. */
    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        state.setConnection(ConnectionState.Disconnected("closed"))
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun runOnce(): ConnectAttempt {
        val serverUrl = serverConfig.getServerUrl() ?: return ConnectAttempt.GracefulClose
        return try {
            val httpClient = clientFactory.getStreamingClient()
            httpClient
                .prepareGet("$serverUrl$SSE_ENDPOINT") {
                    lastEventId?.let { header(HttpHeaders.LastEventID, it.toString()) }
                }.execute { response ->
                    state.setConnection(ConnectionState.Connected(lastEventId))
                    state.recordSuccess(nowMillis())
                    val channel = response.bodyAsChannel()
                    val buffer = StringBuilder()
                    while (!channel.isClosedForRead) {
                        val line = channel.readLine() ?: break
                        if (line.isEmpty()) {
                            // Append an empty trailing line so parseSseStream commits the frame.
                            val parsed = parseSseStream(buffer.lineSequence().plus(""))
                            for (frame in parsed) {
                                frame.id?.let { lastEventId = it }
                                frameBus.emit(frame)
                                state.setConnection(ConnectionState.Connected(lastEventId))
                            }
                            buffer.clear()
                        } else {
                            if (buffer.isNotEmpty()) buffer.append('\n')
                            buffer.append(line)
                        }
                    }
                    ConnectAttempt.Connected
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            val status = e.response.status.value
            if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
                ConnectAttempt.AuthFailed
            } else {
                ConnectAttempt.Reconnect
            }
        } catch (e: Exception) {
            logger.warn(e) { "SSE connection error" }
            ConnectAttempt.Reconnect
        }
    }

    private enum class ConnectAttempt { Connected, Reconnect, AuthFailed, GracefulClose }
}
