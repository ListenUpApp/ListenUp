package com.calypsan.listenup.client

/** Marker written in place of a fragment or query value that must never reach a log. */
private const val REDACTED = "…"

/**
 * Renders a deep-link URL for a log line with every query VALUE and the whole fragment removed.
 *
 * An invite link carries `code=` — a bearer secret that admits its holder to the server — and
 * `ShareLinkCodec` accepts the payload in either the query or a legacy `#fragment`. Every
 * Android log call is teed to a file (`TeeLogger`) that the Settings "Share logs" action hands
 * to an arbitrary app, so a raw URL in a log line is a credential in a shared file.
 *
 * Parameter NAMES survive: they are what makes the line diagnosable ("the link had no `t`"),
 * and they carry nothing secret. Hand-parsed rather than routed through a URL parser because
 * this runs on exactly the input a parser already rejected.
 */
internal fun redactLinkForLog(raw: String): String {
    val hasFragment = raw.contains('#')
    val withoutFragment = raw.substringBefore('#')
    val base = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', "")
    val names =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .joinToString("&") { it.substringBefore('=') }
    return buildString {
        append(base)
        if (names.isNotEmpty()) append('?').append(names)
        if (hasFragment) append('#').append(REDACTED)
    }
}
