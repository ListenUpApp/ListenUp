package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/**
 * RPC implementation of [RegistrationStatusStream], riding the public-channel
 * `AuthServicePublic.observeRegistrationStatus` watch: a flow that emits the registrant's current
 * status, then live updates, and COMPLETES the moment the status turns terminal
 * (approved/denied) — the completion IS the signal, never a dropped connection to reconnect.
 *
 * [fetchStatus] and [streamStatus] both ride the same watch: [fetchStatus] just takes its first
 * emission and lets the subscription cancel, giving the "never stranded" one-shot pull without a
 * second transport.
 */
internal class RegistrationStatusStreamImpl(
    private val channel: RpcChannel<AuthServicePublic>,
) : RegistrationStatusStream {
    override fun streamStatus(userId: String): Flow<StreamedRegistrationStatus> =
        channel.stream { it.observeRegistrationStatus(userId) }.map { it.toStreamedStatus() }

    override suspend fun fetchStatus(userId: String): StreamedRegistrationStatus =
        when (val first = channel.stream { it.observeRegistrationStatus(userId) }.firstOrNull()) {
            is RpcEvent.Data -> first.value.toDomain()

            // Never-stranded: an error, an unexpected empty completion, or a business RpcEvent.Error
            // (e.g. an unrecognised registration id) all fall back to Pending rather than throwing —
            // this is the reliable pull the ViewModel's poll and "Check Status" action lean on.
            else -> StreamedRegistrationStatus.Pending
        }

    /**
     * [RpcEvent.Error] is re-thrown (rather than silently dropped, as [streamStatus] consumers
     * elsewhere in the codebase do) so the ViewModel's existing catch-and-retry-then-poll fallback
     * fires — a typed business failure (e.g. an unrecognised registration id) must not look like a
     * silent "still pending".
     */
    private fun RpcEvent<RegistrationStatusEvent>.toStreamedStatus(): StreamedRegistrationStatus =
        when (this) {
            is RpcEvent.Data -> value.toDomain()
            is RpcEvent.Error -> throw RegistrationStatusStreamFailure(error)
            is RpcEvent.Complete -> error("RpcEvent.Complete is not emitted by observeRegistrationStatus")
        }
}

private fun RegistrationStatusEvent.toDomain(): StreamedRegistrationStatus =
    when (status) {
        "approved" -> StreamedRegistrationStatus.Approved
        "denied" -> StreamedRegistrationStatus.Denied(message)
        else -> StreamedRegistrationStatus.Pending
    }

/** Wraps a server-surfaced [RpcEvent.Error] as a thrown failure at the [RegistrationStatusStream] boundary. */
internal class RegistrationStatusStreamFailure(
    val error: AppError,
) : Exception(error.message)
