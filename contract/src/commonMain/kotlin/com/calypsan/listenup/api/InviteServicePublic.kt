package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Anonymous invite surface — landing-page lookup + claim. Mounted at
 * `/api/rpc/public` (RPC) and under `/api/v1/invites/{code}` (REST).
 *
 * Like [AuthServicePublic], every method returns [AppResult] — failures are
 * typed [com.calypsan.listenup.api.error.AuthError] values, not thrown
 * exceptions, so they survive both transports as in-band data.
 *
 * REST mirrors are defined in
 * [com.calypsan.listenup.api.resources.InviteResources].
 */
@Rpc
interface InviteServicePublic {
    /** Landing-page details for a code (no auth). Reveals only what the join page needs. */
    suspend fun lookupInvite(code: String): AppResult<InvitePreview>

    /** Claim a code: create the ACTIVE account + issue a session, exactly like register. */
    suspend fun claimInvite(
        code: String,
        password: String,
        displayName: String? = null,
    ): AppResult<AuthSession>
}
