package com.calypsan.listenup.client.share

/**
 * Pure, two-way translation between share-link URL strings and [ShareTarget].
 *
 * [encode] is used by the Share button (Phase 2/3) to build the shareable `https` link.
 * [decode] is used by both platforms' reception layers to parse an incoming link — the new
 * `https://link.listenup.audio/o#…` form **and** the legacy `listenup://book/{id}` /
 * `listenup://join?…` schemes — so the parsing lives once, in `commonMain`, not per platform.
 *
 * The payload rides in the URL `#fragment` (the host never receives it); [decode] also accepts
 * the same params in the `?query` as a fallback, in case a platform strips the fragment.
 */
object ShareLinkCodec {

    /** Builds a shareable link for [target]. Books → the `https` `/o` form; invites → `listenup://join`. */
    fun encode(target: ShareTarget): String =
        when (target) {
            is ShareTarget.Book -> encodeBook(target)
            is ShareTarget.Invite -> encodeInvite(target)
        }

    private fun encodeBook(book: ShareTarget.Book): String =
        buildString {
            append(ShareLinkConstants.SHARE_URL_PREFIX)
            append("#t=book")
            append("&b=").append(book.bookId.value.percentEncodeQueryValue())
            book.serverInstanceId?.let { append("&i=").append(it.percentEncodeQueryValue()) }
            book.serverUrl?.let { append("&u=").append(it.percentEncodeQueryValue()) }
        }

    private fun encodeInvite(invite: ShareTarget.Invite): String =
        buildString {
            append(ShareLinkConstants.CUSTOM_SCHEME).append("://join")
            append("?server=").append(invite.serverUrl.percentEncodeQueryValue())
            append("&code=").append(invite.code.percentEncodeQueryValue())
        }

    /**
     * Percent-encodes this string as a query parameter value, following RFC 3986 §2.3.
     * Unreserved characters (ALPHA / DIGIT / "-" / "." / "_" / "~") are left as-is;
     * everything else — including ":" and "/" — is percent-encoded with uppercase hex digits.
     */
    private fun String.percentEncodeQueryValue(): String = buildString {
        for (byte in encodeToByteArray()) {
            val ch = byte.toInt() and 0xFF
            if (ch in 0x30..0x39 || // 0-9
                ch in 0x41..0x5A || // A-Z
                ch in 0x61..0x7A || // a-z
                ch == 0x2D || // -
                ch == 0x2E || // .
                ch == 0x5F || // _
                ch == 0x7E // ~
            ) {
                append(ch.toChar())
            } else {
                append('%')
                append(ch.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
