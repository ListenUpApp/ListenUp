package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId

/**
 * The decided outcome of a [ShareTarget] against the currently-connected server.
 *
 * [ShareTargetResolver.resolve] produces the instanceId-decidable outcomes — [OpenBook],
 * [OpenInviteClaim], [WrongServer], [NotConnected]. [NoAccess] is reached **downstream**, at
 * open time, when the connected server reports the book is not accessible: that check is
 * server-side (`BookAccessPolicy`), so it cannot be decided by the pure resolver. The platform
 * routing layer renders the user-facing message for each outcome (strings added in Phases 2–3).
 */
sealed interface ShareResolution {
    /** The book is on the connected server — open Book Detail (fetch-on-demand if not yet synced). */
    data class OpenBook(
        val bookId: BookId,
    ) : ShareResolution

    /** A join link — present the invite-claim flow for [serverUrl] / [code]. */
    data class OpenInviteClaim(
        val serverUrl: String,
        val code: String,
    ) : ShareResolution

    /** The link points at a different ListenUp server than the one the user is signed in to. */
    data class WrongServer(
        val serverUrl: String?,
    ) : ShareResolution

    /** No server is connected — the user must sign in to the host server first. */
    data class NotConnected(
        val serverUrl: String?,
    ) : ShareResolution

    /** Same server, but the user is not permitted to see this book (decided at open time). */
    data object NoAccess : ShareResolution
}
