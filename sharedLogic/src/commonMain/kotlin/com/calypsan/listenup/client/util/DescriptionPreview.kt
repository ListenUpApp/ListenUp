package com.calypsan.listenup.client.util

private val MD_IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
private val MD_LINK = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val MD_HEADING = Regex("""^\s{0,3}#{1,6}\s+""", RegexOption.MULTILINE)
private val MD_BLOCKQUOTE = Regex("""^\s{0,3}>\s?""", RegexOption.MULTILINE)
private val MD_BULLET = Regex("""^\s{0,3}[-*+]\s+""", RegexOption.MULTILINE)
private val MD_ORDERED = Regex("""^\s{0,3}\d+\.\s+""", RegexOption.MULTILINE)
private val MD_BOLD_STAR = Regex("""\*\*(.+?)\*\*""")
private val MD_BOLD_UNDERSCORE = Regex("""__(.+?)__""")
private val MD_STRIKE = Regex("""~~(.+?)~~""")
private val MD_CODE = Regex("""`([^`]+)`""")
private val MD_ITALIC_STAR = Regex("""\*(.+?)\*""")
private val HTML_TAG = Regex("<[^>]+>")
private val WHITESPACE_RUN = Regex("""\s+""")
private val SPACE_BEFORE_PUNCTUATION = Regex(" ([,.;:!?])")

/**
 * Flattens an HTML/Markdown [String] into a single run of plain text for a clamped preview teaser:
 * tags and emphasis markers are removed (keeping their inner text), links collapse to their label,
 * list/heading/quote markers are dropped, and whitespace is normalised.
 *
 * This is deliberately a lightweight flattener, not a parser — the full *styled* rendering is the
 * caller's job (e.g. a Markdown renderer in the expanded view). The patterns avoid look-around so
 * they behave identically on Kotlin/Native.
 */
fun String.toPlainTextPreview(): String {
    var text = this
    // Inline links/images first — they carry the brackets the emphasis passes would trip over.
    text = MD_IMAGE.replace(text) { it.groupValues[1] }
    text = MD_LINK.replace(text) { it.groupValues[1] }
    // Line-anchored block markers.
    text = MD_HEADING.replace(text, "")
    text = MD_BLOCKQUOTE.replace(text, "")
    text = MD_BULLET.replace(text, "")
    text = MD_ORDERED.replace(text, "")
    // Inline emphasis — bold before italic so `**x**` isn't half-eaten by the single-`*` pass.
    text = MD_BOLD_STAR.replace(text) { it.groupValues[1] }
    text = MD_BOLD_UNDERSCORE.replace(text) { it.groupValues[1] }
    text = MD_STRIKE.replace(text) { it.groupValues[1] }
    text = MD_CODE.replace(text) { it.groupValues[1] }
    text = MD_ITALIC_STAR.replace(text) { it.groupValues[1] }
    // Any remaining HTML, then tidy whitespace and the gaps stripped markup leaves behind.
    text = HTML_TAG.replace(text, " ")
    text = WHITESPACE_RUN.replace(text, " ")
    text = SPACE_BEFORE_PUNCTUATION.replace(text, "$1")
    return text.trim()
}
