package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.core.SecureStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.uuid.Uuid

/**
 * RPC implementation of [PasswordResetRepository], riding the public-channel
 * `AuthServicePublic` reset surface.
 *
 * The device claim is two concatenated [Uuid.random] values — 256 bits from the stdlib's secure
 * RNG, the same idiom already used for client-minted sync ids. Adding a cryptography dependency
 * to the client for this one secret is not worth the dependency edge.
 *
 * It is persisted through [secureStorage] so the flow survives the app being killed while waiting
 * for the admin — the "never stranded" path where the user closes the app, gets the code by phone
 * an hour later, and comes back to a cold start.
 */
internal class PasswordResetRepositoryImpl(
    private val channel: RpcChannel<AuthServicePublic>,
    private val secureStorage: SecureStorage,
) : PasswordResetRepository {
    override suspend fun requestReset(email: String): AppResult<PasswordResetTicket> {
        val claim = "${Uuid.random()}${Uuid.random()}"
        secureStorage.save(CLAIM_KEY, claim)
        return channel.call { it.requestPasswordReset(email, claim) }.also { result ->
            // The ticket id is persisted alongside the claim, not just held in memory: the
            // never-stranded path is a user who closes the app while waiting, gets the code by
            // phone an hour later, and comes back. Without the id there is nothing to come back to.
            if (result is AppResult.Success) secureStorage.save(TICKET_KEY, result.data.ticketId)
        }
    }

    /**
     * Mirrors [RegistrationStatusStreamImpl.streamStatus] exactly: [RpcEvent.Error] is thrown
     * (rather than silently dropped) as [PasswordResetStatusStreamFailure], so a real business
     * failure (e.g. an unrecognised ticket) can never be mistaken for "still pending".
     */
    override fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent> =
        channel.stream { it.observePasswordResetStatus(ticketId) }.transformWhile { event ->
            when (event) {
                is RpcEvent.Data -> {
                    emit(event.value)
                    true
                }

                is RpcEvent.Error -> {
                    throw PasswordResetStatusStreamFailure(event.error)
                }

                // Explicit terminal marker: honestly complete the stream. Per RpcEvent's KDoc this
                // is an "optional explicit terminal marker" — collection completion is the signal.
                is RpcEvent.Complete -> {
                    false
                }
            }
        }

    override suspend fun resumableTicketId(): String? = secureStorage.read(TICKET_KEY)

    override suspend fun completeReset(
        ticketId: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit> {
        val claim =
            secureStorage.read(CLAIM_KEY)
                ?: return AppResult.Failure(AuthError.ResetRequestNotFound())
        return channel.call { it.completePasswordReset(ticketId, claim, code, newPassword) }.also { result ->
            if (result is AppResult.Success) {
                secureStorage.delete(CLAIM_KEY)
                secureStorage.delete(TICKET_KEY)
            }
        }
    }

    override suspend fun resetRootPassword(
        token: String,
        newPassword: String,
    ): AppResult<Unit> = channel.call { it.resetRootPassword(token, newPassword) }

    companion object {
        /** Secure-storage key for the pending device claim. */
        const val CLAIM_KEY = "password_reset_device_claim"

        /** Secure-storage key for the in-flight ticket id, so a cold start can resume. */
        const val TICKET_KEY = "password_reset_ticket_id"
    }
}

/** Wraps a server-surfaced [RpcEvent.Error] as a thrown failure at the [PasswordResetRepository] boundary. */
internal class PasswordResetStatusStreamFailure(
    val error: AppError,
) : Exception(error.message)
