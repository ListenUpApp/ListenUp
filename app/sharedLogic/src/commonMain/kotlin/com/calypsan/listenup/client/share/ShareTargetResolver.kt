package com.calypsan.listenup.client.share

/**
 * Decides what a decoded [ShareTarget] should do, given the currently-connected server's
 * stable `instanceId` (or `null` when no server is connected).
 *
 * Pure and shared so Android and iOS behave identically. It returns the instanceId-decidable
 * outcomes; [ShareResolution.NoAccess] is **not** produced here — book access is a server-side
 * decision reached at open time (see [ShareResolution]).
 */
object ShareTargetResolver {
    /** Resolves [target] against [connectedInstanceId] (the connected server's `instanceId`, or `null`). */
    fun resolve(
        target: ShareTarget,
        connectedInstanceId: String?,
    ): ShareResolution =
        when (target) {
            is ShareTarget.Invite -> ShareResolution.OpenInviteClaim(target.serverUrl, target.code)
            is ShareTarget.Book -> resolveBook(target, connectedInstanceId)
        }

    private fun resolveBook(
        book: ShareTarget.Book,
        connectedInstanceId: String?,
    ): ShareResolution =
        when {
            connectedInstanceId == null -> ShareResolution.NotConnected(book.serverUrl)

            // A legacy link carries no server context — open it against whatever server we are on.
            book.serverInstanceId == null -> ShareResolution.OpenBook(book.bookId)

            book.serverInstanceId == connectedInstanceId -> ShareResolution.OpenBook(book.bookId)

            else -> ShareResolution.WrongServer(book.serverUrl)
        }
}
