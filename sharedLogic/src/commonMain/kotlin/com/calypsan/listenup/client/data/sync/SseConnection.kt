package com.calypsan.listenup.client.data.sync

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

internal const val INITIAL_RECONNECT_DELAY_MS = 1_000L
internal const val MAX_RECONNECT_DELAY_MS = 60_000L
internal const val RECONNECT_BACKOFF_MULTIPLIER = 2.0

/**
 * Consecutive auth-failed connects that prove the streaming client's in-band bearer refresh cannot
 * mint a working token (the plugin performs one refresh per attempt, so two failures = refresh token
 * is dead). Only meaningful when [SseConnection.parkOnAuthExhaustion] is set. Spec §6.3 / §14-Q1.
 */
internal const val AUTH_EXHAUSTED_THRESHOLD = 2

/** Slow heartbeat cadence while auth-parked — still wakeable instantly by [SseConnection.reconnectNow]. */
internal const val AUTH_PARKED_DELAY_MS = 300_000L

/**
 * Upper bound on the connection-establishment phase. A connect that hasn't produced a response
 * within this window is abandoned and retried, so a down/unreachable server can't wedge the loop on
 * engines with no finite connect timeout (Darwin/URLSession). The streaming read stays unbounded.
 */
internal const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

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
 * A blank line terminates a frame. Tolerant of `data:` with or without a
 * leading space, per the SSE spec — a mandatory space is a common hand-rolled bug.
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
 * Lifecycle + data events surfaced by [SseConnection] as it connects, streams, and reconnects.
 * Consumers that only want data ignore everything but [Frame]; the firehose folds the lifecycle
 * variants into its ambient [SyncEngineState].
 */
internal sealed interface SseEvent {
    /** A connect attempt is starting (or restarting after a drop). */
    data object Connecting : SseEvent

    /** The connection is established; the stream is live. */
    data object Connected : SseEvent

    /** A parsed SSE frame arrived on the live stream. */
    data class Frame(
        val frame: ParsedSseFrame,
    ) : SseEvent

    /** A connect attempt failed (or the stream dropped); the engine reconnects after backoff. */
    data class Disconnected(
        val reason: DisconnectReason,
    ) : SseEvent
}

/** Why a connection attempt ended before the reconnect wait. */
internal enum class DisconnectReason {
    /** A 401/403 rejected the connect — the bearer plugin may refresh on the next attempt. */
    Auth,

    /** Any other transport failure (unreachable server, connect timeout, mid-stream drop). */
    Transport,
}

/**
 * The battle-tested SSE transport core, extracted from the sync firehose so every SSE surface
 * inherits it: a bounded connect (the [connectTimeoutMillis] Darwin/URLSession guard), spec-tolerant
 * [parseSseStream] framing, and an exponential backoff reconnect loop with an instant [reconnectNow]
 * wake. Produces a cold [Flow] of [SseEvent]s that reconnects forever until the URL disappears
 * (graceful close) or the collector is cancelled.
 *
 * Parameterized so all three consumers compose over the one engine:
 *  - [urlProvider] yields the full SSE URL, or `null` to stop the loop (graceful close).
 *  - [streamingClientProvider] chooses the transport (authenticated firehose vs the unauthenticated
 *    pre-auth registration streams) — the "auth mode" is simply which client is returned.
 *  - [resumeIdProvider] supplies the `Last-Event-ID` header value on each (re)connect; default `null`
 *    for streams that don't resume.
 *  - [parkOnAuthExhaustion] enables the firehose-only auth-park: after [authExhaustedThreshold]
 *    consecutive 401/403s, [onAuthExhausted] fires once and the loop drops to a slow
 *    [parkedDelayMillis] heartbeat instead of the backoff ladder. Unauthenticated streams leave this
 *    off and treat every failure as a plain transport reconnect.
 *
 * A single [SseConnection] instance drives one active collection at a time; the backoff counters are
 * best-effort and reset at each collection start and by [reconnectNow].
 */
internal class SseConnection(
    private val urlProvider: suspend () -> String?,
    private val streamingClientProvider: suspend () -> HttpClient,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val resumeIdProvider: () -> Long? = { null },
    private val parkOnAuthExhaustion: Boolean = false,
    private val authExhaustedThreshold: Int = AUTH_EXHAUSTED_THRESHOLD,
    private val parkedDelayMillis: Long = AUTH_PARKED_DELAY_MS,
    private val onAuthExhausted: suspend () -> Unit = {},
) {
    /** Conflated wake signal: [reconnectNow] sends; the backoff/park wait races it against the delay. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Reconnect-backoff attempt counter; reset to 0 by [reconnectNow] and at each collection start.
     * Deliberately unsynchronized: best-effort backoff state whose only cross-coroutine race (a reset
     * briefly unobserved by the loop) merely lengthens one backoff. The real wake hand-off is
     * [wakeSignal], which is thread-safe — don't add a lock here.
     */
    private var reconnectAttempt = 0

    /**
     * Consecutive auth-failure counter driving auth-exhaustion detection and the parked heartbeat.
     * Reset by a successful connect, by [reconnectNow], and at each collection start. Same
     * deliberately unsynchronized best-effort discipline as [reconnectAttempt].
     */
    private var authFailureStreak = 0

    /**
     * Reset the reconnect backoff to zero and wake the retry loop so the next connect attempt fires
     * immediately. Called once the caller has confirmed the server is live again (possibly at a new
     * URL), turning "recover within up to 60s" into "recover within seconds".
     */
    fun reconnectNow() {
        reconnectAttempt = 0
        authFailureStreak = 0
        wakeSignal.trySend(Unit)
    }

    /**
     * Cold stream of [SseEvent]s. Connects (bounded), streams parsed frames, and on any drop
     * reconnects with backoff — forever, until [urlProvider] returns `null` (graceful close) or the
     * collector is cancelled.
     */
    fun events(): Flow<SseEvent> =
        channelFlow {
            reconnectAttempt = 0
            authFailureStreak = 0
            while (isActive) {
                send(SseEvent.Connecting)
                when (runOnce { event -> send(event) }) {
                    ConnectAttempt.GracefulClose -> {
                        return@channelFlow
                    }

                    ConnectAttempt.Established -> {
                        reconnectAttempt = 0
                        authFailureStreak = 0
                    }

                    ConnectAttempt.AuthFailed -> {
                        authFailureStreak++
                        send(SseEvent.Disconnected(DisconnectReason.Auth))
                        if (parkOnAuthExhaustion) {
                            // The FIRST 401/403 stays transient: the bearer plugin refreshes the token
                            // on the next attempt. Reaching authExhaustedThreshold consecutive failures
                            // proves the refresh cannot mint a working token — report ONCE and fall back
                            // to a slow heartbeat. reconnectNow() wakes the park instantly (never stranded).
                            if (authFailureStreak == authExhaustedThreshold) onAuthExhausted()
                            if (authFailureStreak >= authExhaustedThreshold) parkedWait() else backoffWait()
                        } else {
                            backoffWait()
                        }
                    }

                    ConnectAttempt.Reconnect -> {
                        send(SseEvent.Disconnected(DisconnectReason.Transport))
                        backoffWait()
                    }
                }
            }
        }

    /**
     * Wait out the current backoff, returning early if [reconnectNow] signals. Computes the delay from
     * the current attempt, increments, then waits — so a [reconnectNow] (which resets the attempt to 0)
     * shortens this wait and the next.
     */
    private suspend fun backoffWait() {
        val delayMs = reconnectDelayMillis(reconnectAttempt)
        logger.debug { "SSE reconnect in ${delayMs}ms (attempt $reconnectAttempt)" }
        reconnectAttempt++
        withTimeoutOrNull(delayMs) { wakeSignal.receive() }
    }

    /** Auth-parked heartbeat: wait [parkedDelayMillis], returning early if [reconnectNow] signals. */
    private suspend fun parkedWait() {
        logger.debug { "SSE auth-parked; next probe in ${parkedDelayMillis}ms" }
        withTimeoutOrNull(parkedDelayMillis) { wakeSignal.receive() }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun runOnce(emit: suspend (SseEvent) -> Unit): ConnectAttempt {
        val url = urlProvider() ?: return ConnectAttempt.GracefulClose
        return try {
            val request =
                streamingClientProvider().prepareGet(url) {
                    resumeIdProvider()?.let { header(HttpHeaders.LastEventID, it.toString()) }
                }
            connectBounded(request, emit)
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
     * Runs one connection attempt, bounding only the *connect* phase: if the server hasn't produced a
     * response within [connectTimeoutMillis], the attempt is abandoned and mapped to
     * [ConnectAttempt.Reconnect] so the outer loop can back off and retry. Once connected, the
     * streaming read ([streamFrames]) runs unbounded — a live-but-idle SSE stream must never be torn
     * down, and servers heartbeat well within any read window.
     *
     * Without this bound, a connect against a down/unreachable server hangs forever on engines with no
     * finite connect timeout (Darwin/URLSession): [runOnce] never returns and every downstream
     * recovery mechanism stays wedged.
     */
    private suspend fun connectBounded(
        request: HttpStatement,
        emit: suspend (SseEvent) -> Unit,
    ): ConnectAttempt =
        coroutineScope {
            val connected = CompletableDeferred<Unit>()
            val reader =
                async {
                    request.execute { response ->
                        connected.complete(Unit)
                        streamFrames(response, emit)
                    }
                }
            if (withTimeoutOrNull(connectTimeoutMillis) { connected.await() } == null) {
                reader.cancel()
                ConnectAttempt.Reconnect
            } else {
                reader.await()
            }
        }

    /** Read and dispatch SSE frames until the stream closes. Returns [ConnectAttempt.Established] on EOF. */
    private suspend fun streamFrames(
        response: HttpResponse,
        emit: suspend (SseEvent) -> Unit,
    ): ConnectAttempt {
        emit(SseEvent.Connected)
        val channel = response.bodyAsChannel()
        val buffer = StringBuilder()
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break
            if (line.isEmpty()) {
                // Append an empty trailing line so parseSseStream commits the frame.
                val parsed = parseSseStream(buffer.lineSequence().plus(""))
                for (frame in parsed) emit(SseEvent.Frame(frame))
                buffer.clear()
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
            }
        }
        return ConnectAttempt.Established
    }

    private enum class ConnectAttempt { Established, Reconnect, AuthFailed, GracefulClose }
}
