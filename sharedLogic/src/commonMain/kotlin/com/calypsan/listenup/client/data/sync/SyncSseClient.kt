@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readLine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

internal const val INITIAL_RECONNECT_DELAY_MS = 1_000L
internal const val MAX_RECONNECT_DELAY_MS = 60_000L
internal const val RECONNECT_BACKOFF_MULTIPLIER = 2.0

/**
 * Consecutive [SyncSseClient]-internal auth-failed connects that prove the streaming client's
 * in-band bearer refresh cannot mint a working token (the plugin performs one refresh per
 * attempt, so two failures = refresh token is dead). Spec §6.3 / §14-Q1.
 */
internal const val AUTH_EXHAUSTED_THRESHOLD = 2

/** Slow heartbeat cadence while auth-parked — still wakeable instantly by [SyncSseClient.reconnectNow]. */
internal const val AUTH_PARKED_DELAY_MS = 300_000L

/**
 * Upper bound on the connection-establishment phase. A connect that hasn't produced a response
 * within this window is abandoned and retried, so a down/unreachable server can't wedge the loop on
 * engines with no finite connect timeout (Darwin/URLSession). The streaming read stays unbounded.
 */
internal const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
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
 *
 * Constructor takes two suspend lambdas instead of concrete `ApiClientFactory`
 * / `ServerConfig` so production wiring (D1) passes method references and
 * tests (Tier 3 e2e) pass any [HttpClient] + base URL. Avoids dragging full
 * auth wiring into the test fixture.
 *
 * [onAuthExhausted] fires once per auth-failure streak when the in-band refresh is proven dead
 * (see AUTH_EXHAUSTED_THRESHOLD).
 */
internal class SyncSseClient(
    private val serverUrlProvider: suspend () -> String?,
    private val streamingClientProvider: suspend () -> HttpClient,
    private val state: SyncEngineState,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val onAuthExhausted: suspend () -> Unit = {},
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

    /**
     * Reconnect-backoff attempt counter; reset to 0 by [reconnectNow]. Deliberately unsynchronized:
     * it is best-effort backoff state, and the only cross-coroutine race (a reset briefly unobserved
     * by the loop) merely lengthens one backoff. The real wake hand-off is [wakeSignal], which is
     * thread-safe — don't add a lock here.
     */
    private var reconnectAttempt = 0

    /**
     * Consecutive [ConnectAttempt.AuthFailed] counter driving auth-exhaustion detection and the
     * parked heartbeat. Reset by a successful connect and by [reconnectNow]. Same deliberately
     * unsynchronized best-effort discipline as [reconnectAttempt] — don't add a lock.
     */
    private var authFailureStreak = 0

    /** Conflated wake signal: [reconnectNow] sends; the backoff wait races it against the delay. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

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
        reconnectAttempt = 0
        connectionJob =
            scope.launch {
                while (isActive) {
                    state.setConnection(ConnectionState.Connecting)
                    when (runOnce()) {
                        ConnectAttempt.GracefulClose -> {
                            return@launch
                        }

                        ConnectAttempt.AuthFailed -> {
                            // The FIRST 401/403 stays transient: the streaming client's bearer
                            // plugin refreshes the token in-band on the next attempt. Reaching
                            // AUTH_EXHAUSTED_THRESHOLD consecutive failures proves the refresh
                            // cannot mint a working token — report ONCE (typed, via
                            // onAuthExhausted → ErrorBus → SessionLapsed; the engine gate then
                            // parks this loop entirely) and fall back to a slow heartbeat as the
                            // defense-in-depth backstop. reconnectNow() wakes the park instantly
                            // and resets the streak (never stranded).
                            authFailureStreak++
                            state.setConnection(ConnectionState.Disconnected("auth"))
                            state.recordError(SyncError.RealtimeDisconnected())
                            if (authFailureStreak == AUTH_EXHAUSTED_THRESHOLD) onAuthExhausted()
                            if (authFailureStreak >= AUTH_EXHAUSTED_THRESHOLD) parkedWait() else backoffWait()
                        }

                        ConnectAttempt.Reconnect -> {
                            state.setConnection(ConnectionState.Disconnected("reconnecting"))
                            state.recordError(SyncError.RealtimeDisconnected())
                            backoffWait()
                        }

                        ConnectAttempt.Connected -> {
                            reconnectAttempt = 0
                            authFailureStreak = 0
                            state.recordSuccess(nowMillis())
                        }
                    }
                }
            }
    }

    /**
     * Wait out the current backoff, returning early if [reconnectNow] signals.
     * Computes the delay from the current attempt, increments, then waits — so a
     * [reconnectNow] (which resets [reconnectAttempt] to 0) shortens this wait and the next.
     */
    private suspend fun backoffWait() {
        val delayMs = reconnectDelayMillis(reconnectAttempt)
        logger.debug { "SSE reconnect in ${delayMs}ms (attempt $reconnectAttempt)" }
        reconnectAttempt++
        withTimeoutOrNull(delayMs) { wakeSignal.receive() }
    }

    /** Auth-parked heartbeat: wait [AUTH_PARKED_DELAY_MS], returning early if [reconnectNow] signals. */
    private suspend fun parkedWait() {
        logger.debug { "SSE auth-parked; next probe in ${AUTH_PARKED_DELAY_MS}ms" }
        withTimeoutOrNull(AUTH_PARKED_DELAY_MS) { wakeSignal.receive() }
    }

    /** Close the SSE connection and stop the reconnect loop. */
    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        state.setConnection(ConnectionState.Disconnected("closed"))
    }

    override fun reconnectNow() {
        reconnectAttempt = 0
        authFailureStreak = 0
        wakeSignal.trySend(Unit)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun runOnce(): ConnectAttempt {
        val serverUrl = serverUrlProvider() ?: return ConnectAttempt.GracefulClose
        return try {
            val request =
                streamingClientProvider().prepareGet("$serverUrl$SSE_ENDPOINT") {
                    lastEventId?.let { header(HttpHeaders.LastEventID, it.toString()) }
                }
            connectBounded(request)
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

    /**
     * Runs one connection attempt, bounding only the *connect* phase: if the server hasn't produced
     * a response within [connectTimeoutMillis], the attempt is abandoned and mapped to
     * [ConnectAttempt.Reconnect] so the outer loop can back off and retry. Once connected, the
     * streaming read ([streamFrames]) runs unbounded — a live-but-idle SSE stream must never be torn
     * down, and both servers heartbeat well within any read window.
     *
     * Without this bound, a connect against a down/unreachable server hangs forever on engines with
     * no finite connect timeout (Darwin/URLSession): [runOnce] never returns, the state never leaves
     * `Connecting`, and every downstream recovery mechanism stays wedged.
     */
    private suspend fun connectBounded(request: HttpStatement): ConnectAttempt =
        coroutineScope {
            val connected = CompletableDeferred<Unit>()
            val reader =
                async {
                    request.execute { response ->
                        connected.complete(Unit)
                        streamFrames(response)
                    }
                }
            if (withTimeoutOrNull(connectTimeoutMillis) { connected.await() } == null) {
                reader.cancel()
                ConnectAttempt.Reconnect
            } else {
                reader.await()
            }
        }

    /** Read and dispatch SSE frames until the stream closes. Returns [ConnectAttempt.Connected] on EOF. */
    private suspend fun streamFrames(response: HttpResponse): ConnectAttempt {
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
        return ConnectAttempt.Connected
    }

    private enum class ConnectAttempt { Connected, Reconnect, AuthFailed, GracefulClose }
}
