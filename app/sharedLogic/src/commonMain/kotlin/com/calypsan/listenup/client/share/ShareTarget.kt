package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId

/**
 * A decoded share / deep-link target — what the user is trying to open.
 *
 * This is the generalized model: [Book] ships first; series and contributors slot in later
 * as new variants with no change to the codec or resolver call sites. Parity between Android
 * and iOS comes from both platforms decoding to — and resolving — this one type.
 */
sealed interface ShareTarget {
    /**
     * A shared book.
     *
     * @property bookId The server-scoped book id to open.
     * @property serverInstanceId The stable `instanceId` of the server that produced the link,
     *   or `null` when the link carried no server context (it then resolves against the
     *   currently-connected server).
     * @property serverUrl The source server's URL, for display and a possible future "connect"
     *   action — never the identity key (URLs vary LAN-vs-remote). `null` when the link carried none.
     *
     * Note: [serverInstanceId] and [serverUrl] are independently optional (they map to the link's
     * separate `i` and `u` params). This app's encoder always supplies both together from a
     * connected server; a link carrying one but not the other is representable but degenerate —
     * the resolver treats a null [serverInstanceId] as a context-free link and ignores [serverUrl] then.
     */
    data class Book(
        val bookId: BookId,
        val serverInstanceId: String?,
        val serverUrl: String?,
    ) : ShareTarget

    /**
     * An invite / join claim.
     *
     * @property serverUrl The server the invite is for — the admin's local/active URL, persisted
     *   before lookup so a fresh install can reach it.
     * @property code The invite code.
     * @property remoteUrl The server's operator-set remote (WAN) URL when configured, so an invitee
     *   off the local network can still connect; `null` when the server advertises none. The claim
     *   flow tries [serverUrl] first and falls back to [remoteUrl].
     */
    data class Invite(
        val serverUrl: String,
        val code: String,
        val remoteUrl: String? = null,
    ) : ShareTarget
}
