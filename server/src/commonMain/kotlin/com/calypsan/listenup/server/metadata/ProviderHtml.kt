package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.server.metadata.audible.decodeHtmlEntities

// ─── Regexes for the HTML-to-plain-text converter ──────────────────────────────
// Case-insensitive: providers are inconsistent about tag casing (`<p>` vs `<P>`).

// `\b` after the tag name keeps these from also matching an unrelated tag that merely starts
// with the same letters — `<p\b` doesn't match `<pre>`/`<param>`/`<picture>`, `<li\b` doesn't
// match `<link>`. Close tags need no `\b` since nothing follows the name but optional whitespace
// and `>`; `</p\s*>` doesn't match `</pre>` because `re` isn't whitespace.
private val BR_TAG = Regex("(?i)<br\\b[^>]*>")
private val LI_CLOSE_TAG = Regex("(?i)</li\\s*>")
private val LI_OPEN_TAG = Regex("(?i)<li\\b[^>]*>")
private val P_CLOSE_TAG = Regex("(?i)</p\\s*>")
private val P_OPEN_TAG = Regex("(?i)<p\\b[^>]*>")
private val ANY_TAG = Regex("<[^>]+>")
private val WHITESPACE_RUN = Regex("\\s+")
private val THREE_OR_MORE_NEWLINES = Regex("\n{3,}")

/**
 * Converts a metadata provider's HTML-formatted text to plain text, preserving
 * paragraph structure.
 *
 * Providers ship full book descriptions and author bios as HTML — `<p>` blocks,
 * inline `<b>`/`<i>` emphasis, `<br>` line breaks, `<li>` list items, and HTML
 * entities — but ListenUp clients render plain text. Naively stripping every tag
 * collapses a multi-paragraph publisher summary into one run-on block, which is
 * worse than the short blurb it replaces. This keeps `<p>`/`<li>` boundaries as
 * line breaks so the text still reads the way the publisher intended, while
 * inline emphasis tags contribute no break at all.
 *
 * `<br>` (and its `<br/>`/`<br />` variants, including attributes) become a single
 * newline, `</p>` (and a following `<p>`) become a blank line between paragraphs,
 * and each `<li>` becomes its own line. Every other tag is stripped, keeping its
 * inner text in place. The entities [decodeHtmlEntities] already knows are
 * decoded once, from the one table it owns.
 *
 * HTML collapses any run of whitespace — including the newlines and indentation
 * a provider's source formatting adds — to a single space, so that whitespace is
 * collapsed on the raw markup *before* the `<br>`/`<p>`/`<li>` breaks are inserted;
 * otherwise a provider's own line-wrapping would leak through as spurious breaks
 * inside a paragraph. Each resulting line is trimmed on both ends (not just
 * trailing), since a break can land a stray space at the start of the next line.
 */
internal fun providerHtmlToPlainText(html: String): String {
    if (html.isBlank()) return ""

    val collapsedWhitespace = html.replace(WHITESPACE_RUN, " ")

    val withLineBreaks =
        collapsedWhitespace
            .replace(BR_TAG, "\n")
            .replace(LI_CLOSE_TAG, "")
            .replace(LI_OPEN_TAG, "\n")
            .replace(P_CLOSE_TAG, "\n\n")
            .replace(P_OPEN_TAG, "\n\n")
            .replace(ANY_TAG, "")

    val decoded = decodeHtmlEntities(withLineBreaks)

    return decoded
        .lineSequence()
        .joinToString("\n") { it.trim() }
        .replace(THREE_OR_MORE_NEWLINES, "\n\n")
        .trim()
}
