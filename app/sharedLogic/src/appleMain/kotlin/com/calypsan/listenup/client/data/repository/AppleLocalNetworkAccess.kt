@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.repository.LocalNetworkAccess
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_current_path
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_t
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create
import platform.Network.nw_path_get_unsatisfied_reason
import platform.Network.nw_path_unsatisfied_reason_local_network_denied
import platform.Network.nw_path_unsatisfied_reason_t
import platform.Network.nw_protocol_stack_set_transport_protocol
import platform.Network.nw_tcp_create_options
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume

private val logger = KotlinLogging.logger {}

/**
 * How long the probe may take to hear the system's verdict. Local Network privacy answers
 * almost at once — the path is evaluated before any packet leaves — so this bounds only the
 * unhappy case of a probe that neither connects nor waits.
 */
private const val PROBE_TIMEOUT_MS = 2_000L

/**
 * What one state of the probe connection says about the local-network gate: `true` when the
 * connection is waiting because Local Network access was denied, `false` for any other settled
 * state, and `null` while it is still being set up.
 *
 * Apple TN3179 ("Understanding local network privacy") documents this as the way to detect the
 * denial: the connection enters `.waiting` and the path's unsatisfied reason is
 * `nw_path_unsatisfied_reason_local_network_denied`. A connection waiting for any other reason
 * (nothing listening, no route) is simply not the gate's doing.
 */
internal fun localNetworkVerdict(
    state: nw_connection_state_t,
    unsatisfiedReason: nw_path_unsatisfied_reason_t?,
): Boolean? =
    when (state) {
        nw_connection_state_waiting -> unsatisfiedReason == nw_path_unsatisfied_reason_local_network_denied
        nw_connection_state_ready, nw_connection_state_failed, nw_connection_state_cancelled -> false
        else -> null
    }

/**
 * iOS's [LocalNetworkAccess]: a short-lived TCP `NWConnection` probe to the host, per Apple
 * TN3179. There is no API to read the Local Network permission, but the system tells a
 * connection it blocked exactly why. The probe is cancelled as soon as the verdict is in (or
 * after [PROBE_TIMEOUT_MS]); it never sends a byte.
 *
 * No address classification here: iOS decides which destinations the privacy gate covers, and
 * the probe asks it directly. The simulator does not enforce Local Network privacy, so this only
 * ever reports a denial on a physical device.
 */
class AppleLocalNetworkAccess : LocalNetworkAccess {
    private val probeQueue = dispatch_queue_create("com.calypsan.listenup.local-network-probe", null)

    override suspend fun isDeniedFor(
        host: String,
        port: Int,
    ): Boolean {
        val parameters = nw_parameters_create()
        nw_protocol_stack_set_transport_protocol(
            nw_parameters_copy_default_protocol_stack(parameters),
            nw_tcp_create_options(),
        )
        val connection = nw_connection_create(nw_endpoint_create_host(host, port.toString()), parameters)
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    // The handler runs on the serial probeQueue, so the isActive check and the
                    // resume cannot interleave with another state change.
                    nw_connection_set_state_changed_handler(connection) { state, _ ->
                        val reason =
                            if (state == nw_connection_state_waiting) {
                                nw_connection_copy_current_path(connection)?.let(::nw_path_get_unsatisfied_reason)
                            } else {
                                null
                            }
                        val verdict = localNetworkVerdict(state, reason)
                        if (verdict != null && continuation.isActive) continuation.resume(verdict)
                    }
                    nw_connection_set_queue(connection, probeQueue)
                    nw_connection_start(connection)
                }
            } ?: false
        } finally {
            nw_connection_cancel(connection)
        }.also { denied -> logger.debug { "Local network probe of $host:$port: denied=$denied" } }
    }
}
