package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.sync.parseSeriesSequence
import com.calypsan.listenup.server.io.hashBytesSha256

private const val OPTION_HASH_LENGTH = 12
private const val YEAR_DIGITS = 4
private val HTML_TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
private val SPACE_BEFORE_PUNCTUATION = Regex(" ([.,;:!?])")
private val HTML_ENTITIES =
    mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ")

/**
 * How Review compares values (spec, *Field candidates*): whitespace and case folded for text, HTML stripped for
 * descriptions, people as an order-insensitive name set, series as name plus sequence, release dates by year.
 * Two values with the same key are "the same", and the same key always yields the same option id.
 */
internal object ReviewKeys {
    /** The comparison key of [value]; [html] strips markup first (descriptions). */
    fun keyOf(
        value: FieldValue,
        html: Boolean = false,
    ): String =
        when (value) {
            is FieldValue.Text -> {
                "t:" + text(if (html) stripHtml(value.text) else value.text)
            }

            is FieldValue.People -> {
                "p:" +
                    value.names
                        .map(::text)
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                        .joinToString("|")
            }

            is FieldValue.SeriesEntries -> {
                "s:" +
                    value.entries
                        .map { "${text(it.name)}#${parseSeriesSequence(it.sequence) ?: it.sequence?.let(::text)}" }
                        .distinct()
                        .sorted()
                        .joinToString("|")
            }

            is FieldValue.Year -> {
                "y:${value.year}"
            }
        }

    /** `"<provider>:<sha256(key)[0..12]>"` — stable across derivations of the same option. */
    fun optionId(
        provider: String,
        key: String,
    ): String = provider + ":" + hashBytesSha256(key.encodeToByteArray()).take(OPTION_HASH_LENGTH)

    /** Folds whitespace and case: what "the same text" means everywhere in Review. */
    fun text(raw: String): String = raw.trim().replace(WHITESPACE, " ").lowercase()

    /** The year a provider's release date names (`"2021-03-04"` → 2021), or null. */
    fun yearOf(releaseDate: String?): Int? =
        releaseDate
            ?.trim()
            ?.take(YEAR_DIGITS)
            ?.takeIf { it.length == YEAR_DIGITS && it.all(Char::isDigit) }
            ?.toInt()

    /** Markup becomes spacing (so paragraphs don't run together), and a tag before punctuation leaves no gap. */
    private fun stripHtml(raw: String): String =
        HTML_ENTITIES.entries
            .fold(raw.replace(HTML_TAG, " ")) { acc, (entity, char) -> acc.replace(entity, char) }
            .replace(WHITESPACE, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
}
