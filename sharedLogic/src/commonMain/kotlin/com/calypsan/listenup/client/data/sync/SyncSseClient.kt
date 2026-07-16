@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SSE_ENDPOINT = "/api/v1/sync/events"
private const val FRAME_BUFFER_CAPACITY = 256

/**
 * Manages the SSE connection to `/api/v1/sync/events`. A thin composition over the shared
 * [SseConnection] engine: the engine owns the bounded connect, spec-tolerant framing, exponential
 * reconnect backoff, `Last-Event-ID` resume, and the auth-park heartbeat; this class folds the
 * engine's [SseEvent] lifecycle into the ambient [SyncEngineState] and re-broadcasts frames on a hot
 * [SharedFlow] the dispatcher collects.
 *
 * Constructor takes two suspend lambdas instead of concrete `ApiClientFactory` / `ServerConfig` so
 * production wiring (D1) passes method references and tests (Tier 3 e2e) pass any [HttpClient] + base
 * URL. Avoids dragging full auth wiring into the test fixture.
 *
 * [onAuthExhausted] fires once per auth-failure streak when the in-band refresh is proven dead
 * (see [AUTH_EXHAUSTED_THRESHOLD]).
 */
internal class SyncSseClient(
    serverUrlProvider: suspend () -> String?,
    streamingClientProvider: suspend () -> HttpClient,
    private val state: SyncEngineState,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    onAuthExhausted: suspend () -> Unit = {},
    // Test seam: a small capacity lets a test force `frameBus.emit` to suspend deterministically
    // (SyncSseClientDeliveryOrderingTest) without depending on the production buffer depth.
    frameBufferCapacity: Int = FRAME_BUFFER_CAPACITY,
) : SseClient {
    private val frameBus =
        MutableSharedFlow<ParsedSseFrame>(
            replay = 0,
            extraBufferCapacity = frameBufferCapacity,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /** Hot stream of parsed frames; consumers (the dispatcher) collect from here. */
    override val frames: SharedFlow<ParsedSseFrame> = frameBus.asSharedFlow()

    private var connectionJob: Job? = null

    /** Highest `id:` seen this session; sent as `Last-Event-Id` on reconnect. */
    private var lastEventId: Long? = null

    private val connection =
        SseConnection(
            urlProvider = { serverUrlProvider()?.let { "$it$SSE_ENDPOINT" } },
            streamingClientProvider = streamingClientProvider,
            connectTimeoutMillis = connectTimeoutMillis,
            resumeIdProvider = { lastEventId },
            parkOnAuthExhaustion = true,
            onAuthExhausted = onAuthExhausted,
        )

    /** Seed [lastEventId] from the persisted highest cursor before the first connect. */
    override fun seedLastEventId(initial: Long?) {
        if (initial != null) lastEventId = initial
    }

    /** Read-only accessor for [SyncEngine.handleCursorStale] and tests. */
    override fun currentLastEventId(): Long? = lastEventId

    /**
     * Drop the current connection and reseed [lastEventId] from [newLastEventId].
     * Caller is responsible for invoking [connect] afterwards once the new cursor
     * is in place — keeps the disconnect/reseed/reconnect ordering visible at
     * the orchestration site rather than hidden inside the SSE client.
     */
    override suspend fun reseed(newLastEventId: Long?) {
        disconnect()
        lastEventId = newLastEventId
    }

    /** Open an SSE connection (or no-op if one is already active). */
    override fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob =
            scope.launch {
                connection.events().collect { event -> apply(event) }
            }
    }

    /**
     * Fold one engine lifecycle event into the ambient [SyncEngineState] and re-broadcast frames.
     *
     * `internal` (not `private`) so [SyncSseClientDeliveryOrderingTest] can drive it directly —
     * exercising the real [connection]/[SseConnection] plumbing can't force a deterministic
     * mid-emit suspension without a sleepy wall-clock race.
     */
    internal suspend fun apply(event: SseEvent) {
        when (event) {
            SseEvent.Connecting -> {
                state.setConnection(ConnectionState.Connecting)
            }

            SseEvent.Connected -> {
                state.setConnection(ConnectionState.Connected(lastEventId))
                state.recordSuccess(nowMillis())
            }

            is SseEvent.Frame -> {
                val frame = event.frame
                // emit is the linearization point for "this frame will be delivered": advancing
                // lastEventId before it returns would commit the watermark past a frame that a
                // cancellation mid-suspend (the bounded frameBus can suspend on emit) never
                // actually delivered — the server would then never resend it this session.
                frameBus.emit(frame)
                frame.id?.let { lastEventId = it }
                state.setConnection(ConnectionState.Connected(lastEventId))
            }

            is SseEvent.Disconnected -> {
                val reason = if (event.reason == DisconnectReason.Auth) "auth" else "reconnecting"
                state.setConnection(ConnectionState.Disconnected(reason))
                state.recordError(SyncError.RealtimeDisconnected())
            }
        }
    }

    /** Close the SSE connection and stop the reconnect loop. */
    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        state.setConnection(ConnectionState.Disconnected("closed"))
    }

    override fun reconnectNow() = connection.reconnectNow()
}
