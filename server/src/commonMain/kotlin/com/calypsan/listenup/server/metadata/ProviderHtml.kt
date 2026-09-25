package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.server.metadata.audible.decodeHtmlEntities

// ─── Regexes for the HTML-to-plain-text converter ──────────────────────────────
// Case-insensitive: providers are inconsistent about tag casing (`<p>` vs `<P>`).

private val BR_TAG = Regex("(?i)<br\\s*/?>")
private val LI_CLOSE_TAG = Regex("(?i)</li>")
private val LI_OPEN_TAG = Regex("(?i)<li[^>]*>")
private val P_CLOSE_TAG = Regex("(?i)</p>")
private val P_OPEN_TAG = Regex("(?i)<p[^>]*>")
private val ANY_TAG = Regex("<[^>]+>")
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
 * `<br>` (and its `<br/>`/`<br />` variants) become a single newline, `</p>` (and
 * a following `<p>`) become a blank line between paragraphs, and each `<li>`
 * becomes its own line. Every other tag is stripped, keeping its inner text in
 * place. The entities [decodeHtmlEntities] already knows are decoded once, from
 * the one table it owns.
 */
internal fun providerHtmlToPlainText(html: String): String {
    if (html.isBlank()) return ""

    val withLineBreaks =
        html
            .replace(BR_TAG, "\n")
            .replace(LI_CLOSE_TAG, "")
            .replace(LI_OPEN_TAG, "\n")
            .replace(P_CLOSE_TAG, "\n\n")
            .replace(P_OPEN_TAG, "\n\n")
            .replace(ANY_TAG, "")

    val decoded = decodeHtmlEntities(withLineBreaks)

    return decoded
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }
        .replace(THREE_OR_MORE_NEWLINES, "\n\n")
        .trim()
}
