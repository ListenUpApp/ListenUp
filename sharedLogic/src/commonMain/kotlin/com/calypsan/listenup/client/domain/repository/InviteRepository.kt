package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.model.User

/**
 * Public invite operations. The server's invite surface is REST-only —
 * the kotlinx.rpc auth contract does not yet expose invites — so this
 * port returns [AppResult] to propagate typed errors without throwing.
 *
 * Implementations persist auth tokens internally on a successful claim
 * (same shape as the auth use cases): callers receive the resulting
 * [User] and the AuthState transitions automatically.
 */
interface InviteRepository {
    /**
     * Fetch invite details for the registration screen.
     *
     * Returns [AppResult.Failure] carrying the typed error on network/envelope failure.
     */
    suspend fun getInviteDetails(
        serverUrl: String,
        code: String,
    ): AppResult<InviteDetails>

    /**
     * Claim an invite by creating the new account.
     *
     * On success, auth tokens are persisted via the injected `AuthSession`
     * (flipping `AuthState` to `Authenticated`) and the new user is saved
     * locally. The returned [User] is the same value just persisted.
     *
     * Returns [AppResult.Failure] carrying the typed error on network/envelope failure.
     */
    suspend fun claimInvite(
        serverUrl: String,
        code: String,
        password: String,
    ): AppResult<User>
}
