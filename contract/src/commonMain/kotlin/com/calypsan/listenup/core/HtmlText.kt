@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.core

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

private val LINE_BREAK_TAGS = Regex("""<\s*(br|/p|/div|/li)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")
private val BLANK_LINES = Regex("""\n{3,}""")

/** Entities catalogues send, other than `&amp;`, which [decodeHtmlEntities] must replace last. */
private val NAMED_ENTITIES =
    listOf(
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&nbsp;" to " ",
    )

/**
 * Named character references catalogues actually send, decoded once.
 *
 * `&amp;` goes last so `&amp;lt;` reads as the text `&lt;`, not as `<`.
 */
@HiddenFromObjC
fun decodeHtmlEntities(text: String): String {
    if ('&' !in text) return text
    var decoded = text
    NAMED_ENTITIES.forEach { (entity, character) -> decoded = decoded.replace(entity, character) }
    return decoded.replace("&amp;", "&")
}

/**
 * Catalogue HTML as the words alone: breaks become new lines, tags go, entities are decoded.
 *
 * Entities are decoded after the tags are removed, so an encoded `&lt;b&gt;` stays text.
 */
@HiddenFromObjC
fun htmlToPlainText(html: String): String {
    if ('<' !in html && '&' !in html) return html
    val withoutTags = html.replace(LINE_BREAK_TAGS, "\n").replace(ANY_TAG, "")
    return decodeHtmlEntities(withoutTags).replace(BLANK_LINES, "\n\n").trim()
}
