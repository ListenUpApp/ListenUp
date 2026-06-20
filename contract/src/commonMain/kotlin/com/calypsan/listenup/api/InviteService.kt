package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Admin-only invite management. Every method requires ROOT/ADMIN; non-admins
 * receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
 *
 * Covers the administrative lifecycle of an invite — minting a code for a
 * specific email/role, listing outstanding invites with their derived
 * [com.calypsan.listenup.api.dto.invite.InviteStatus], and revoking one before
 * it is claimed. The anonymous landing-page and claim surface lives on
 * [InviteServicePublic].
 *
 * REST mirrors are defined in
 * `InviteResources`.
 */
@Rpc
interface InviteService {
    /**
     * Mints a new invite for [email] with the given [displayName] and [role].
     * [expiresInDays] overrides the instance default expiry when non-null.
     * Returns the full [InviteDto] including the generated code.
     */
    suspend fun createInvite(
        email: String,
        displayName: String,
        role: UserRole,
        expiresInDays: Int? = null,
    ): AppResult<InviteDto>

    /** Returns every invite on the instance, each tagged with its derived status. */
    suspend fun listInvites(): AppResult<List<InviteSummary>>

    /** Revokes the unclaimed invite identified by [id]. Fails when no such invite exists. */
    suspend fun revokeInvite(id: InviteId): AppResult<Unit>
}
