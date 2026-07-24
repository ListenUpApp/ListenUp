@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.result.AppResult

/**
 * Contract-typed public invite operations over the `/api/rpc/public` surface —
 * the client side of [com.calypsan.listenup.api.InviteServicePublic].
 *
 * Both methods return [AppResult] so typed [com.calypsan.listenup.api.error.AppError]
 * values cross the boundary as in-band data rather than thrown exceptions.
 *
 * `claimInvite` is a register + login combined: on success the implementation
 * persists the issued session (lands the user logged-in), so callers only need
 * the returned [AuthSession] and the AuthState transitions automatically.
 */
interface InviteRepository {
    /**
     * Fetch the public landing-page preview for an invite [code].
     *
     * Returns [AppResult.Failure] carrying the typed error on transport failure.
     */
    suspend fun lookupInvite(code: String): AppResult<InvitePreview>

    /**
     * Claim an invite [code], creating the account and issuing a session.
     *
     * On success the issued tokens are persisted via the injected `AuthSession`
     * (flipping AuthState to Authenticated); the returned [AuthSession] is the
     * same value just persisted. [displayName] overrides the invite's default
     * when supplied.
     *
     * Returns [AppResult.Failure] carrying the typed error on transport failure.
     */
    suspend fun claimInvite(
        code: String,
        password: String,
        displayName: String?,
    ): AppResult<AuthSession>
}
