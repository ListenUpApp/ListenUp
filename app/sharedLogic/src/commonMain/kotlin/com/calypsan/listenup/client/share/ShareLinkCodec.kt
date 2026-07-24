package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString

/**
 * Pure, two-way translation between share-link URL strings and [ShareTarget].
 *
 * Every shareable link is the `https://link.listenup.audio/o?…` universal-link form — one shape
 * for books and invites alike, so a tapped link opens the app (or falls back to the install page)
 * on both platforms. [encode] builds it; [decode] parses it, living once in `commonMain` rather
 * than per platform.
 *
 * The payload rides in the URL `?query`: iOS Universal Links do not reliably deliver the URL
 * `#fragment` to the app (`NSUserActivity.webpageURL` drops it), and the redirector's `/o`→`/o/`
 * 308 strands a fragment too — a query survives both. [decode] still accepts the legacy `#fragment`
 * form so links shared before this change keep working when they arrive intact (e.g. on Android).
 */
object ShareLinkCodec {
    /**
     * RFC 3986 §2.3 unreserved characters — safe to appear in a percent-encoded value without
     * being escaped. All other octets (including `:` and `/`) are percent-encoded.
     */
    private val rfc3986UnreservedChars: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

    /** Builds the `https` `/o` universal-link for [target] — `t=book` for books, `t=invite` for invites. */
    fun encode(target: ShareTarget): String =
        when (target) {
            is ShareTarget.Book -> encodeBook(target)
            is ShareTarget.Invite -> encodeInvite(target)
        }

    /**
     * Parses [raw] into a [ShareTarget], or `null` if it is not a recognised ListenUp link.
     * Foreign hosts and unknown entity types return `null` (forward-compatible, not a crash).
     */
    fun decode(raw: String): ShareTarget? {
        val url = raw.trim()
        if (!url.startsWith(ShareLinkConstants.SHARE_URL_PREFIX, ignoreCase = true)) return null
        return decodeHttpsForm(url)
    }

    private fun encodeBook(book: ShareTarget.Book): String =
        buildString {
            append(ShareLinkConstants.SHARE_URL_PREFIX)
            append("?t=book")
            append("&b=").append(book.bookId.value.percentEncodeQueryValue())
            book.serverInstanceId?.let { append("&i=").append(it.percentEncodeQueryValue()) }
            book.serverUrl?.let { append("&u=").append(it.percentEncodeQueryValue()) }
        }

    private fun encodeInvite(invite: ShareTarget.Invite): String =
        buildString {
            append(ShareLinkConstants.SHARE_URL_PREFIX)
            append("?t=invite")
            append("&server=").append(invite.serverUrl.percentEncodeQueryValue())
            append("&code=").append(invite.code.percentEncodeQueryValue())
            // Optional remote (WAN) URL so an off-LAN invitee can still connect; older links omit it.
            invite.remoteUrl?.takeIf { it.isNotBlank() }?.let {
                append("&remote=").append(it.percentEncodeQueryValue())
            }
        }

    private fun decodeHttpsForm(url: String): ShareTarget? {
        // Length-based slice is safe: the prefix matched ignore-case, so its length is unchanged.
        val remainder = url.substring(ShareLinkConstants.SHARE_URL_PREFIX.length)
        // A single trailing slash is equivalent to no slash (redirectors/bots hand us both forms);
        // beyond that the path must end at /o exactly — reject /o/something or /other.
        val afterOptionalSlash = remainder.removePrefix("/")
        if (afterOptionalSlash.isNotEmpty() && afterOptionalSlash[0] !in charArrayOf('#', '?')) return null
        val query = afterOptionalSlash.substringAfter('?', "").substringBefore('#')
        val fragment = afterOptionalSlash.substringAfter('#', "")
        val params = parseQueryString(fragment.ifEmpty { query })
        return when (params["t"]) {
            "book" -> decodeBookParams(params)
            "invite" -> decodeInviteParams(params)
            else -> null
        }
    }

    private fun decodeBookParams(params: Parameters): ShareTarget? {
        val bookId = params["b"]?.takeIf { it.isNotBlank() } ?: return null
        return ShareTarget.Book(
            bookId = BookId(bookId),
            serverInstanceId = params["i"]?.takeIf { it.isNotBlank() },
            serverUrl = params["u"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun decodeInviteParams(params: Parameters): ShareTarget? {
        val server = params["server"]?.takeIf { it.isNotBlank() } ?: return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        return ShareTarget.Invite(
            serverUrl = server,
            code = code,
            remoteUrl = params["remote"]?.takeIf { it.isNotBlank() },
        )
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
