package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Consecutive transport failures before calls start running under [OPEN_CIRCUIT_BOUND]. Two, not
 * one: a single blip must not degrade every subsequent call.
 */
internal const val CONSECUTIVE_FAILURES_TO_OPEN = 2

/**
 * Bound applied while the breaker is open. Generous next to a healthy LAN round-trip (~20ms) so an
 * ordinary call still succeeds and closes the breaker, but a small fraction of the 15s default.
 */
internal val OPEN_CIRCUIT_BOUND = 2.seconds

/** While open, every Nth call goes out at the caller's full bound — see `ConnectionEvidence.boundFor`. */
internal const val PROBE_EVERY = 5

/**
 * Process-wide sink for server-reachability evidence observed by the transports.
 *
 * Every real interaction with the server is proof about reachability RIGHT NOW — strictly more
 * truthful than any state inferred from a single long-lived connection. Unary RPC outcomes
 * ([recordOutcome], tapped at the [com.calypsan.listenup.client.data.remote.RpcChannel.call]
 * boundary) and the reconnection supervisor's reachability probes report here;
 * [ConnectionHealthStore] folds the LATEST evidence into
 * [com.calypsan.listenup.client.domain.model.ConnectionHealth], so a fresh success heals an
 * offline reading the instant it happens and a fresh network failure surfaces one.
 *
 * Stamps are a logical monotonic sequence, not wall-clock time: ordering must be exact even for
 * events landing in the same millisecond, and the stamps never leave the process.
 */
internal class ConnectionEvidence {
    private val clock = MutableStateFlow(0L)

    /** Logical stamp of the most recent proof the server answered (any response at all). */
    val lastUpAt: StateFlow<Long?>
        field = MutableStateFlow<Long?>(null)

    /** Logical stamp of the most recent network-class failure to reach the server. */
    val lastDownAt: StateFlow<Long?>
        field = MutableStateFlow<Long?>(null)

    /**
     * Consecutive transport-class failures with no answer in between. Drives [boundFor]; see the
     * class KDoc for why evidence and gating live together.
     */
    private val consecutiveFailures = atomic(0)

    /** Calls issued while the breaker is open — paces the full-bound probe in [boundFor]. */
    private val callsWhileOpen = atomic(0)

    /** Record proof the server answered. */
    fun reportUp() {
        consecutiveFailures.value = 0
        callsWhileOpen.value = 0
        lastUpAt.value = clock.updateAndGet { it + 1 }
    }

    /** Record a network-class failure to reach the server. */
    fun reportDown() {
        consecutiveFailures.incrementAndGet()
        lastDownAt.value = clock.updateAndGet { it + 1 }
    }

    /**
     * The timeout a call should actually run under, given what the transport has just proved.
     *
     * Evidence used to be write-only: the engine recorded that the socket was dead and then
     * committed the next caller to the full 15s bound anyway. That is where the 2026-08-07 resume
     * stall got its length — the firehose watchdog had ALREADY declared the connection half-open
     * before the listener ever tapped play.
     *
     * Two invariants matter here:
     * - **Never lengthen.** A caller that asked for less than [OPEN_CIRCUIT_BOUND] asked for a
     *   reason — the resume fetch's 800ms is a latency budget on the path to audio.
     * - **Never refuse.** Calls still go out, just bounded shorter, so an ordinary call doubles as
     *   the recovery probe: the server answering inside the short bound closes the breaker via
     *   [reportUp]. The one case that needs help is a link too slow to answer inside the short
     *   bound at all, so every [PROBE_EVERY]th call goes out at the caller's full bound rather than
     *   letting the breaker latch open forever.
     */
    fun boundFor(requested: Duration): Duration {
        if (consecutiveFailures.value < CONSECUTIVE_FAILURES_TO_OPEN) return requested
        // incrementAndGet, not getAndIncrement: the FIRST call after opening must be short-bounded.
        // Handing it the probe slot would waste the full bound on exactly the call the breaker
        // exists to protect — the next one after we just learned the socket is dead.
        val isProbe = callsWhileOpen.incrementAndGet() % PROBE_EVERY == 0
        return if (isProbe) requested else minOf(requested, OPEN_CIRCUIT_BOUND)
    }

    /**
     * Classify one unary RPC outcome into evidence. ANY response — success, a typed domain
     * failure, an auth rejection, even a 5xx — proves the server is reachable; only the
     * transport-class failures (couldn't connect, timed out, connection dropped mid-call) are
     * evidence it is not.
     */
    fun recordOutcome(result: AppResult<*>) {
        when (result) {
            is AppResult.Success -> {
                reportUp()
            }

            is AppResult.Failure -> {
                when (result.error) {
                    is TransportError.NetworkUnavailable,
                    is TransportError.Timeout,
                    is TransportError.OutcomeUnknown,
                    -> reportDown()

                    else -> reportUp()
                }
            }
        }
    }
}
