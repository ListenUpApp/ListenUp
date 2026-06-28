package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId
import io.ktor.http.parseQueryString

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
    /**
     * RFC 3986 §2.3 unreserved characters — safe to appear in a percent-encoded value without
     * being escaped. All other octets (including `:` and `/`) are percent-encoded.
     */
    private val rfc3986UnreservedChars: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

    /**
     * Builds a shareable link for [target]. Both books and invites use the `https`
     * universal-link `/o#…` form (`t=book` / `t=invite`) so the link is a real, tappable
     * Universal Link / App Link that opens the app via the `link.listenup.audio` associated
     * domain. The legacy `listenup://book/{id}` / `listenup://join?…` custom schemes are still
     * accepted by [decode] for links shared before this change.
     */
    fun encode(target: ShareTarget): String =
        when (target) {
            is ShareTarget.Book -> encodeBook(target)
            is ShareTarget.Invite -> encodeInvite(target)
        }

    /**
     * Parses [raw] into a [ShareTarget], or `null` if it is not a recognised ListenUp link.
     * Unknown entity types and foreign hosts return `null` (forward-compatible, not a crash).
     */
    fun decode(raw: String): ShareTarget? {
        val url = raw.trim()
        return when {
            url.startsWith(ShareLinkConstants.SHARE_URL_PREFIX, ignoreCase = true) -> decodeHttpsForm(url)
            url.startsWith("${ShareLinkConstants.CUSTOM_SCHEME}://", ignoreCase = true) -> decodeCustomScheme(url)
            else -> null
        }
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
            append(ShareLinkConstants.SHARE_URL_PREFIX)
            append("#t=invite")
            append("&server=").append(invite.serverUrl.percentEncodeQueryValue())
            append("&code=").append(invite.code.percentEncodeQueryValue())
        }

    private fun decodeHttpsForm(url: String): ShareTarget? {
        // Length-based slice is safe: the prefix matched ignore-case, so its length is unchanged.
        val remainder = url.substring(ShareLinkConstants.SHARE_URL_PREFIX.length)
        // The path must end at /o exactly — reject /o/something or /other.
        if (remainder.isNotEmpty() && remainder[0] !in charArrayOf('#', '?')) return null
        val query = remainder.substringAfter('?', "").substringBefore('#')
        val fragment = remainder.substringAfter('#', "")
        val params = parseQueryString(fragment.ifEmpty { query })
        return when (params["t"]) {
            "book" -> {
                val bookId = params["b"]?.takeIf { it.isNotBlank() } ?: return null
                ShareTarget.Book(
                    bookId = BookId(bookId),
                    serverInstanceId = params["i"]?.takeIf { it.isNotBlank() },
                    serverUrl = params["u"]?.takeIf { it.isNotBlank() },
                )
            }

            "invite" -> {
                val server = params["server"]?.takeIf { it.isNotBlank() } ?: return null
                val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
                ShareTarget.Invite(serverUrl = server, code = code)
            }

            else -> {
                null
            }
        }
    }

    private fun decodeCustomScheme(url: String): ShareTarget? {
        val afterScheme = url.substring("${ShareLinkConstants.CUSTOM_SCHEME}://".length)
        val host =
            afterScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .lowercase()
        return when (host) {
            "book" -> decodeLegacyBookScheme(afterScheme)
            "join" -> decodeLegacyJoinScheme(afterScheme)
            else -> null
        }
    }

    private fun decodeLegacyBookScheme(afterScheme: String): ShareTarget? {
        val id =
            afterScheme
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
                .split('/')
                .firstOrNull { it.isNotBlank() }
                ?: return null
        return ShareTarget.Book(bookId = BookId(id), serverInstanceId = null, serverUrl = null)
    }

    private fun decodeLegacyJoinScheme(afterScheme: String): ShareTarget? {
        val query = afterScheme.substringAfter('?', "").substringBefore('#')
        val params = parseQueryString(query)
        val server = params["server"]?.takeIf { it.isNotBlank() } ?: return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        return ShareTarget.Invite(serverUrl = server, code = code)
    }

    /**
     * Percent-encodes this string as a query parameter value, following RFC 3986 §2.3.
     * Unreserved characters (ALPHA / DIGIT / "-" / "." / "_" / "~") are left as-is;
     * everything else — including ":" and "/" — is percent-encoded with uppercase hex digits.
     */
    private fun String.percentEncodeQueryValue(): String =
        buildString {
            for (byte in encodeToByteArray()) {
                val ch = (byte.toInt() and 0xFF).toChar()
                if (ch in rfc3986UnreservedChars) {
                    append(ch)
                } else {
                    append('%')
                    append(
                        ch.code
                            .toString(16)
                            .uppercase()
                            .padStart(2, '0'),
                    )
                }
            }
        }
}
