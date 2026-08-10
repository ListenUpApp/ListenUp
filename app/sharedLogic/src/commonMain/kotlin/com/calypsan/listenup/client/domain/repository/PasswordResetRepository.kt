@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Client side of the admin-approval password-reset flow.
 *
 * Opening a reset mints a device claim that is retained locally and required again to complete —
 * the pairing is what binds the reset to this install rather than to whoever learns the code.
 * Both the claim and the ticket id survive the app being killed, so a user who closes the app
 * while waiting and returns later with the code (by phone, an hour on) can still finish.
 */
interface PasswordResetRepository {
    /**
     * Opens a reset request for [email]. Mints and retains a device claim locally — the same
     * value is required again by [completeReset], which is what binds the reset to this install.
     *
     * Always succeeds, whether or not an account exists for [email] — the server's response shape
     * is indistinguishable either way, so this call must never become an account-existence oracle.
     */
    suspend fun requestReset(email: String): AppResult<PasswordResetTicket>

    /**
     * Watches the request identified by [ticketId]. Emits the current status, then live updates,
     * and completes honestly once the status turns terminal — completion is the signal, not a
     * dropped connection to reconnect.
     *
     * @throws Exception if the underlying stream transport genuinely fails. An unrecognised
     *   [ticketId] is deliberately **not** a business failure here — the server emits `PENDING`
     *   then completes as `EXPIRED`, indistinguishable from a real request nobody has approved
     *   yet, so this call can never become an account-existence oracle.
     */
    fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent>

    /**
     * One-shot pull of the request's current status — the first emission of the same RPC watch
     * [observeStatus] rides.
     *
     * The "never stranded" fallback for a "Check Status" action or an on-entry check: never
     * throws — any transport/parse failure resolves to null, leaving the caller free to retry or
     * fall back to [observeStatus] rather than crash.
     */
    suspend fun fetchStatus(ticketId: String): PasswordResetStatusEvent?

    /**
     * The ticket id of a request this install left in flight, or null.
     *
     * Lets the flow resume after the app is killed — the "never stranded" path where someone
     * closes the app while waiting and returns later with the code.
     */
    suspend fun resumableTicketId(): String?

    /**
     * Completes an approved reset using the retained device claim and the admin-supplied [code].
     * On success, both the claim and the ticket id are cleared from local storage. On failure —
     * including a simply-wrong [code] — both are retained, so a retry with the correct code can
     * still complete.
     */
    suspend fun completeReset(
        ticketId: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit>

    /**
     * Resets root's password against the one-time [token] a server operator arms via
     * `LISTENUP_ROOT_RESET`. No device claim is involved — root has no admin above them, so there
     * is no request to open in the first place.
     */
    suspend fun resetRootPassword(
        token: String,
        newPassword: String,
    ): AppResult<Unit>
}
