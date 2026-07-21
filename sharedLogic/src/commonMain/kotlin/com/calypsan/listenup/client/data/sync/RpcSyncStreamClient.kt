@file:OptIn(ExperimentalTime::class, FlowPreview::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.data.remote.RpcChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

private const val FRAME_BUFFER_CAPACITY = 256

/**
 * The production sync-firehose client — [SyncStreamClient] over the kotlinx.rpc
 * [SyncStreamService] stream, riding the same WebSocket as every unary RPC. This is the ONLY
 * firehose transport.
 *
 * One subscription loop per [connect]: `Connecting` → subscribe
 * `observeEvents(currentLastEventId())` → latch `Connected` on the server's hello frame (the
 * stream-open [SyncControl.Heartbeat]) → re-broadcast every data/control frame on the hot
 * [frames] bus. Any termination — a typed [RpcEvent.Error], normal completion, or the read-idle
 * watchdog tripping — folds to `Disconnected` and re-subscribes after exponential backoff
 * (1s → 60s, ×2), resuming from the advanced cursor. [reconnectNow] wakes the backoff wait
 * immediately and resets the ladder.
 *
 * Heartbeat frames (CONTROL frames whose body decodes to [SyncControl.Heartbeat]) are swallowed
 * here: their only job is liveness, which the watchdog consumes upstream of the swallow —
 * forwarding them would wake the dispatcher's collector every 25s for a guaranteed no-op. Every
 * other CONTROL frame is forwarded untouched; control decoding is the dispatcher's job.
 *
 * `lastEventId` advances from [SyncFrame.revision] only AFTER `frames.emit` returns: a
 * cancellation while emit is suspended (the bounded bus can suspend) must not move the resume
 * cursor past a frame the collector never received.
 *
 * Auth handling is layered, not local: [RpcChannel.stream] heals a handshake 401 (refresh +
 * one resubscribe) before the first event; a confirmed-dead session surfaces as a typed
 * [AuthError] and the engine's auth gate parks this client via [disconnect] on SessionLapsed.
 */
internal class RpcSyncStreamClient(
    private val channel: RpcChannel<SyncStreamService>,
    private val state: SyncEngineState,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    // 3× the server's 25s heartbeat cadence: a live-but-idle stream is never torn down, a
    // half-open socket is caught within two missed heartbeats (see DEFAULT_READ_IDLE_TIMEOUT_MS).
    private val readIdleTimeoutMillis: Long = DEFAULT_READ_IDLE_TIMEOUT_MS,
    private val initialBackoffMillis: Long = INITIAL_RECONNECT_DELAY_MS,
    private val maxBackoffMillis: Long = MAX_RECONNECT_DELAY_MS,
    // Test seam: a small capacity lets a test force `frameBus.emit` to suspend deterministically
    // without depending on the production buffer depth.
    frameBufferCapacity: Int = FRAME_BUFFER_CAPACITY,
) : SyncStreamClient {
    private val frameBus =
        MutableSharedFlow<SyncFrame>(
            replay = 0,
            extraBufferCapacity = frameBufferCapacity,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /** Hot stream of firehose frames; consumers (the dispatcher) collect from here. */
    override val frames: SharedFlow<SyncFrame> = frameBus.asSharedFlow()

    private var connectionJob: Job? = null

    /** Highest revision seen this session; passed as `sinceRevision` on (re)subscribe. */
    private var lastEventId: Long? = null

    /** Conflated wake signal: [reconnectNow] sends; the backoff wait races it against the delay. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Reconnect-backoff attempt counter; reset by [reconnectNow], at each [connect], and by a
     * successful subscription. Deliberately unsynchronized best-effort state: the only
     * cross-coroutine race (a reset briefly unobserved by the loop) merely lengthens one backoff.
     */
    private var reconnectAttempt = 0

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
     * the orchestration site rather than hidden inside the stream client.
     */
    override suspend fun reseed(newLastEventId: Long?) {
        disconnect()
        lastEventId = newLastEventId
    }

    /** Open the firehose subscription loop (or no-op if one is already active). */
    override fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch { subscribeLoop() }
    }

    /** Close the firehose subscription and stop the reconnect loop. */
    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        state.setConnection(ConnectionState.Disconnected("closed"))
    }

    override fun reconnectNow() {
        reconnectAttempt = 0
        wakeSignal.trySend(Unit)
    }

    /** Subscribe, stream until the subscription ends, back off, repeat — until cancelled. */
    private suspend fun subscribeLoop() {
        reconnectAttempt = 0
        while (currentCoroutineContext().isActive) {
            state.setConnection(ConnectionState.Connecting)
            val reason = streamOnce()
            state.setConnection(ConnectionState.Disconnected(reason))
            state.recordError(SyncError.RealtimeDisconnected())
            backoffWait()
        }
    }

    /**
     * One subscription: collect until the stream ends, returning the disconnect reason.
     * The read-idle watchdog rides [timeout] UPSTREAM of the heartbeat swallow, so every
     * received frame — heartbeat or data — proves the socket alive and resets the window;
     * silence past the bound means a half-open connection and trips a reconnect.
     */
    private suspend fun streamOnce(): String {
        var reason = REASON_RECONNECTING
        try {
            var latched = false
            channel
                .stream { it.observeEvents(lastEventId) }
                .timeout(readIdleTimeoutMillis.milliseconds)
                .collect { event ->
                    when (event) {
                        is RpcEvent.Data -> {
                            if (!latched) {
                                // The hello frame (or any first frame): the stream is live.
                                latched = true
                                reconnectAttempt = 0
                                state.setConnection(ConnectionState.Connected(lastEventId))
                                state.recordSuccess(nowMillis())
                            }
                            apply(event.value)
                        }

                        is RpcEvent.Error -> {
                            // Typed drop (transport fault folded by RpcChannel.stream, or a
                            // server-side error). The stream completes right after; classify
                            // the reason now so the Disconnected state is honest about auth.
                            logger.warn { "Firehose stream error: ${event.error.code}" }
                            reason = if (event.error is AuthError) REASON_AUTH else REASON_RECONNECTING
                        }

                        RpcEvent.Complete -> {
                            // Explicit terminal marker (e.g. after CursorStale) — treat like
                            // normal completion; the loop reconnects with the current cursor.
                        }
                    }
                }
        } catch (e: TimeoutCancellationException) {
            // The watchdog's own bound tripped — NOT a caller cancellation. A genuinely
            // cancelled collector surfaces the outer job's CancellationException instead,
            // which propagates past this catch to tear the loop down.
            logger.warn(e) {
                "Firehose silent for ${readIdleTimeoutMillis}ms (heartbeat missed) — " +
                    "treating as half-open and resubscribing"
            }
        }
        return reason
    }

    /**
     * Re-broadcast one frame, swallowing heartbeats. Ordering is load-bearing: emit hands the
     * frame to the buffered collector first; only then does [lastEventId] advance from
     * [SyncFrame.revision] (see the class KDoc's cancellation-guard contract).
     */
    private suspend fun apply(frame: SyncFrame) {
        if (isHeartbeat(frame)) return
        frameBus.emit(frame)
        frame.revision?.let { lastEventId = it }
        state.setConnection(ConnectionState.Connected(lastEventId))
    }

    /** Whether [frame] is a liveness-only heartbeat. Decodes CONTROL bodies with the contract Json. */
    private fun isHeartbeat(frame: SyncFrame): Boolean {
        if (frame.domain != SyncFrame.CONTROL) return false
        return try {
            contractJson.decodeFromString(SyncControl.serializer(), frame.json) is SyncControl.Heartbeat
        } catch (e: IllegalArgumentException) {
            // Undecodable control — forward it; the dispatcher owns compat reporting.
            logger.debug(e) { "Undecodable CONTROL frame; forwarding to the dispatcher" }
            false
        }
    }

    /**
     * Wait out the current backoff, returning early if [reconnectNow] signals. Computes the delay
     * from the current attempt, increments, then waits — so a [reconnectNow] (which resets the
     * attempt to 0) shortens this wait and the next.
     */
    private suspend fun backoffWait() {
        val delayMs = backoffDelayMillis(reconnectAttempt)
        logger.debug { "Firehose resubscribe in ${delayMs}ms (attempt $reconnectAttempt)" }
        reconnectAttempt++
        withTimeoutOrNull(delayMs) { wakeSignal.receive() }
    }

    /** 1s, 2s, 4s, … capped at [maxBackoffMillis], parameterized for tests. */
    private fun backoffDelayMillis(attempt: Int): Long {
        val raw =
            (
                initialBackoffMillis.toDouble() *
                    RECONNECT_BACKOFF_MULTIPLIER.pow(attempt.toDouble())
            ).toLong()
        return raw.coerceAtMost(maxBackoffMillis)
    }

    private companion object {
        const val REASON_RECONNECTING = "reconnecting"
        const val REASON_AUTH = "auth"
    }
}
