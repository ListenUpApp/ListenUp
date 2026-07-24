package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/** First resubscribe delay after a dropped/completed watch; doubles up to [MAX_RESUBSCRIBE_DELAY_MS]. */
internal const val INITIAL_RESUBSCRIBE_DELAY_MS = 1_000L

/** Resubscribe backoff ceiling — a long outage retries once a minute, never faster forever. */
internal const val MAX_RESUBSCRIBE_DELAY_MS = 60_000L

/**
 * RPC implementation of [RegistrationPolicyStream], riding the public-channel
 * `AuthServicePublic.observeRegistrationPolicy` watch. The server emits the current policy
 * immediately on subscribe, then every change.
 *
 * Unlike the terminal-completing registration-STATUS watch ([RegistrationStatusStreamImpl]), this
 * flow is INFINITE by contract — its consumer ([AuthSessionStore]) collects for as long as the
 * login screen shows. So a server-side [RpcEvent.Error] or completion is never surfaced as
 * termination: the loop resubscribes with capped exponential backoff, and the fresh subscription's
 * current-policy emit recovers anything missed while disconnected (never stranded).
 */
internal class RegistrationPolicyStreamImpl(
    private val channel: RpcChannel<AuthServicePublic>,
) : RegistrationPolicyStream {
    override fun streamPolicy(): Flow<RegistrationPolicy> =
        flow {
            var backoffMs = INITIAL_RESUBSCRIBE_DELAY_MS
            while (true) {
                channel.stream { it.observeRegistrationPolicy() }.collect { event ->
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
                            logger.warn { "Registration-policy watch errored (${event.error.code}); resubscribing" }
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
}
