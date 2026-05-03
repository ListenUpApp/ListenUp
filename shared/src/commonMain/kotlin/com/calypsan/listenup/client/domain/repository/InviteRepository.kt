package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.model.User

/**
 * Public invite operations. The server's invite surface is REST-only —
 * the kotlinx.rpc auth contract does not yet expose invites — so this
 * port stays exception-shaped pending a contract addition.
 *
 * Implementations persist auth tokens internally on a successful claim
 * (same shape as the auth use cases): callers receive the resulting
 * [User] and the AuthState transitions automatically.
 */
interface InviteRepository {
    /**
     * Fetch invite details for the registration screen.
     *
     * @throws Exception on network errors or invalid code
     */
    suspend fun getInviteDetails(
        serverUrl: String,
        code: String,
    ): InviteDetails

    /**
     * Claim an invite by creating the new account.
     *
     * On success, auth tokens are persisted via the injected `AuthSession`
     * (flipping `AuthState` to `Authenticated`) and the new user is saved
     * locally. The returned [User] is the same value just persisted.
     *
     * @throws Exception on network errors or invalid/expired invite
     */
    suspend fun claimInvite(
        serverUrl: String,
        code: String,
        password: String,
    ): User
}
