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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * RPC implementation of [PasswordResetRepository], riding the public-channel
 * `AuthServicePublic` reset surface.
 *
 * The device claim is two concatenated [Uuid.random] values. Each v4 UUID has 6 structurally
 * fixed bits (4 version + 2 variant), leaving 122 bits of randomness per value — **244 bits
 * total, not 256**. [Uuid.random]'s own KDoc explicitly disclaims cryptographic use ("not
 * recommended for use for cryptographic purposes... partially predictable bit pattern... at most
 * 122 bits of entropy"); that disclaimer is about the fixed version/variant bits, not the
 * underlying generator, which is a real CSPRNG on every target this app ships — JVM
 * `SecureRandom`, JS/Wasm `crypto.getRandomValues`, Kotlin/Native `getrandom`/`arc4random_buf`.
 * Adding a dedicated cryptography dependency for this one secret is not worth the dependency
 * edge, but this reasoning is specific to a device claim: it does not generalize, and a future
 * reader reaching for [Uuid.random] as a bearer secret elsewhere should re-derive it rather than
 * cite this comment as precedent.
 *
 * It is persisted through [secureStorage] so the flow survives the app being killed while waiting
 * for the admin — the "never stranded" path where the user closes the app, gets the code by phone
 * an hour later, and comes back to a cold start.
 *
 * [requestMutex] serializes [requestReset] and [completeReset]: both are read-then-write-two-keys
 * sequences against [secureStorage], and an overlapping pair (e.g. a double-tapped submit) could
 * otherwise interleave those writes into the same desync a failed request already had to guard
 * against. See [AuthSessionStore]'s `credentialMutex` for the precedent this follows.
 */
internal class PasswordResetRepositoryImpl(
    private val channel: RpcChannel<AuthServicePublic>,
    private val secureStorage: SecureStorage,
) : PasswordResetRepository {
    private val requestMutex = Mutex()

    override suspend fun requestReset(email: String): AppResult<PasswordResetTicket> =
        requestMutex.withLock {
            val claim = "${Uuid.random()}${Uuid.random()}"
            secureStorage.save(CLAIM_KEY, claim)
            // A new claim invalidates any ticket retained from a prior attempt: that ticket's
            // server record is paired with the OLD claim, which is now gone, so resuming it would
            // send a claim the server never saw and could never succeed. Dropping it here — before
            // we know whether this attempt even succeeds — leaves resumableTicketId() honestly
            // null ("start again") rather than stranding a ticket that can never complete. A
            // success below immediately re-establishes it against the fresh claim.
            secureStorage.delete(TICKET_KEY)
            channel.call { it.requestPasswordReset(email, claim) }.also { result ->
                // The ticket id is persisted alongside the claim, not just held in memory: the
                // never-stranded path is a user who closes the app while waiting, gets the code by
                // phone an hour later, and comes back. Without the id there is nothing to come
                // back to.
                if (result is AppResult.Success) secureStorage.save(TICKET_KEY, result.data.ticketId)
            }
        }

    /**
     * Mirrors [RegistrationStatusStreamImpl.streamStatus] exactly: [RpcEvent.Error] is thrown
     * (rather than silently dropped) as [PasswordResetStatusStreamFailure], so a genuine
     * transport/stream fault is never mistaken for "still pending". Unlike the registration watch
     * this mirrors, an unrecognised ticket is **not** a business failure here — per
     * `AuthServicePublic.observePasswordResetStatus`'s contract it emits `PENDING` then completes
     * as `EXPIRED`, deliberately indistinguishable from a real unapproved request, so this stream
     * never becomes an account-existence oracle. The only realistic [RpcEvent.Error] source is a
     * transport/stream fault.
     *
     * The server's underlying watch is a fixed-interval poll, not distinct-until-changed — it
     * re-emits the current status every tick for as long as it stays `PENDING` or `APPROVED`, so
     * a subscriber otherwise sees the same status repeatedly. [distinctUntilChangedBy] collapses
     * those repeats by [PasswordResetStatusEvent.status] only (not the whole event — `expiresAt`
     * is a fixed value from the ticket's issue time and never actually varies here, but comparing
     * the whole event would be fragile if that ever changed). This is defence in depth, not the
     * fix for a downstream consumer re-synthesizing state on every tick: dedup is scoped to a
     * single subscription, so the first event after a stream retry/reconnect is not deduped
     * against anything the previous subscription saw.
     */
    override fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent> =
        channel
            .stream { it.observePasswordResetStatus(ticketId) }
            .transformWhile { event ->
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
            }.distinctUntilChangedBy { it.status }

    /** Mirrors [RegistrationStatusStreamImpl.fetchStatus]: the first emission of [observeStatus]'s watch, never throwing. */
    override suspend fun fetchStatus(ticketId: String): PasswordResetStatusEvent? =
        when (val first = channel.stream { it.observePasswordResetStatus(ticketId) }.firstOrNull()) {
            is RpcEvent.Data -> first.value
            else -> null
        }

    override suspend fun resumableTicketId(): String? = secureStorage.read(TICKET_KEY)

    override suspend fun abandonPendingRequest() =
        requestMutex.withLock {
            secureStorage.delete(CLAIM_KEY)
            secureStorage.delete(TICKET_KEY)
        }

    override suspend fun completeReset(
        ticketId: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit> =
        requestMutex.withLock {
            val claim =
                secureStorage.read(CLAIM_KEY)
                    ?: return AppResult.Failure(AuthError.ResetRequestNotFound())
            channel.call { it.completePasswordReset(ticketId, claim, code, newPassword) }.also { result ->
                // Only a SUCCESS clears the retained state. On failure — including a simply-wrong
                // code — both keys are deliberately left in place so a retry with the correct code
                // can still complete; clearing unconditionally here would strand the user exactly
                // like the bug this file's requestReset fix addresses.
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
