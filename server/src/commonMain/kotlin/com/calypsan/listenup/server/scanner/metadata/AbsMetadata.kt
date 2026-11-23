package com.calypsan.listenup.server.scanner.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schema for ABS-authored `metadata.json` sidecar files.
 *
 * ABS writes one of these in every book folder when `storeMetadataWithItem`
 * is on (the common self-hoster setting). The schema is flat in current ABS
 * versions; legacy ABS versions wrap fields in a nested `metadata` object —
 * the reader auto-flattens both forms.
 *
 * `series` arrives as an array of `"Name #seq"` strings — this DTO keeps
 * them as raw strings; the reader splits them into
 * [com.calypsan.listenup.api.dto.scanner.SeriesEntry] downstream.
 *
 * Source: ABS `server/scanner/BookScanner.js:810-879` (`saveMetadataFile`)
 * and `abmetadataGenerator.js:9-20` (legacy unwrap).
 */
@Serializable
data class AbsMetadata(
    @SerialName("title")
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /**
     * As written, any JSON shape. ABS stores `publishedYear` as a string filled verbatim from the audio
     * file's date tag (ABS `server/models/Book.js`, `server/scanner/AudioFileScanner.js`), so a full date
     * like `"2011-05-03"` is ordinary — and the file is decoded all-or-nothing, so a strict `Int`
     * here would discard every other field over it. Read it through [publishedYear].
     */
    @SerialName("publishedYear")
    val publishedYearRaw: JsonElement? = null,
    val publishedDate: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val language: String? = null,
    val explicit: Boolean? = null,
    val abridged: Boolean? = null,
    val chapters: List<AbsChapter> = emptyList(),
) {
    /**
     * The publication year: the leading four digits of `publishedYear` when they form a plausible
     * year, else those of `publishedDate`, else null.
     */
    val publishedYear: Int?
        get() = leadingYear((publishedYearRaw as? JsonPrimitive)?.content) ?: leadingYear(publishedDate)
}

private const val YEAR_DIGITS = 4
private val PLAUSIBLE_YEARS = 1000..2999

private fun leadingYear(text: String?): Int? {
    val digits = text?.run { trim().take(YEAR_DIGITS) } ?: return null
    if (digits.length != YEAR_DIGITS || !digits.all(Char::isDigit)) return null
    return digits.toInt().takeIf { it in PLAUSIBLE_YEARS }
}
