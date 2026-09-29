package com.calypsan.listenup.server.goodreads

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a Goodreads book page says about its rating. */
sealed interface GoodreadsBookRating {
    /** Goodreads' readers rated the book [average] out of 5 across [count] ratings. */
    data class Rated(
        val average: Double,
        val count: Int,
    ) : GoodreadsBookRating

    /** The page describes the book, and nobody has rated it: Goodreads then publishes no rating at all. */
    data object Unrated : GoodreadsBookRating

    /** The page is not a book page this parser recognises, or its rating is incomplete or unreadable. */
    data object Unrecognised : GoodreadsBookRating
}

/** What a Goodreads search page lists. */
sealed interface GoodreadsSearch {
    /** Every result, in Goodreads' own relevance order; empty when Goodreads says it found nothing. */
    data class Results(
        val candidates: List<GoodreadsCandidate>,
    ) : GoodreadsSearch

    /** The page is not a search page this parser recognises. */
    data object Unrecognised : GoodreadsSearch
}

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
 * formatted for people ("1,886,867 ratings") and move whenever the page is restyled. The page's
 * JSON-LD `Book` decides: with an `aggregateRating` the book is [GoodreadsBookRating.Rated]; without
 * one it is [GoodreadsBookRating.Unrated] (Goodreads omits the block for a book with no ratings). No
 * `Book` at all, or a rating missing its value or count, is [GoodreadsBookRating.Unrecognised]: half a
 * number is no number.
 *
 * Search results carry no JSON-LD, so [searchResults] reads the rows' schema.org microdata
 * (`itemtype="http://schema.org/Book"`, `itemprop="name"`), which names each result's link, title and
 * author. It reads no numbers from them. A page with no readable rows is an empty result only when
 * Goodreads says so ([NO_RESULTS]); otherwise the page is not one this parser recognises.
 */
internal object GoodreadsPages {
    private val json = Json { ignoreUnknownKeys = true }

    private const val JSON_LD_TYPE = "application/ld+json"
    private const val SCRIPT_END = "</script>"
    private const val BOOK_TYPE = "Book"
    private const val SEARCH_ROW = "itemtype=\"http://schema.org/Book\""
    private const val TITLE_LINK = "class=\"bookTitle\""
    private const val TITLE_NAME = "<span itemprop='name'"
    private const val AUTHOR_NAME = "<span itemprop=\"name\">"
    private const val SPAN_END = "</span>"
    private const val MAX_AVERAGE = 5.0

    /** How Goodreads' search page says it found nothing: its result summary reads "No results." */
    private const val NO_RESULTS = "searchSubNavContainer\">No results."

    /** What the book page's JSON-LD `Book` says about its rating. */
    fun bookRating(html: String): GoodreadsBookRating {
        val book =
            jsonLdBlocks(html)
                .flatMap { it.objects() }
                .firstOrNull { it.isBook() }
                ?: return GoodreadsBookRating.Unrecognised
        val rating = book["aggregateRating"] ?: return GoodreadsBookRating.Unrated
        return (rating as? JsonObject)?.toRating() ?: GoodreadsBookRating.Unrecognised
    }

    /** Every result on a search page, in Goodreads' own relevance order. */
    fun searchResults(html: String): GoodreadsSearch {
        val candidates = rows(html)
        return when {
            candidates.isNotEmpty() -> GoodreadsSearch.Results(candidates)
            NO_RESULTS in html -> GoodreadsSearch.Results(emptyList())
            else -> GoodreadsSearch.Unrecognised
        }
    }

    private fun rows(html: String): List<GoodreadsCandidate> =
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

    /** A JSON-LD `@type` may be one type or a list of them. */
    private fun JsonObject.isBook(): Boolean =
        when (val type = this["@type"]) {
            is JsonPrimitive -> type.content == BOOK_TYPE
            is JsonArray -> type.any { (it as? JsonPrimitive)?.content == BOOK_TYPE }
            else -> false
        }

    /**
     * Schema.org allows a number or a string for both fields, so both are read. A count of zero says
     * outright that nobody has rated the book; anything unreadable is no rating at all.
     */
    private fun JsonObject.toRating(): GoodreadsBookRating? {
        val average = (this["ratingValue"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val count = (this["ratingCount"] as? JsonPrimitive)?.content?.toIntOrNull()
        return when {
            average == null || count == null || count < 0 || average !in 0.0..MAX_AVERAGE -> null
            count == 0 -> GoodreadsBookRating.Unrated
            else -> GoodreadsBookRating.Rated(average = average, count = count)
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
