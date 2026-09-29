package com.calypsan.listenup.server.goodreads

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A Goodreads book page's rating: [average] out of 5 across [count] ratings. */
data class GoodreadsRating(
    val average: Double,
    val count: Int,
)

/** One Goodreads search result: the book page's site-relative [bookPath], its [title] and first [author]. */
data class GoodreadsCandidate(
    val bookPath: String,
    val title: String,
    val author: String?,
)

/**
 * Reads the two Goodreads pages the rating source needs. Pure and I/O-free.
 *
 * The rating is read only from the book page's JSON-LD (`<script type="application/ld+json">`), the
 * structured data Goodreads publishes for search engines; never from visible HTML, whose numbers are
 * formatted for people ("1,886,867 ratings") and move whenever the page is restyled. A rating needs
 * both its value and its count: half a number is no number.
 *
 * Search results carry no JSON-LD, so [searchResults] reads the rows' schema.org microdata
 * (`itemtype="http://schema.org/Book"`, `itemprop="name"`), which names each result's link, title and
 * author. It reads no numbers from them.
 */
internal object GoodreadsPages {
    private val json = Json { ignoreUnknownKeys = true }

    private const val JSON_LD_TYPE = "application/ld+json"
    private const val SCRIPT_END = "</script>"
    private const val SEARCH_ROW = "itemtype=\"http://schema.org/Book\""
    private const val TITLE_LINK = "class=\"bookTitle\""
    private const val TITLE_NAME = "<span itemprop='name'"
    private const val AUTHOR_NAME = "<span itemprop=\"name\">"
    private const val SPAN_END = "</span>"
    private const val MAX_AVERAGE = 5.0

    /** The book page's JSON-LD `aggregateRating`, or `null` when it has none, or only part of one. */
    fun aggregateRating(html: String): GoodreadsRating? =
        jsonLdBlocks(html)
            .flatMap { it.objects() }
            .firstNotNullOfOrNull { (it["aggregateRating"] as? JsonObject)?.toRating() }

    /** Every result on a search page, in Goodreads' own relevance order; empty for any other page. */
    fun searchResults(html: String): List<GoodreadsCandidate> =
        html.split(SEARCH_ROW).drop(1).mapNotNull { row ->
            val bookPath = row.attributeAfter(TITLE_LINK, "href")?.substringBefore('?')
            val title = row.textAfter(TITLE_NAME)
            if (bookPath == null || title == null) {
                null
            } else {
                GoodreadsCandidate(bookPath = bookPath, title = title, author = row.textAfter(AUTHOR_NAME))
            }
        }

    private fun jsonLdBlocks(html: String): List<JsonElement> {
        val blocks = mutableListOf<JsonElement>()
        var marker = html.indexOf(JSON_LD_TYPE)
        while (marker >= 0) {
            val start = html.indexOf('>', marker) + 1
            val end = if (start > 0) html.indexOf(SCRIPT_END, start) else -1
            if (end < 0) break
            parse(html.substring(start, end))?.let(blocks::add)
            marker = html.indexOf(JSON_LD_TYPE, end)
        }
        return blocks
    }

    private fun parse(block: String): JsonElement? =
        try {
            json.parseToJsonElement(block)
        } catch (_: SerializationException) {
            null
        }

    /** The objects a JSON-LD block holds: itself and its `@graph`, or the members of a top-level array. */
    private fun JsonElement.objects(): List<JsonObject> =
        when (this) {
            is JsonObject -> listOf(this) + (this["@graph"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            is JsonArray -> filterIsInstance<JsonObject>()
            else -> emptyList()
        }

    /** Schema.org allows a number or a string for both fields, so both are read. */
    private fun JsonObject.toRating(): GoodreadsRating? {
        val average = (this["ratingValue"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val count = (this["ratingCount"] as? JsonPrimitive)?.content?.toIntOrNull()
        return if (average == null || count == null || count <= 0 || average !in 0.0..MAX_AVERAGE) {
            null
        } else {
            GoodreadsRating(average = average, count = count)
        }
    }

    /** The value of [attribute] on the tag that [marker] sits inside, entity-decoded. */
    private fun String.attributeAfter(
        marker: String,
        attribute: String,
    ): String? {
        val tagStart = indexOf(marker)
        val tagEnd = if (tagStart >= 0) indexOf('>', tagStart) else -1
        if (tagEnd < 0) return null
        val tag = substring(tagStart, tagEnd)
        val opener = "$attribute=\""
        val valueStart = tag.indexOf(opener)
        val valueEnd = if (valueStart >= 0) tag.indexOf('"', valueStart + opener.length) else -1
        return if (valueEnd < 0) null else tag.substring(valueStart + opener.length, valueEnd).decodeEntities()
    }

    /** The text inside the first element opened by [marker], entity-decoded and trimmed; `null` when blank. */
    private fun String.textAfter(marker: String): String? {
        val tagStart = indexOf(marker)
        val textStart = if (tagStart >= 0) indexOf('>', tagStart) + 1 else 0
        val textEnd = if (textStart > 0) indexOf(SPAN_END, textStart) else -1
        return if (textEnd < 0) null else substring(textStart, textEnd).decodeEntities().trim().ifEmpty { null }
    }

    private fun String.decodeEntities(): String =
        replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
