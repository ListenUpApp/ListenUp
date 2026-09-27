package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * RPC implementation of [HardcoverRepository] over [HardcoverService].
 *
 * The connection watch is infinite by contract, so it resubscribes exactly as
 * [RegistrationPolicyStreamImpl] does: an [RpcEvent.Error] or completion is never surfaced as
 * termination, the loop backs off (capped, reset by any live emission) and resubscribes, and the
 * fresh subscription's current-value emit recovers whatever changed while disconnected — including
 * a device sign-in the server finished while the socket was down.
 */
internal class HardcoverRepositoryImpl(
    private val channel: RpcChannel<HardcoverService>,
) : HardcoverRepository {
    override fun observeConnection(): Flow<HardcoverConnection> =
        flow {
            var backoffMs = INITIAL_RESUBSCRIBE_DELAY_MS
            while (true) {
                channel.stream { it.observeConnection() }.collect { event ->
                    when (event) {
                        is RpcEvent.Data -> {
                            // A live emission proves the watch is healthy — reset the backoff so
                            // the next drop reconnects promptly.
                            backoffMs = INITIAL_RESUBSCRIBE_DELAY_MS
                            emit(event.value)
                        }

                        // Transport faults arrive as one Error then completion (see
                        // RpcChannel.stream); the outer loop resubscribes either way.
                        is RpcEvent.Error -> {
                            logger.warn { "Hardcover connection watch errored (${event.error.code}); resubscribing" }
                        }

                        is RpcEvent.Complete -> {
                            Unit
                        }
                    }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RESUBSCRIBE_DELAY_MS)
            }
        }

    // Not idempotent: starting again replaces the pending code, so a blind re-fire after a lost
    // response could invalidate the code the user is already entering.
    override suspend fun startLink(): AppResult<HardcoverLinkPrompt> =
        channel.call(idempotent = false) { it.startLink() }

    override suspend fun disconnect(): AppResult<Unit> = channel.call(idempotent = true) { it.disconnect() }
}
