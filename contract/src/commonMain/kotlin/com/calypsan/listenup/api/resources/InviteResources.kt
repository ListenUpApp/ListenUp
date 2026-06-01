package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.InviteService].
 * All routes live under `/api/v1/admin/invites` and require an admin (ROOT/ADMIN)
 * JWT; non-admin callers receive PermissionDenied.
 *
 * RPC is the first-class surface; these resources exist so the same operations
 * are reachable over plain REST for third-party integrations.
 */
@Resource("/api/v1/admin/invites")
class InviteResources {
    /**
     * REST mirror for the invite roster:
     * - `GET /api/v1/admin/invites` →
     *   [com.calypsan.listenup.api.InviteService.listInvites]
     * - `POST /api/v1/admin/invites` →
     *   [com.calypsan.listenup.api.InviteService.createInvite]
     */
    @Resource("")
    class List(
        val parent: InviteResources = InviteResources(),
    )

    /**
     * REST mirror for per-invite operations:
     * - `DELETE /api/v1/admin/invites/{id}` →
     *   [com.calypsan.listenup.api.InviteService.revokeInvite]
     */
    @Resource("{id}")
    class Detail(
        val parent: InviteResources = InviteResources(),
        /** Invite id string. */
        val id: String,
    )
}

/**
 * REST mirror of [com.calypsan.listenup.api.InviteServicePublic.lookupInvite] —
 * `GET /api/v1/invites/{code}` returns the anonymous [com.calypsan.listenup.api.dto.invite.InvitePreview]
 * landing-page details for a code. No auth required.
 */
@Resource("/api/v1/invites/{code}")
class InvitePreviewResource(
    /** The invite code being looked up. */
    val code: String,
) {
    /**
     * REST mirror of [com.calypsan.listenup.api.InviteServicePublic.claimInvite] —
     * `POST /api/v1/invites/{code}/claim` creates the ACTIVE account and issues a
     * session. No auth required.
     */
    @Resource("claim")
    class Claim(
        val parent: InvitePreviewResource,
    )
}
