package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet

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

    /** Record proof the server answered. */
    fun reportUp() {
        lastUpAt.value = clock.updateAndGet { it + 1 }
    }

    /** Record a network-class failure to reach the server. */
    fun reportDown() {
        lastDownAt.value = clock.updateAndGet { it + 1 }
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
